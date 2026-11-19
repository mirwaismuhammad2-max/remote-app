cat > /home/claude/RemoteAppProject/app/src/main/java/com/example/remoteapp/SignalingClient.kt << 'EOF'
package com.example.remoteapp

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Signaling goes through Firebase Firestore instead of a custom server.
 * This is what lets Phone A and Phone B find each other over the internet
 * regardless of which network each is on (Wi-Fi, mobile data, different
 * carriers — none of that matters, since Firestore is reachable from
 * anywhere). The actual video/control stream still flows directly between
 * the two phones via WebRTC; Firestore is only used to exchange the
 * handshake messages needed to set that connection up.
 *
 * Document layout, under sessions/{pairingCode}:
 *   - offer: String?   (written by the phone/host)
 *   - answer: String?  (written by the viewer)
 *   subcollections:
 *   - hostCandidates/{auto-id}   { sdpMid, sdpMLineIndex, candidate }
 *   - viewerCandidates/{auto-id} { sdpMid, sdpMLineIndex, candidate }
 *   - presence/viewer            { online: true }  (written by the viewer on connect)
 */
class SignalingClient(
    private val pairingCode: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onPeerJoined()
        fun onRemoteSdp(type: String, sdp: String)
        fun onRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onDisconnected()
        fun onError(message: String)
    }

    private val db = FirebaseFirestore.getInstance()
    private val sessionDoc = db.collection("sessions").document(pairingCode)

    private var answerListener: ListenerRegistration? = null
    private var candidatesListener: ListenerRegistration? = null
    private var presenceListener: ListenerRegistration? = null
    private var peerJoinedNotified = false

    fun connect() {
        // Clear any stale offer/answer from a previous session under this code.
        sessionDoc.set(mapOf("offer" to null, "answer" to null))
            .addOnSuccessListener {
                listener.onConnected()
                listenForViewerPresence()
            }
            .addOnFailureListener { e ->
                listener.onError(e.message ?: "Firestore write failed")
            }
    }

    private fun listenForViewerPresence() {
        presenceListener = sessionDoc.collection("presence").document("viewer")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    listener.onError(error.message ?: "presence listen failed")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists() && !peerJoinedNotified) {
                    peerJoinedNotified = true
                    listener.onPeerJoined()
                    listenForAnswerAndCandidates()
                }
            }
    }

    private fun listenForAnswerAndCandidates() {
        answerListener = sessionDoc.addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            val answer = snapshot?.getString("answer")
            if (answer != null) listener.onRemoteSdp("answer", answer)
        }

        candidatesListener = sessionDoc.collection("viewerCandidates")
            .addSnapshotListener { snapshots, error ->
                if (error != null) return@addSnapshotListener
                snapshots?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val doc = change.document
                        listener.onRemoteIceCandidate(
                            doc.getString("sdpMid") ?: "",
                            (doc.getLong("sdpMLineIndex") ?: 0).toInt(),
                            doc.getString("candidate") ?: ""
                        )
                    }
                }
            }
    }

    fun sendSdp(type: String, sdp: String) {
        sessionDoc.update(type, sdp)
    }

    fun sendIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        sessionDoc.collection("hostCandidates").add(
            mapOf(
                "sdpMid" to (sdpMid ?: ""),
                "sdpMLineIndex" to sdpMLineIndex,
                "candidate" to candidate
            )
        )
    }

    fun disconnect() {
        answerListener?.remove()
        candidatesListener?.remove()
        presenceListener?.remove()
        listener.onDisconnected()
    }
}
EOF
echo done
