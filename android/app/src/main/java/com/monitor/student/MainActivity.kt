package com.monitor.student

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.*

class MainActivity : AppCompatActivity() {

    private var socket: Socket? = null
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoTrack: VideoTrack? = null
    private var targetAdminSocketId: String? = null

    private lateinit var tvStatus: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var etStudentName: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etServerUrl = findViewById(R.id.etServerUrl)
        etStudentName = findViewById(R.id.etStudentName)
        val btnConnect: Button = findViewById(R.id.btnConnect)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }

        val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        btnConnect.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            val name = etStudentName.text.toString().trim()
            if (url.isNotEmpty() && name.isNotEmpty()) {
                connectToServer(url, name)
            } else {
                Toast.makeText(this, "URL and Name required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connectToServer(url: String, studentId: String) {
        tvStatus.text = "Connecting..."
        try {
            socket = IO.socket(url)
            socket?.connect()

            socket?.on(Socket.EVENT_CONNECT) {
                runOnUiThread { tvStatus.text = "Status: Online & Ready" }
                val reg = JSONObject().apply {
                    put("role", "student")
                    put("id", studentId)
                }
                socket?.emit("register", reg)
            }

            socket?.on("start-stream") { args ->
                val data = args[0] as JSONObject
                targetAdminSocketId = data.getString("adminSocketId")
                runOnUiThread {
                    tvStatus.text = "Status: Streaming to Admin..."
                    startStreaming()
                }
            }

            socket?.on("answer") { args ->
                val data = args[0] as JSONObject
                val sdpObj = data.getJSONObject("sdp")
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpObj.getString("sdp"))
                peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {}, sdp)
            }

            socket?.on("ice-candidate") { args ->
                val data = args[0] as JSONObject
                val c = data.getJSONObject("candidate")
                val candidate = IceCandidate(c.getString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate"))
                peerConnection?.addIceCandidate(candidate)
            }

            socket?.on("stop-stream") {
                runOnUiThread {
                    stopStream()
                    tvStatus.text = "Status: Online (Idle)"
                }
            }
        } catch (e: Exception) {
            tvStatus.text = "Error: ${e.message}"
        }
    }

    private fun startStreaming() {
        videoCapturer = createCameraCapturer()
        val eglContext = EglBase.create().eglBaseContext
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer?.initialize(surfaceHelper, this, videoSource.capturerObserver)
        videoCapturer?.startCapture(480, 360, 15)

        videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource)

        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, object : PeerConnection.Observer {
            override fun onIceCandidate(cand: IceCandidate) {
                val cJson = JSONObject().apply {
                    put("candidate", cand.sdp)
                    put("sdpMid", cand.sdpMid)
                    put("sdpMLineIndex", cand.sdpMLineIndex)
                }
                val payload = JSONObject().apply {
                    put("targetSocketId", targetAdminSocketId)
                    put("candidate", cJson)
                }
                socket?.emit("ice-candidate", payload)
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })

        peerConnection?.addTrack(videoTrack, listOf("stream1"))

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                val payload = JSONObject().apply {
                    put("targetSocketId", targetAdminSocketId)
                    put("sdp", JSONObject().apply {
                        put("type", "offer")
                        put("sdp", desc.description)
                    })
                }
                socket?.emit("offer", payload)
            }
        }, MediaConstraints())
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) return enumerator.createCapturer(name, null)
        }
        return enumerator.createCapturer(enumerator.deviceNames[0], null)
    }

    private fun stopStream() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
            videoTrack?.dispose()
            videoTrack = null
            peerConnection?.close()
            peerConnection = null
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
        socket?.disconnect()
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
