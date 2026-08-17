package com.micklab.llama;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restarts the API/WebUI foreground service after a device reboot when the user had it enabled
 * (persisted via {@link OllamaForegroundService#PREF_API_ENABLED}). Note: on Android 14+ starting a
 * {@code specialUse} foreground service directly from BOOT_COMPLETED may be disallowed; in that case
 * {@link OllamaForegroundService#startIfEnabled(Context)} fails quietly and the server is re-asserted
 * the next time the user opens the app (MainActivity.onCreate).
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Log.i(TAG, "Boot completed; re-asserting API enable state if set");
            OllamaForegroundService.startIfEnabled(context);
        }
    }
}
