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
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null
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

        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()

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
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
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
        val factory = peerConnectionFactory ?: return
        val eglContext = eglBase?.eglBaseContext ?: return

        videoCapturer = createCameraCapturer() ?: return
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
        val videoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer?.initialize(surfaceHelper, this, videoSource.capturerObserver)
        videoCapturer?.startCapture(480, 360, 15)

        videoTrack = factory.createVideoTrack("100", videoSource)

        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(cand: IceCandidate?) {
                cand?.let {
                    val cJson = JSONObject().apply {
                        put("candidate", it.sdp)
                        put("sdpMid", it.sdpMid)
                        put("sdpMLineIndex", it.sdpMLineIndex)
                    }
                    val payload = JSONObject().apply {
                        put("targetSocketId", targetAdminSocketId)
                        put("candidate", cJson)
                    }
                    socket?.emit("ice-candidate", payload)
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
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
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                    val payload = JSONObject().apply {
                        put("targetSocketId", targetAdminSocketId)
                        put("sdp", JSONObject().apply {
                            put("type", "offer")
                            put("sdp", it.description)
                        })
                    }
                    socket?.emit("offer", payload)
                }
            }
        }, MediaConstraints())
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) return enumerator.createCapturer(name, null)
        }
        return if (enumerator.deviceNames.isNotEmpty()) {
            enumerator.createCapturer(enumerator.deviceNames[0], null)
        } else null
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
