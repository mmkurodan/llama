package com.micklab.llama;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * Bundles a {@link PowerManager#PARTIAL_WAKE_LOCK} and a Wi-Fi lock so background inference / the API
 * server keep running while the screen is off. Both underlying locks are non-reference-counted; this
 * class does its own synchronized counting so unbalanced or duplicate release() calls can never throw
 * the "under-locked" RuntimeException. The lock is held while the count is &gt; 0.
 *
 * <p>Wi-Fi uses {@code WIFI_MODE_FULL_HIGH_PERF}: unlike {@code LOW_LATENCY} (foreground-only) it keeps
 * the radio responsive with the screen off, which is what a LAN API server needs. Surviving Doze also
 * requires the app to be exempt from battery optimization (requested separately).
 */
public final class PowerLocks {
    private static final String TAG = "PowerLocks";

    private final PowerManager.WakeLock wakeLock;
    private final WifiManager.WifiLock wifiLock;
    private int count = 0;

    public PowerLocks(Context context, String tag) {
        Context app = context.getApplicationContext();

        PowerManager.WakeLock wl = null;
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag + ":wake");
            wl.setReferenceCounted(false);
        }
        wakeLock = wl;

        WifiManager.WifiLock wfl = null;
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wfl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag + ":wifi");
            wfl.setReferenceCounted(false);
        }
        wifiLock = wfl;
    }

    /** Acquire (raise the hold count); acquires the real locks on the 0 -> 1 transition. */
    public synchronized void acquire() {
        if (count++ == 0) {
            rawAcquire();
        }
    }

    /** Release (lower the hold count); releases the real locks on the 1 -> 0 transition. No-op at 0. */
    public synchronized void release() {
        if (count > 0 && --count == 0) {
            rawRelease();
        }
    }

    /** Force-release everything regardless of count (e.g. on service destroy). */
    public synchronized void releaseAll() {
        count = 0;
        rawRelease();
    }

    private void rawAcquire() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "wake lock acquire failed", e);
        }
        try {
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "wifi lock acquire failed", e);
        }
    }

    private void rawRelease() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "wake lock release failed", e);
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "wifi lock release failed", e);
        }
    }
}
