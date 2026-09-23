package com.monitor.student

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var socket: Socket? = null
    private var isStreaming = false
    private var lastFrameTime = 0L
    private val cameraExecutor = Executors.newSingleThreadExecutor()

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

            socket?.on("start-stream") {
                isStreaming = true
                runOnUiThread {
                    tvStatus.text = "Status: Monitoring Active"
                    startCamera()
                }
            }

            socket?.on("stop-stream") {
                isStreaming = false
                runOnUiThread {
                    stopCamera()
                    tvStatus.text = "Status: Online (Idle)"
                }
            }
        } catch (e: Exception) {
            runOnUiThread { tvStatus.text = "Error: ${e.message}" }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val now = System.currentTimeMillis()
                // Send ~8 frames per second to save data (120ms gap)
                if (isStreaming && now - lastFrameTime > 120) {
                    lastFrameTime = now
                    try {
                        val rawBitmap = imageProxy.toBitmap()
                        val matrix = Matrix().apply {
                            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                        }
                        val bitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 45, out)
                        val bytes = out.toByteArray()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        socket?.emit("stream-frame", base64)
                    } catch (_: Exception) {}
                }
                imageProxy.close()
            }

            val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
        stopCamera()
        cameraExecutor.shutdown()
        socket?.disconnect()
    }
}
