package com.puddingroot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
    private Button shutdownBtn;
    private Button softBootBtn;
    private EditText pkgEdit;
    private Spinner managerSpinner;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private volatile boolean rooted = false;
    private volatile boolean rootChecked = false;
    private volatile boolean rootChecking = false;

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
        shutdownBtn = findViewById(R.id.shutdown);
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
        shutdownBtn.setOnClickListener(v -> shutdownDevice());
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
        checkRootedAsync();
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
        for (String s : buildSources()) {
            if (s != null && (s.contains(EXPECT_BUILD) || s.contains("CNXM"))) {
                return true;
            }
        }
        return false;
    }

    private boolean isPuddingDevice() {
        return Build.DEVICE != null && Build.DEVICE.toLowerCase().contains("pudding");
    }

    private boolean isHyperOs4() {
        for (String s : buildSources()) {
            if (s != null && s.startsWith("OS4")) {
                return true;
            }
        }
        return false;
    }

    private String[] buildSources() {
        return new String[]{
                Build.DISPLAY,
                Build.ID,
                Build.FINGERPRINT,
                Build.VERSION.INCREMENTAL,
        };
    }

    /** The Xiaomi/HyperOS build string for display (prefer one containing "OS"). */
    private String xiaomiBuild() {
        for (String s : buildSources()) {
            if (s != null && (s.contains("OS4") || s.contains("XM"))) {
                return s;
            }
        }
        return Build.DISPLAY != null ? Build.DISPLAY : "?";
    }

    /** Fast app-side check; may be hidden by KernelSU's hide features. */
    private boolean isKsuLoaded() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/modules"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("kernelsu") || line.contains("sukisu")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Reliable root detection through Shizuku: KSU hide features filter
     * /proc/modules for apps, but a shell-issued {@code su -c id} still tells
     * the truth. Runs off the UI thread.
     */
    private boolean detectRooted() {
        if (isKsuLoaded()) {
            return true;
        }
        try {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            String out = RootRunner.runShizukuCommand("/system/bin/su -c id 2>&1");
            if (out != null && out.contains("uid=0")) {
                return true;
            }
            out = RootRunner.runShizukuCommand("su -c id 2>&1");
            if (out != null && out.contains("uid=0")) {
                return true;
            }
            out = RootRunner.runShizukuCommand("/data/adb/ksu/bin/su -c id 2>&1");
            if (out != null && out.contains("uid=0")) {
                return true;
            }
            String mods = RootRunner.runShizukuCommand(
                    "grep -i -E 'kernelsu|sukisu' /proc/modules 2>&1");
            return mods != null && (mods.contains("kernelsu") || mods.contains("sukisu"));
        } catch (Throwable t) {
            return false;
        }
    }

    private void checkRootedAsync() {
        if (rootChecking || running) {
            return;
        }
        rootChecking = true;
        new Thread(() -> {
            boolean r = detectRooted();
            ui.post(() -> {
                rooted = r;
                rootChecked = true;
                rootChecking = false;
                refreshStatus();
            });
        }).start();
    }

    private void refreshStatus() {
        String dev = android.os.Build.DEVICE;
        String build = xiaomiBuild();
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
        boolean ksuLoaded = isKsuLoaded();
        if (ksuLoaded) {
            rooted = true;
            rootChecked = true;
        }
        String rootState;
        if (!rootChecked) {
            rootState = "root: checking…";
        } else {
            rootState = rooted ? "ROOTED (KernelSU loaded)" : "not rooted";
        }
        statusView.setText(
                target + "\n"
                        + "Device   " + dev + "\n"
                        + "Target   Xiaomi 17 / HyperOS 4\n"
                        + "Build    " + build + "\n"
                        + "Kernel   " + kernel + "\n"
                        + rootState + "\n"
                        + shizuku);
        shizukuBtn.setText(shizukuReady ? "SHIZUKU ACCESS GRANTED" : "REQUEST SHIZUKU ACCESS");
        rootBtn.setText(running ? "RUNNING… (reboot if stuck)" : "ROOT THIS BOOT");
        rootBtn.setEnabled(shizukuReady && !running);
        rebootBtn.setEnabled(shizukuReady);
        shutdownBtn.setEnabled(shizukuReady);
        softBootBtn.setVisibility(rooted ? android.view.View.VISIBLE : android.view.View.GONE);
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
        java.util.Set<String> installed = installedPackages();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> packages = new ArrayList<>();
        addManagerIfInstalled(labels, packages, installed, CURRENT_SPOOF, "KernelSU Next (spoofed)");
        addManagerIfInstalled(labels, packages, installed, "me.weishu.kernelsu", "KernelSU (stock)");
        addManagerIfInstalled(labels, packages, installed, "com.rifsxd.ksunext", "KernelSU Next (stock)");
        addManagerIfInstalled(labels, packages, installed, "com.sukisu.ultra", "SukiSU Ultra");
        addManagerIfInstalled(labels, packages, installed, "com.resukisu.resukisu", "ReSukiSU");
        // Any other installed package whose name looks like a KSU manager.
        for (String p : installed) {
            String low = p.toLowerCase();
            if ((low.contains("kernelsu") || low.contains("sukisu") || low.contains("ksunext"))
                    && !packages.contains(p)) {
                labels.add(p);
                packages.add(p);
            }
        }
        appendLog("managers detected: " + packages);
        labels.add("Custom package");
        packages.add("");
        managerLabels = labels.toArray(new String[0]);
        managerPackages = packages.toArray(new String[0]);
    }

    private java.util.Set<String> installedPackages() {
        java.util.Set<String> set = new java.util.HashSet<>();
        try {
            for (android.content.pm.PackageInfo pi : getPackageManager().getInstalledPackages(0)) {
                set.add(pi.packageName);
            }
        } catch (Throwable ignored) {
        }
        return set;
    }

    private void addManagerIfInstalled(ArrayList<String> labels, ArrayList<String> packages,
                                       java.util.Set<String> installed,
                                       String packageName, String label) {
        if (installed.contains(packageName)) {
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
        if (rooted) {
            new AlertDialog.Builder(this)
                    .setTitle("Already rooted")
                    .setMessage("KernelSU is already loaded. Re-running the exploit is "
                            + "usually unnecessary and can crash the kernel. Continue anyway?")
                    .setPositiveButton("Continue", (d, w) -> runRoot())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        runRoot();
    }

    private void runRoot() {
        if (running) {
            return;
        }
        String pkg = pkgEdit.getText().toString().trim();
        if (pkg.isEmpty()) {
            toast("Select or enter a KernelSU manager package first");
            return;
        }
        if (!packageInstalled(pkg)) {
            new AlertDialog.Builder(this)
                    .setTitle("Manager not installed")
                    .setMessage("'" + pkg + "' is not installed. Install a KernelSU-family "
                            + "manager (KernelSU / KernelSU-Next / SukiSU / ReSukiSU) first, "
                            + "or pick the right one above.")
                    .setPositiveButton("OK", null)
                    .show();
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

        prefs.edit().putString("ksu_pkg", pkg).apply();

        running = true;
        rootBtn.setEnabled(false);
        rootBtn.setText("RUNNING… (reboot if stuck)");
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
                        rootChecked = true;
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

    private void shutdownDevice() {
        // HyperOS maps `svc power shutdown` to a quick shutdown that reboots
        // right after turning off. Use the raw init power-off instead (like the
        // power button); it needs root, so try setprop first, then reboot -p.
        issuePowerCommand(
                "/system/bin/su -c 'setprop sys.powerctl shutdown' 2>&1 "
                        + "|| /system/bin/su -c 'reboot -p' 2>&1",
                "Shutdown", "Shutting down…", "shutdown");
    }

    private void softRebootDevice() {
        if (!rooted) {
            toast("Root first, then soft reboot");
            return;
        }
        // This ROM doesn't support userspace reboot, so hand off to the
        // KernelSU manager, whose soft-reboot option is known to work.
        String pkg = pkgEdit.getText().toString().trim();
        if (pkg.isEmpty()) {
            pkg = "me.weishu.kernelsu";
        }
        try {
            android.content.Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                appendLog("opened manager (" + pkg + ") — use its soft reboot");
            } else {
                toast("Manager not found: " + pkg);
            }
        } catch (Throwable t) {
            toast("Can't open manager: " + t.getClass().getSimpleName());
        }
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
                        || o.contains("Permission") || o.contains("not permitted")
                        || o.contains("not found") || o.contains("No such file")) {
                    appendLog(action + " didn't run (needs root or is blocked). "
                            + "Do it manually or root first.");
                    toast(action + " needs root / blocked — do it manually");
                }
            });
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
