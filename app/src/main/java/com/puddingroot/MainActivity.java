package com.puddingroot;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    /** Kernel prefix for the supported build (HyperOS 4.0.1.0.XPCMIXM.D01). */
    static final String EXPECT_KERNEL_PREFIX = "6.12.69-android16-6-g0a1dc0413ec7";

    private TextView statusView;
    private TextView logView;
    private Button rootBtn;
    private EditText pkgEdit;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> ui.post(this::refreshStatus);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        rootBtn = findViewById(R.id.root);
        pkgEdit = findViewById(R.id.pkg);
        logView.setMovementMethod(new ScrollingMovementMethod());

        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        pkgEdit.setText(prefs.getString("ksu_pkg", "me.weishu.kernelsu"));

        Shizuku.addRequestPermissionResultListener(permListener);
        rootBtn.setOnClickListener(v -> startRoot());

        refreshStatus();
        if (!deviceSupported()) {
            rootBtn.setEnabled(false);
            appendLog("Unsupported device/kernel. This build only targets Xiaomi 17 Global "
                    + "(pudding) on kernel " + EXPECT_KERNEL_PREFIX + "…");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        super.onDestroy();
    }

    private String kernelVersion() {
        String v = System.getProperty("os.version", "");
        if (v == null || v.isEmpty()) {
            // Fallback: /proc/version is "Linux version <release> (...)".
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/version"))) {
                String line = r.readLine();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 3) {
                        v = parts[2];
                    }
                }
            } catch (Throwable ignored) {
                // keep whatever os.version gave us
            }
        }
        return (v == null || v.isEmpty()) ? "?" : v;
    }

    private boolean deviceSupported() {
        return kernelVersion().startsWith(EXPECT_KERNEL_PREFIX);
    }

    private void refreshStatus() {
        String dev = android.os.Build.DEVICE;
        String build = android.os.Build.DISPLAY;
        String kernel = kernelVersion();
        boolean ok = deviceSupported();

        String shizuku;
        try {
            if (!Shizuku.pingBinder()) {
                shizuku = "Shizuku: not running (open Shizuku, then return here)";
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizuku = "Shizuku: ready";
            } else {
                shizuku = "Shizuku: permission not granted (tap Root to request)";
            }
        } catch (Throwable t) {
            shizuku = "Shizuku: unavailable (" + t.getClass().getSimpleName() + ")";
        }

        String target = ok ? "SUPPORTED BUILD" : "UNSUPPORTED BUILD";
        statusView.setText(
                target + "\n"
                        + "Device   " + dev + "\n"
                        + "Target   Xiaomi 17 Global / HyperOS 4.0.1.0\n"
                        + "Kernel   " + kernel + "\n"
                        + shizuku);
    }

    private void startRoot() {
        if (running) {
            return;
        }
        if (!deviceSupported()) {
            toast("Unsupported device/kernel");
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                toast("Start Shizuku first");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0);
                toast("Grant Shizuku permission, then tap Root again");
                return;
            }
        } catch (Throwable t) {
            toast("Shizuku error: " + t);
            return;
        }

        String pkg = pkgEdit.getText().toString().trim();
        if (pkg.isEmpty()) {
            pkg = "me.weishu.kernelsu";
        }
        prefs.edit().putString("ksu_pkg", pkg).apply();

        running = true;
        rootBtn.setEnabled(false);
        appendLog("=== starting · KSU package: " + pkg + " ===");

        final String finalPkg = pkg;
        new Thread(new RootRunner(this, finalPkg, new RootRunner.Sink() {
            @Override
            public void onLine(String line) {
                ui.post(() -> appendLog(line));
            }

            @Override
            public void onDone(boolean ok, String summary) {
                ui.post(() -> {
                    appendLog(summary);
                    running = false;
                    rootBtn.setEnabled(true);
                    refreshStatus();
                });
            }
        })).start();
    }

    private void appendLog(String s) {
        logView.append(s + "\n");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
