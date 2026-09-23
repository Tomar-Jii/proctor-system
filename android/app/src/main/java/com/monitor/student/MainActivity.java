package com.monitor.student;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Base64;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private Socket socket;
    private Camera camera;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private boolean isSurfaceReady = false;
    private boolean isStreaming = false;
    private long lastFrameTime = 0;

    private TextView tvStatus;
    private EditText etServerUrl;
    private EditText etStudentName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        etServerUrl = findViewById(R.id.etServerUrl);
        etStudentName = findViewById(R.id.etStudentName);
        Button btnConnect = findViewById(R.id.btnConnect);
        surfaceView = findViewById(R.id.surfaceView);

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
        }

        btnConnect.setOnClickListener(v -> {
            String url = etServerUrl.getText().toString().trim();
            String name = etStudentName.getText().toString().trim();
            if (!url.isEmpty() && !name.isEmpty()) {
                connectToServer(url, name);
            } else {
                Toast.makeText(this, "URL and Name are required", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void connectToServer(String url, String studentId) {
        tvStatus.setText("Status: Connecting...");
        try {
            if (socket != null) {
                socket.disconnect();
            }
            socket = IO.socket(url);
            socket.connect();

            socket.on(Socket.EVENT_CONNECT, args -> runOnUiThread(() -> {
                tvStatus.setText("Status: Online & Ready");
                try {
                    JSONObject reg = new JSONObject();
                    reg.put("role", "student");
                    reg.put("id", studentId);
                    socket.emit("register", reg);
                } catch (JSONException ignored) {}
            }));

            socket.on("start-stream", args -> runOnUiThread(() -> {
                tvStatus.setText("Status: Monitoring Active");
                startCameraStream();
            }));

            socket.on("stop-stream", args -> runOnUiThread(() -> {
                tvStatus.setText("Status: Online (Idle)");
                stopCameraStream();
            }));

            socket.on(Socket.EVENT_DISCONNECT, args -> runOnUiThread(() -> {
                tvStatus.setText("Status: Disconnected");
                stopCameraStream();
            }));

        } catch (Exception e) {
            tvStatus.setText("Error: " + e.getMessage());
        }
    }

    private void startCameraStream() {
        if (isStreaming) return;
        try {
            int frontCamId = findFrontFacingCamera();
            camera = Camera.open(frontCamId != -1 ? frontCamId : 0);
            camera.setDisplayOrientation(90);

            Camera.Parameters params = camera.getParameters();
            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            Camera.Size chosenSize = sizes.get(0);
            for (Camera.Size s : sizes) {
                if (s.width <= 640 && s.height <= 480) {
                    chosenSize = s;
                    break;
                }
            }
            params.setPreviewSize(chosenSize.width, chosenSize.height);
            camera.setParameters(params);

            if (isSurfaceReady && surfaceHolder != null) {
                camera.setPreviewDisplay(surfaceHolder);
            }

            final int pWidth = chosenSize.width;
            final int pHeight = chosenSize.height;

            camera.setPreviewCallback((data, cam) -> {
                long now = System.currentTimeMillis();
                if (isStreaming && now - lastFrameTime > 120 && socket != null && socket.connected()) {
                    lastFrameTime = now;
                    try {
                        YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, pWidth, pHeight, null);
                        ByteArrayOutputStream os = new ByteArrayOutputStream();
                        yuvImage.compressToJpeg(new Rect(0, 0, pWidth, pHeight), 45, os);
                        byte[] jpegBytes = os.toByteArray();
                        String base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
                        socket.emit("stream-frame", base64);
                    } catch (Exception ignored) {}
                }
            });

            camera.startPreview();
            isStreaming = true;
        } catch (Exception e) {
            tvStatus.setText("Camera Error: " + e.getMessage());
        }
    }

    private void stopCameraStream() {
        isStreaming = false;
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {}
            camera = null;
        }
    }

    private int findFrontFacingCamera() {
        int count = Camera.getNumberOfCameras();
        for (int i = 0; i < count; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        isSurfaceReady = true;
        surfaceHolder = holder;
        if (isStreaming && camera != null) {
            try {
                camera.setPreviewDisplay(holder);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        isSurfaceReady = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCameraStream();
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
    }
}
