package com.monitor.student;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private EditText etServerUrl;
    private EditText etStudentName;
    private TextView tvStatus;
    private Button btnStartService;
    private Button btnStopService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerUrl = findViewById(R.id.etServerUrl);
        etStudentName = findViewById(R.id.etStudentName);
        tvStatus = findViewById(R.id.tvStatus);
        btnStartService = findViewById(R.id.btnStartService);
        btnStopService = findViewById(R.id.btnStopService);

        requestAllPermissions();

        btnStartService.setOnClickListener(v -> {
            String url = etServerUrl.getText().toString().trim();
            String name = etStudentName.getText().toString().trim();

            if (url.isEmpty() || name.isEmpty()) {
                Toast.makeText(this, "URL and Name are required", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!hasOverlayPermission()) {
                Toast.makeText(this, "Please allow 'Display over other apps'", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }

            Intent serviceIntent = new Intent(this, MonitorService.class);
            serviceIntent.putExtra("url", url);
            serviceIntent.putExtra("name", name);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            tvStatus.setText("Status: Background Service Running");
            btnStartService.setEnabled(false);
            btnStopService.setVisibility(Button.VISIBLE);
            Toast.makeText(this, "Monitoring active! You can now minimize and use any app.", Toast.LENGTH_LONG).show();
        });

        btnStopService.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, MonitorService.class);
            stopService(serviceIntent);
            tvStatus.setText("Status: Monitoring Stopped");
            btnStartService.setEnabled(true);
            btnStopService.setVisibility(Button.GONE);
        });
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestAllPermissions() {
        // Camera Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
        }

        // Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }

        // Battery Optimization Ignore (Background survival)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }
}
