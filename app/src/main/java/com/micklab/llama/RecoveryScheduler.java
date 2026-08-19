package com.micklab.llama;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Alarm-driven self-recovery for the API foreground service. Two independent, user-toggleable
 * mechanisms share one repeating (self-re-arming) alarm chain delivered to {@link WatchdogReceiver}:
 *
 * <ul>
 *   <li><b>Watchdog (recovery)</b>: on each tick, if the API is enabled but the foreground service is
 *       not running (the previous process was SIGKILLed by a kernel/cgroup OOM, crashed, or was killed
 *       on task removal), restart it. Because the alarm targets a manifest-registered receiver, the OS
 *       cold-starts a fresh process to deliver it even after the previous process died.</li>
 *   <li><b>Recycle (prevention)</b>: periodically, while idle, tear down and relaunch our own process
 *       before an OEM battery manager decides we have been running too long and force-stops us. A
 *       self-initiated kill (unlike an external force-stop) does NOT put the package into the
 *       "stopped" state, so the re-armed alarm still fires and brings us back.</li>
 * </ul>
 *
 * <p><b>Hard limitation:</b> an OEM force-stop (REASON_USER_REQUESTED) cancels all alarms and puts the
 * package into the stopped state, so neither mechanism can recover from it once it happens. Recycle
 * only PREVENTS it by keeping our process young; battery-optimization allowlisting (Settings) is the
 * complementary lever.
 */
public final class RecoveryScheduler {
    private static final String TAG = "RecoveryScheduler";

    public static final String PREF_WATCHDOG_ENABLED = "recovery_watchdog_enabled";
    public static final String PREF_RECYCLE_ENABLED = "recovery_recycle_enabled";
    public static final String PREF_RECYCLE_INTERVAL_MIN = "recovery_recycle_interval_min";

    public static final boolean DEFAULT_WATCHDOG_ENABLED = true;
    public static final boolean DEFAULT_RECYCLE_ENABLED = false;
    public static final int DEFAULT_RECYCLE_INTERVAL_MIN = 45;
    public static final int MIN_RECYCLE_INTERVAL_MIN = 10;
    public static final int MAX_RECYCLE_INTERVAL_MIN = 240;

    static final String ACTION_TICK = "com.micklab.llama.RECOVERY_TICK";

    // Watchdog cadence: how often we probe that the server is alive. Inexact + allow-while-idle keeps
    // it battery-friendly (the OS may stretch this toward ~9 min in deep Doze, which is acceptable for
    // a background server that also holds a wake lock while actually serving).
    private static final long TICK_INTERVAL_MS = 60_000L;
    // Fast comeback after a self-recycle kill.
    private static final long RECYCLE_COMEBACK_MS = 4_000L;
    private static final int REQUEST_CODE = 0xA11;

    private RecoveryScheduler() {}

    public static boolean isWatchdogEnabled(Context c) {
        return prefs(c).getBoolean(PREF_WATCHDOG_ENABLED, DEFAULT_WATCHDOG_ENABLED);
    }

    public static boolean isRecycleEnabled(Context c) {
        return prefs(c).getBoolean(PREF_RECYCLE_ENABLED, DEFAULT_RECYCLE_ENABLED);
    }

    public static int getRecycleIntervalMinutes(Context c) {
        int v = prefs(c).getInt(PREF_RECYCLE_INTERVAL_MIN, DEFAULT_RECYCLE_INTERVAL_MIN);
        return Math.max(MIN_RECYCLE_INTERVAL_MIN, Math.min(MAX_RECYCLE_INTERVAL_MIN, v));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(OllamaForegroundService.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean anyEnabled(Context c) {
        return isWatchdogEnabled(c) || isRecycleEnabled(c);
    }

    private static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, WatchdogReceiver.class).setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, REQUEST_CODE, i, flags);
    }

    /** Kick off (or stop) the alarm chain according to the current toggles + API-enabled state. */
    public static void ensureScheduled(Context c) {
        if (!OllamaForegroundService.isApiEnabled(c) || !anyEnabled(c)) {
            cancel(c);
            return;
        }
        schedule(c, TICK_INTERVAL_MS);
    }

    static void schedule(Context c, long delayMs) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long triggerAt = SystemClock.elapsedRealtime() + delayMs;
        try {
            // Inexact allow-while-idle: fires through Doze without the SCHEDULE_EXACT_ALARM permission
            // that setExactAndAllowWhileIdle would demand on Android 12+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, tickIntent(c));
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, tickIntent(c));
            }
        } catch (Exception e) {
            Log.w(TAG, "schedule failed", e);
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try {
                am.cancel(tickIntent(c));
            } catch (Exception ignored) {
            }
        }
    }

    static long tickIntervalMs() {
        return TICK_INTERVAL_MS;
    }

    static long recycleComebackMs() {
        return RECYCLE_COMEBACK_MS;
    }
}
