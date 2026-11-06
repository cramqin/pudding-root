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
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    /** Kernel prefix for the supported build (HyperOS 4.0.1.0.XPCMIXM.D01). */
    static final String EXPECT_KERNEL_PREFIX = "6.12.69-android16-6-g0a1dc0413ec7";

    private TextView statusView;
    private TextView logView;
    private Button rootBtn;
    private Button shizukuBtn;
    private EditText pkgEdit;
    private Spinner managerSpinner;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;

    private static final String CURRENT_SPOOF = "kqvdie.zeykvw.hpbcyd";
    private String[] managerLabels = new String[0];
    private String[] managerPackages = new String[0];

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> ui.post(this::refreshStatus);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        rootBtn = findViewById(R.id.root);
        shizukuBtn = findViewById(R.id.shizuku);
        pkgEdit = findViewById(R.id.pkg);
        managerSpinner = findViewById(R.id.manager_spinner);
        logView.setMovementMethod(new ScrollingMovementMethod());

        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        setupManagerChoices();
        ArrayAdapter<String> managerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, managerLabels);
        managerSpinner.setAdapter(managerAdapter);
        selectManager(prefs.getString("ksu_pkg", detectManagerPackage()));
        managerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                boolean custom = position == managerPackages.length - 1;
                pkgEdit.setEnabled(custom);
                if (!custom) {
                    pkgEdit.setText(managerPackages[position]);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        Shizuku.addRequestPermissionResultListener(permListener);
        rootBtn.setOnClickListener(v -> startRoot());
        shizukuBtn.setOnClickListener(v -> requestShizuku());

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
        boolean shizukuReady = false;
        try {
            if (!Shizuku.pingBinder()) {
                shizuku = "Shizuku: not running (open Shizuku, then return here)";
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizuku = "Shizuku: ready";
                shizukuReady = true;
            } else {
                shizuku = "Shizuku: permission not granted (tap Request Access)";
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
        shizukuBtn.setText(shizukuReady ? "SHIZUKU ACCESS GRANTED" : "REQUEST SHIZUKU ACCESS");
        rootBtn.setEnabled(ok && shizukuReady && !running);
    }

    private boolean packageInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private void setupManagerChoices() {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> packages = new ArrayList<>();
        addManagerIfInstalled(labels, packages, CURRENT_SPOOF, "KernelSU Next (spoofed)");
        addManagerIfInstalled(labels, packages, "me.weishu.kernelsu", "KernelSU (stock)");
        addManagerIfInstalled(labels, packages, "com.rifsxd.ksunext", "KernelSU Next (stock)");
        addManagerIfInstalled(labels, packages, "com.sukisu.ultra", "SukiSU Ultra");
        labels.add("Custom package");
        packages.add("");
        managerLabels = labels.toArray(new String[0]);
        managerPackages = packages.toArray(new String[0]);
    }

    private void addManagerIfInstalled(ArrayList<String> labels, ArrayList<String> packages,
                                       String packageName, String label) {
        if (packageInstalled(packageName)) {
            labels.add(label);
            packages.add(packageName);
        }
    }

    private String detectManagerPackage() {
        for (String packageName : managerPackages) {
            if (!packageName.isEmpty() && packageInstalled(packageName)) {
                return packageName;
            }
        }
        return CURRENT_SPOOF;
    }

    private void selectManager(String packageName) {
        for (int i = 0; i < managerPackages.length - 1; i++) {
            if (managerPackages[i].equals(packageName)) {
                managerSpinner.setSelection(i);
                pkgEdit.setEnabled(false);
                pkgEdit.setText(packageName);
                return;
            }
        }
        managerSpinner.setSelection(managerPackages.length - 1);
        pkgEdit.setEnabled(true);
        pkgEdit.setText(packageName);
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Start Shizuku first, then return here");
                try {
                    android.content.Intent intent = getPackageManager().getLaunchIntentForPackage(
                            "moe.shizuku.privileged.api");
                    if (intent != null) {
                        startActivity(intent);
                    }
                } catch (Throwable ignored) {
                }
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0);
            } else {
                toast("Shizuku access is already granted");
            }
        } catch (Throwable t) {
            toast("Shizuku error: " + t.getClass().getSimpleName());
        }
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
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                toast("Grant Shizuku access first");
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
