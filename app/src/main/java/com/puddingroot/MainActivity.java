package com.puddingroot;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
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
import java.lang.reflect.Method;
import java.util.ArrayList;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    /** Kernel prefix for the supported build (HyperOS 4.0.1.0.XPCMIXM.D01). */
    static final String EXPECT_KERNEL_PREFIX = "6.12.69-android16-6-g0a1dc0413ec7";
    /** Build id is set at boot and is NOT spoofable by KernelSU. */
    static final String EXPECT_BUILD = "4.0.1.0";

    private TextView statusView;
    private TextView logView;
    private Button rootBtn;
    private Button shizukuBtn;
    private Button copyLogBtn;
    private Button rebootBtn;
    private Button softBootBtn;
    private EditText pkgEdit;
    private Spinner managerSpinner;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private volatile boolean rooted = false;

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
        copyLogBtn = findViewById(R.id.copy_log);
        rebootBtn = findViewById(R.id.reboot);
        softBootBtn = findViewById(R.id.soft_boot);
        pkgEdit = findViewById(R.id.pkg);
        managerSpinner = findViewById(R.id.manager_spinner);
        logView.setMovementMethod(new ScrollingMovementMethod());

        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        Notifier.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
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
        copyLogBtn.setOnClickListener(v -> copyLog());
        rebootBtn.setOnClickListener(v -> rebootDevice());
        softBootBtn.setOnClickListener(v -> softRebootDevice());

        refreshStatus();
        if (deviceStatus() != 0) {
            appendLog("Note: this build is not verified (target: pudding HyperOS 4, "
                    + "global 4.0.1.0 / CN). Running may fail or crash the kernel — "
                    + "proceed at your own risk.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        ui.postDelayed(this::refreshStatus, 500);
        ui.postDelayed(this::refreshStatus, 1500);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        super.onDestroy();
    }

    /** Boot-time kernel release from the read-only property (KSU can't spoof it). */
    private String readSystemProperty(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method get = cls.getMethod("get", String.class);
            Object result = get.invoke(null, key);
            return result == null ? null : result.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Kernel release from /proc/version ("Linux version <release> (...)" ). */
    private String readProcVersion() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/version"))) {
            String line = r.readLine();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) {
                    return parts[2];
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String[] kernelCandidates() {
        return new String[]{
                readSystemProperty("ro.kernel.version"),
                System.getProperty("os.version", ""),
                readProcVersion(),
        };
    }

    private String kernelVersion() {
        for (String v : kernelCandidates()) {
            if (v != null && !v.isEmpty()) {
                return v.trim();
            }
        }
        return "?";
    }

    /** 0 = verified build, 1 = experimental (pudding/HyperOS 4), 2 = unsupported. */
    private int deviceStatus() {
        if (isVerifiedBuild()) {
            return 0;
        }
        if (isPuddingFamily()) {
            return 1;
        }
        return 2;
    }

    private boolean isPuddingFamily() {
        return isPuddingDevice() && isHyperOs4();
    }

    /** Global 4.0.1.0 (verified on hardware) or China CN (Dream-Rainy verified). */
    private boolean isVerifiedBuild() {
        if (!isPuddingDevice()) {
            return false;
        }
        String id = buildId();
        return id.contains(EXPECT_BUILD) || id.contains("CNXM");
    }

    private boolean isPuddingDevice() {
        return Build.DEVICE != null && Build.DEVICE.toLowerCase().contains("pudding");
    }

    private boolean isHyperOs4() {
        return buildId().startsWith("OS4");
    }

    private String buildId() {
        if (Build.ID != null && !Build.ID.isEmpty()) {
            return Build.ID;
        }
        return Build.DISPLAY != null ? Build.DISPLAY : "";
    }

    private void refreshStatus() {
        String dev = android.os.Build.DEVICE;
        String build = android.os.Build.DISPLAY;
        String kernel = kernelVersion();
        int st = deviceStatus();

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

        String target = st == 0 ? "VERIFIED BUILD (global / CN)"
                : st == 1 ? "EXPERIMENTAL (unverified build)"
                : "UNSUPPORTED (not Xiaomi 17 / HyperOS 4)";
        statusView.setText(
                target + "\n"
                        + "Device   " + dev + "\n"
                        + "Target   Xiaomi 17 / HyperOS 4\n"
                        + "Build    " + build + "\n"
                        + "Kernel   " + kernel + "\n"
                        + shizuku);
        shizukuBtn.setText(shizukuReady ? "SHIZUKU ACCESS GRANTED" : "REQUEST SHIZUKU ACCESS");
        rootBtn.setEnabled(shizukuReady && !running);
        rebootBtn.setEnabled(shizukuReady && !running);
        softBootBtn.setEnabled(rooted && !running);
        copyLogBtn.setEnabled(logView.length() > 0);
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
        addManagerIfInstalled(labels, packages, "com.resukisu.resukisu", "ReSukiSU");
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
                ui.postDelayed(this::refreshStatus, 500);
                ui.postDelayed(this::refreshStatus, 1500);
            } else {
                toast("Shizuku access is already granted");
                refreshStatus();
            }
        } catch (Throwable t) {
            toast("Shizuku error: " + t.getClass().getSimpleName());
        }
    }

    private void startRoot() {
        if (running) {
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
                Notifier.notify(MainActivity.this,
                        ok ? "PuddingRoot: rooted" : "PuddingRoot: failed",
                        ok ? summary : summary + " Reboot and retry.",
                        Notifier.ID_AUTO);
                ui.post(() -> {
                    appendLog(summary);
                    if (ok) {
                        rooted = true;
                    }
                    running = false;
                    rootBtn.setEnabled(true);
                    refreshStatus();
                });
            }
        })).start();
    }

    private void appendLog(String s) {
        logView.append(s + "\n");
        copyLogBtn.setEnabled(logView.length() > 0);
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("PuddingRoot log", logView.getText()));
        toast("Log copied");
    }

    private void rebootDevice() {
        issuePowerCommand("svc power reboot 2>&1 || setprop sys.powerctl reboot 2>&1",
                "Reboot", "Rebooting…", "reboot");
    }

    private void softRebootDevice() {
        if (!rooted) {
            toast("Root first, then soft reboot");
            return;
        }
        try {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                toast("Grant Shizuku access first");
                return;
            }
        } catch (Throwable t) {
            toast("Shizuku error: " + t.getClass().getSimpleName());
            return;
        }
        toast("Soft rebooting…");
        appendLog("--- soft reboot requested ---");
        new Thread(() -> {
            String out = RootRunner.runShizukuCommand("su -c 'stop; sleep 2; start' 2>&1");
            String o = out == null ? "" : out.trim();
            ui.post(() -> {
                if (!o.isEmpty()) {
                    appendLog(o);
                }
            });
        }).start();
    }

    private void issuePowerCommand(String command, String action, String toastMsg, String logLabel) {
        try {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                toast("Grant Shizuku access first");
                return;
            }
        } catch (Throwable t) {
            toast("Shizuku error: " + t.getClass().getSimpleName());
            return;
        }
        toast(toastMsg);
        appendLog("--- " + logLabel + " requested ---");
        new Thread(() -> {
            String out = RootRunner.runShizukuCommand(command);
            String o = out == null ? "" : out.trim();
            ui.post(() -> {
                if (!o.isEmpty()) {
                    appendLog(o);
                }
                if (o.contains("SecurityException") || o.contains("denied")
                        || o.contains("Permission") || o.contains("not permitted")) {
                    appendLog(action + " blocked by this ROM (no shell permission). Do it manually.");
                    toast(action + " blocked — do it manually");
                }
            });
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
