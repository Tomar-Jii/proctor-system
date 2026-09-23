package com.monitor.student;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Size;
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

import java.nio.ByteBuffer;
import java.util.Collections;

import io.socket.client.IO;
import io.socket.client.Socket;

public class MainActivity extends AppCompatActivity {

    private Socket socket;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

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

        checkCameraPermission();

        btnConnect.setOnClickListener(v -> {
            if (!checkCameraPermission()) {
                Toast.makeText(this, "Please allow Camera Permission!", Toast.LENGTH_LONG).show();
                return;
            }
            String url = etServerUrl.getText().toString().trim();
            String name = etStudentName.getText().toString().trim();
            if (!url.isEmpty() && !name.isEmpty()) {
                connectToServer(url, name);
            } else {
                Toast.makeText(this, "URL and Name are required", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
            return false;
        }
        return true;
    }

    private void startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException ignored) {}
        }
    }

    private void connectToServer(String url, String studentId) {
        tvStatus.setText("Status: Connecting...");
        try {
            if (socket != null) socket.disconnect();
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
                startCamera2Stream();
            }));

            socket.on("stop-stream", args -> runOnUiThread(() -> {
                tvStatus.setText("Status: Online (Idle)");
                stopCamera2Stream();
            }));

            socket.on(Socket.EVENT_DISCONNECT, args -> runOnUiThread(() -> {
                tvStatus.setText("Status: Disconnected");
                stopCamera2Stream();
            }));

        } catch (Exception e) {
            tvStatus.setText("Error: " + e.getMessage());
        }
    }

    private void startCamera2Stream() {
        if (isStreaming) return;
        startBackgroundThread();

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null && manager.getCameraIdList().length > 0) {
                cameraId = manager.getCameraIdList()[0];
            }

            if (cameraId == null) {
                tvStatus.setText("No camera found");
                return;
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size chosenSize = new Size(480, 360);
            if (map != null) {
                Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                if (jpegSizes != null && jpegSizes.length > 0) {
                    chosenSize = jpegSizes[jpegSizes.length - 1];
                    for (Size s : jpegSizes) {
                        if (s.getWidth() <= 640 && s.getHeight() <= 480 && s.getWidth() >= 320) {
                            chosenSize = s;
                            break;
                        }
                    }
                }
            }

            imageReader = ImageReader.newInstance(chosenSize.getWidth(), chosenSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null && isStreaming) {
                        long now = System.currentTimeMillis();
                        if (now - lastFrameTime > 120) {
                            lastFrameTime = now;
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                            if (socket != null && socket.connected()) {
                                socket.emit("stream-frame", base64);
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (image != null) image.close();
                }
            }, backgroundHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(imageReader.getSurface());
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                        cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                captureSession = session;
                                try {
                                    session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                    isStreaming = true;
                                } catch (Exception e) {
                                    runOnUiThread(() -> tvStatus.setText("Stream Error: " + e.getMessage()));
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                runOnUiThread(() -> tvStatus.setText("Session configuration failed"));
                            }
                        }, backgroundHandler);
                    } catch (Exception e) {
                        runOnUiThread(() -> tvStatus.setText("Capture Request Error: " + e.getMessage()));
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    isStreaming = false;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    isStreaming = false;
                    runOnUiThread(() -> tvStatus.setText("Camera2 Error: Code " + error));
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            tvStatus.setText("Camera Access Error: " + e.getMessage());
        }
    }

    private void stopCamera2Stream() {
        isStreaming = false;
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception ignored) {}
        stopBackgroundThread();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera2Stream();
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
    }
}
