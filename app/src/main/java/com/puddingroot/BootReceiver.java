package com.puddingroot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        SharedPreferences prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_root", false)) {
            return;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(RootWorker.class)
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(ctx).enqueue(request);
    }
}
