package com.puddingroot;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import rikka.shizuku.Shizuku;

/**
 * Runs the exploit automatically after boot (if enabled). Waits for Shizuku to
 * come up, then runs the payload and reports the result via a notification.
 */
public class RootWorker extends Worker {

    public RootWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_root", false)) {
            return Result.success();
        }
        if (!waitForShizuku(300_000L)) {
            Notifier.notify(ctx, "PuddingRoot",
                    "Auto-root skipped: Shizuku not ready. Enable Autostart for PuddingRoot "
                            + "and Shizuku in HyperOS settings, then open the app.",
                    Notifier.ID_AUTO);
            return Result.failure();
        }
        String pkg = prefs.getString("ksu_pkg", "me.weishu.kernelsu");
        final boolean[] ok = {false};
        RootRunner runner = new RootRunner(ctx, pkg, new RootRunner.Sink() {
            @Override
            public void onLine(String line) {
            }

            @Override
            public void onDone(boolean success, String summary) {
                ok[0] = success;
                Notifier.notify(ctx,
                        success ? "PuddingRoot: rooted" : "PuddingRoot: failed",
                        success ? summary : summary + " Reboot and let it try again.",
                        Notifier.ID_AUTO);
            }
        });
        runner.run();
        return ok[0] ? Result.success() : Result.failure();
    }

    private boolean waitForShizuku(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                if (Shizuku.pingBinder()
                        && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }
}
