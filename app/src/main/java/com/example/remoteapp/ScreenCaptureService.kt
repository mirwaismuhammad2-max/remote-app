package com.example.remoteapp

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.webrtc.*

class ScreenCaptureService : Service(), SignalingClient.Listener {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val NOTIFICATION_ID = 42
    }

    private lateinit var signalingClient: SignalingClient
    private lateinit var whitelistManager: WhitelistManager
    private lateinit var commandProcessor: RemoteCommandProcessor
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var permissionIntent: Intent? = null

    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var dataChannel: DataChannel? = null

    private fun toast(text: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        toast("RemoteApp: service starting")

        if (intent == null) {
            toast("RemoteApp: no intent, stopping")
            return START_NOT_STICKY
        }

        whitelistManager = WhitelistManager(applicationContext)
        commandProcessor = RemoteCommandProcessor(applicationContext, whitelistManager)

        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val pairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE)

        if (pairingCode == null) {
            toast("RemoteApp: missing pairing code, stopping")
            return START_NOT_STICKY
        }

        if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
            toast("RemoteApp: screen capture permission not granted, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        permissionIntent = resultData

        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)

        setupWebRtc()
        toast("RemoteApp: connecting to Firebase, code $pairingCode")
        signalingClient = SignalingClient(pairingCode, this)
        signalingClient.connect()

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, RemoteControlApp.CHANNEL_ID)
            .setContentTitle("Remote session active")
            .setContentText("Your screen is being shared to a paired device.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun setupWebRtc() {
        eglBase = EglBase.create()

        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(applicationContext)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun startCapture() {
        val permIntent = permissionIntent ?: run {
            toast("RemoteApp: no permission intent for capture")
            return
        }

        videoCapturer = ScreenCapturerAndroid(
            permIntent,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }
        )

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = peerConnectionFactory.createVideoSource(true)
        videoCapturer?.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)

        videoCapturer?.startCapture(1280, 720, 15)

        localVideoTrack = peerConnectionFactory.createVideoTrack("screen_video", videoSource)
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    signalingClient.sendIceCandidate(
                        candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp
                    )
                }
                override fun onDataChannel(channel: DataChannel) {
                    dataChannel = channel
                    observeDataChannel(channel)
                }
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )

        localVideoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("screen_stream"))
        }

        val dcInit = DataChannel.Init()
        dataChannel = peerConnection?.createDataChannel("control", dcInit)
        dataChannel?.let { observeDataChannel(it) }
    }

    private fun observeDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    sendAppListOverDataChannel()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                handleControlMessage(text)
            }
        })
    }

    private fun sendAppListOverDataChannel() {
        val arr = org.json.JSONArray()
        whitelistManager.getWhitelistedApps().forEach {
            arr.put(JSONObject().apply {
                put("packageName", it.packageName)
                put("label", it.label)
            })
        }
        val json = JSONObject().apply {
            put("cmd", "app-list")
            put("apps", arr)
        }
        sendOverDataChannel(json.toString())
    }

    private fun sendOverDataChannel(text: String) {
        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false
        )
        dataChannel?.send(buffer)
    }

    private fun handleControlMessage(text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        val cmd = json.optString("cmd")

        val outcome = when (cmd) {
            "launch" -> commandProcessor.handleLaunch(json.getString("packageName"))
            "close" -> commandProcessor.handleClose(json.getString("packageName"))
            "tap" -> commandProcessor.handleTap(
                json.getDouble("x").toFloat(), json.getDouble("y").toFloat()
            )
            "swipe" -> commandProcessor.handleSwipe(
                json.getDouble("x1").toFloat(), json.getDouble("y1").toFloat(),
                json.getDouble("x2").toFloat(), json.getDouble("y2").toFloat(),
                json.optLong("durationMs", 300)
            )
            "longPress" -> commandProcessor.handleLongPress(
                json.getDouble("x").toFloat(), json.getDouble("y").toFloat()
            )
            "type" -> commandProcessor.handleType(json.getString("text"))
            "back" -> commandProcessor.handleBack()
            else -> RemoteCommandProcessor.CommandOutcome.Error("Unknown command: $cmd")
        }

        val ackJson = JSONObject().apply {
            put("cmd", "ack")
            put("for", cmd)
            put("outcome", outcome::class.simpleName)
        }
        sendOverDataChannel(ackJson.toString())
    }

    override fun onConnected() {
        toast("RemoteApp: connected to Firebase, waiting for viewer")
    }

    override fun onPeerJoined() {
        toast("RemoteApp: viewer joined! Starting capture")
        startCapture()
        createPeerConnection()

        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                signalingClient.sendSdp("offer", desc.description)
                toast("RemoteApp: offer sent")
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                toast("RemoteApp: create offer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    override fun onRemoteSdp(type: String, sdp: String) {
        if (type == "answer") {
            toast("RemoteApp: answer received")
            val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            peerConnection?.setRemoteDescription(SimpleSdpObserver(), desc)
        }
    }

    override fun onRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    override fun onDisconnected() {
        toast("RemoteApp: disconnected")
    }

    override fun onError(message: String) {
        toast("RemoteApp ERROR: $message")
    }

    override fun onDestroy() {
        super.onDestroy()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()
        dataChannel?.close()
        peerConnection?.close()
        mediaProjection?.stop()
        if (::signalingClient.isInitialized) signalingClient.disconnect()
    }
}

private class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
