package com.obby.android.localscreenshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class BootCompletedReceiver extends BroadcastReceiver {
    private final String mTag = "BootCompletedReceiver@" + hashCode();

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(mTag, String.format("onReceive: action is %s", intent.getAction()));
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(mTag, "onReceive: start persistent service");
            ContextCompat.startForegroundService(context, new Intent(context, PersistentService.class));
        }
    }
}
