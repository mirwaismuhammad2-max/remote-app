package com.example.remoteapp

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the small Node.js relay server in /signaling-server.
 * Responsibilities:
 *   - join a "room" identified by a pairing code
 *   - relay WebRTC offer/answer/ice-candidate messages between phone and viewer
 * This class does NOT know about WebRTC internals — it just moves JSON
 * strings back and forth. ScreenCaptureService builds/parses the payloads.
 */
class SignalingClient(
    private val serverUrl: String, // e.g. "wss://your-server.example.com"
    private val pairingCode: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onPeerJoined()
        fun onRemoteSdp(type: String, sdp: String) // "offer" or "answer"
        fun onRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onControlMessage(json: JSONObject) // launch/close/tap/swipe/type commands
        fun onDisconnected()
        fun onError(message: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket stays open indefinitely
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                val join = JSONObject().apply {
                    put("type", "join")
                    put("role", "host") // the phone is always "host"; the viewer joins as "viewer"
                    put("room", pairingCode)
                }
                webSocket.send(join.toString())
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "peer-joined" -> listener.onPeerJoined()
                        "offer", "answer" -> listener.onRemoteSdp(
                            json.getString("type"),
                            json.getString("sdp")
                        )
                        "ice-candidate" -> listener.onRemoteIceCandidate(
                            json.getString("sdpMid"),
                            json.getInt("sdpMLineIndex"),
                            json.getString("candidate")
                        )
                        "control" -> listener.onControlMessage(json.getJSONObject("payload"))
                        "error" -> listener.onError(json.optString("message", "unknown error"))
                    }
                } catch (e: Exception) {
                    listener.onError("Bad message from server: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "WebSocket failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected()
            }
        })
    }

    fun sendSdp(type: String, sdp: String) {
        val json = JSONObject().apply {
            put("type", type)
            put("sdp", sdp)
            put("room", pairingCode)
        }
        webSocket?.send(json.toString())
    }

    fun sendIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val json = JSONObject().apply {
            put("type", "ice-candidate")
            put("sdpMid", sdpMid ?: "")
            put("sdpMLineIndex", sdpMLineIndex)
            put("candidate", candidate)
            put("room", pairingCode)
        }
        webSocket?.send(json.toString())
    }

    fun sendControlAck(originalType: String, outcome: String, reason: String? = null) {
        val payload = JSONObject().apply {
            put("type", originalType)
            put("outcome", outcome)
            reason?.let { put("reason", it) }
        }
        val json = JSONObject().apply {
            put("type", "control-ack")
            put("payload", payload)
            put("room", pairingCode)
        }
        webSocket?.send(json.toString())
    }

    fun sendAppList(apps: List<WhitelistedApp>) {
        val arr = org.json.JSONArray()
        apps.forEach {
            arr.put(JSONObject().apply {
                put("packageName", it.packageName)
                put("label", it.label)
            })
        }
        val json = JSONObject().apply {
            put("type", "app-list")
            put("apps", arr)
            put("room", pairingCode)
        }
        webSocket?.send(json.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "done")
        client.dispatcher.executorService.shutdown()
    }
}
