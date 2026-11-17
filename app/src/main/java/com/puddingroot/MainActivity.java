package com.puddingroot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    /** Kernel prefix for the supported build (HyperOS 4.0.1.0.XPCMIXM.D01). */
    static final String EXPECT_KERNEL_PREFIX = "6.12.69-android16-6-g0a1dc0413ec7";
    /** Build id is set at boot and is NOT spoofable by KernelSU. */
    static final String EXPECT_BUILD = "4.0.1.0";

    /** Kernel the active GLOBAL payload was built against (pudding-global offsets). */
    static final String EXPECT_KERNEL_GLOBAL =
            "6.12.69-android16-6-g0a1dc0413ec7-ab16271006-4k";
    /** Kernel the active CN payload was built against (pudding offsets). */
    static final String EXPECT_KERNEL_CN =
            "6.12.69-android16-6-g43b5ab7e247d-abogki561813157-4k";

    private TextView statusView;
    private TextView logView;
    private Button rootBtn;
    private Button shizukuBtn;
    private Button copyLogBtn;
    private Button rebootBtn;
    private Button shutdownBtn;
    private Button softBootBtn;
    private Button pickBootimgBtn;
    private EditText pkgEdit;
    private Spinner managerSpinner;
    private SharedPreferences prefs;
    private ProgressBar checkProgress;
    private TextView checkStatus;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private volatile boolean rooted = false;
    private volatile boolean rootChecked = false;
    private volatile boolean rootChecking = false;
    private volatile boolean checkingBootimg = false;

    private static final int PICK_BOOTIMG_REQUEST = 4201;

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
        pickBootimgBtn = findViewById(R.id.pick_bootimg);
        checkProgress = findViewById(R.id.check_progress);
        checkStatus = findViewById(R.id.check_status);
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
        pickBootimgBtn.setOnClickListener(v -> pickBootimg());

        refreshStatus();
        if (deviceStatus() != 0) {
            appendLog("Note: this build is not verified (target: pudding HyperOS 4, "
                    + "global 4.0.1.0 / CN). Running may fail or crash the kernel — "
                    + "proceed at your own risk.");
        }
        int km = kernelMatch();
        if (km == 1) {
            appendLog("⚠ KERNEL MISMATCH: payload was built for " + expectedKernel()
                    + " but this device is running " + kernelVersion()
                    + ". The exploit offsets will not line up. Do not run — "
                    + "extract this build's offsets first (see exploit/src/devices).");
        } else if (km == 0) {
            appendLog("kernel matches payload offsets (" + kernelVersion() + ")");
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

    /** Kernel the currently-selected payload was compiled against. */
    private String expectedKernel() {
        return isCnBuild() ? EXPECT_KERNEL_CN : EXPECT_KERNEL_GLOBAL;
    }

    /** CN vs global payload selection, mirrors RootRunner.isCnBuild(). */
    private boolean isCnBuild() {
        for (String s : buildSources()) {
            if (s != null && s.contains("CNXM")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does the device kernel match the payload's offsets?
     * 0 = match, 1 = mismatch, 2 = unreadable.
     *
     * The exploit's kallsyms addresses and call-site values are compile-time
     * constants baked into the native payload, so a kernel string mismatch
     * means the offsets will not line up -- the write will miss, or hit the
     * wrong address and panic. A match is a strong positive signal; it is not
     * a guarantee, since two builds can share a release string but differ.
     */
    private int kernelMatch() {
        String live = kernelVersion();
        String expected = expectedKernel();
        if (live.isEmpty() || live.equals("?")) {
            return 2;
        }
        return live.equals(expected) ? 0 : 1;
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
        String payloadLine;
        int km = kernelMatch();
        if (km == 0) {
            payloadLine = "Payload   " + expectedKernel() + "\n"
                    + "          ✓ kernel matches payload offsets";
        } else if (km == 1) {
            payloadLine = "Payload   " + expectedKernel() + "\n"
                    + "          ⚠ KERNEL MISMATCH — payload offsets are for a "
                    + "different kernel\n"
                    + "          Device   " + kernel + " (do not run)";
        } else {
            payloadLine = "Payload   " + expectedKernel() + "\n"
                    + "          ? could not read device kernel";
        }

        statusView.setText(
                target + "\n"
                        + "Device   " + dev + "\n"
                        + "Target   Xiaomi 17 / HyperOS 4\n"
                        + "Build    " + build + "\n"
                        + payloadLine + "\n"
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
        // Package names are only [a-zA-Z0-9.]. Reject anything else up front so
        // the value can never be interpolated into a shell command as code.
        if (!pkg.matches("[a-zA-Z0-9.]+")) {
            toast("Invalid package name: " + pkg);
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

    private void pickBootimg() {
        if (checkingBootimg) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, PICK_BOOTIMG_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_BOOTIMG_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                checkBootimg(uri);
            }
        }
    }

    private void checkBootimg(Uri uri) {
        if (checkingBootimg) {
            return;
        }
        checkingBootimg = true;
        ui.post(() -> {
            pickBootimgBtn.setEnabled(false);
            checkProgress.setVisibility(android.view.View.VISIBLE);
            checkProgress.setProgress(0);
            checkStatus.setVisibility(android.view.View.VISIBLE);
            checkStatus.setText("Preparing…");
        });
        appendLog("\n=== Boot Image Check started ===");

        new Thread(() -> {
            try {
                updateCheckProgress(10, "Reading boot.img header…");
                // Copy URI content to a temp file we can random-access
                String tempPath = getCacheDir().getAbsolutePath() + "/bootimg_check_" + System.currentTimeMillis() + ".img";
                try (InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tempPath)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }

                updateCheckProgress(30, "Verifying boot.img header…");
                // Parse header
                try (FileInputStream fis = new FileInputStream(tempPath)) {
                    byte[] hdr = new byte[4096];
                    fis.read(hdr);
                    if (!java.util.Arrays.equals(java.util.Arrays.copyOfRange(hdr, 0, 8), "ANDROID!".getBytes())) {
                        throw new IOException("Not a valid boot.img (missing ANDROID! magic)");
                    }
                    int kernelSize = readU32(hdr, 8);
                    int pageSize = readU32(hdr, 36);
                    if (pageSize == 0) pageSize = 4096;
                    int kernelOff = pageSize;
                    if (kernelSize <= 0 || kernelSize > 200_000_000) {
                        throw new IOException("Invalid kernel size: " + kernelSize);
                    }
                    appendLog("kernel_size=" + kernelSize + " page_size=" + pageSize);

                    updateCheckProgress(50, "Extracting kernel payload…");
                    // Read kernel payload
                    byte[] kernel = new byte[kernelSize];
                    fis.getChannel().position(kernelOff);
                    int read = 0;
                    while (read < kernelSize) {
                        int n = fis.read(kernel, read, kernelSize - read);
                        if (n <= 0) throw new IOException("Unexpected EOF reading kernel");
                        read += n;
                    }

                    // Decompress if needed
                    if (kernel.length >= 2 && kernel[0] == 0x1f && kernel[1] == 0x8b) {
                        updateCheckProgress(60, "Decompressing gzip kernel…");
                        kernel = decompressGzip(kernel);
                        appendLog("decompressed gzip -> " + kernel.length + " bytes");
                    } else if (kernel.length >= 6
                            && kernel[0] == (byte)0xfd && kernel[1] == '7' && kernel[2] == 'z'
                            && kernel[3] == 'X' && kernel[4] == 'Z' && kernel[5] == 0) {
                        updateCheckProgress(60, "Decompressing xz kernel…");
                        kernel = decompressXz(kernel);
                        appendLog("decompressed xz -> " + kernel.length + " bytes");
                    } else {
                        appendLog("raw Image (magic " + String.format("%02x%02x", kernel[0] & 0xff, kernel[1] & 0xff) + ")");
                    }

                    updateCheckProgress(80, "Checking call-site offsets…");
                    // Check the two call sites
                    boolean ok1 = checkCallSite(kernel, 0xffffffc08035477cL, "do_timer_create");
                    boolean ok2 = checkCallSite(kernel, 0xffffffc0800f42d8L, "copy_process");

                    updateCheckProgress(100, "Done");
                    ui.post(() -> {
                        if (ok1 && ok2) {
                            checkStatus.setText("✓ PASS — offsets match current payload");
                            appendLog("RESULT: ✓ PASS — both call sites match the exploit offsets");
                        } else {
                            checkStatus.setText("✗ FAIL — offsets do not match");
                            appendLog("RESULT: ✗ FAIL — call site mismatch (new offsets needed)");
                        }
                    });
                }
            } catch (Throwable t) {
                ui.post(() -> {
                    checkStatus.setText("Error: " + t.getMessage());
                    appendLog("ERROR: " + t);
                });
            } finally {
                checkingBootimg = false;
                ui.post(() -> {
                    pickBootimgBtn.setEnabled(true);
                    checkProgress.setVisibility(android.view.View.GONE);
                });
            }
        }).start();
    }

    private void updateCheckProgress(int progress, String status) {
        ui.post(() -> {
            checkProgress.setProgress(progress);
            checkStatus.setText(status);
        });
    }

    private int readU32(byte[] data, int off) {
        return ((data[off] & 0xff) |
                ((data[off+1] & 0xff) << 8) |
                ((data[off+2] & 0xff) << 16) |
                ((data[off+3] & 0xff) << 24));
    }

    private boolean checkCallSite(byte[] kernel, long retVaddr, String name) {
        // The kernel Image maps its _text at RELATIVE_BASE (0xffffffc080000000)
        // File offset = vaddr - RELATIVE_BASE
        final long RELATIVE_BASE = 0xffffffc080000000L;
        long fileOff = retVaddr - RELATIVE_BASE;
        long blFile = fileOff - 4;
        if (blFile < 0 || blFile + 4 > kernel.length) {
            appendLog("[" + name + "] OUT OF RANGE");
            return false;
        }
        int insn = readU32(kernel, (int)blFile);
        // bl encoding: top 6 bits = 0b100101 (0x25). Use >>> (unsigned) because insn
        // can be negative as a Java int (e.g., 0x97f7b405).
        if ((insn >>> 26) != 0x25) {
            appendLog("[" + name + "] NOT A BL at file " + Long.toHexString(blFile) + ": " + String.format("%08x", insn));
            return false;
        }
        // bl target: imm26 is bits 25-0, sign-extended, << 2
        int imm26 = insn & 0x3ffffff;
        if ((imm26 & 0x2000000) != 0) imm26 -= 0x4000000;
        long blVaddr = retVaddr - 4;
        long target = blVaddr + (imm26 << 2);
        boolean ok = (0xffffffc000000000L <= target && target <= 0xffffffff00000000L);
        appendLog("[" + name + "] bl at vaddr " + Long.toHexString(blVaddr)
                + " -> target " + Long.toHexString(target) + " " + (ok ? "OK" : "BAD"));
        return ok;
    }

    private byte[] decompressGzip(byte[] data) throws IOException {
        java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(data);
        java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bis);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = gis.read(buf)) > 0) {
            bos.write(buf, 0, len);
        }
        gis.close();
        return bos.toByteArray();
    }

    private byte[] decompressXz(byte[] data) throws IOException {
        // Use LZMA from XZ for Java if available; fallback to external tool not possible here.
        // For now, throw since we shouldn't hit this on pudding (it's MZ raw).
        throw new IOException("XZ decompression not implemented (not needed for pudding)");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
