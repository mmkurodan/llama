package com.micklab.llama;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the self-re-arming recovery alarm from {@link RecoveryScheduler}. Runs in a freshly
 * cold-started process when the previous one died, which is what lets the watchdog resurrect the
 * foreground service after a SIGKILL/OOM/crash/task-removal.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();

        // Stop the chain if the user disabled the API or turned both mechanisms off.
        if (!OllamaForegroundService.isApiEnabled(app)
                || (!RecoveryScheduler.isWatchdogEnabled(app) && !RecoveryScheduler.isRecycleEnabled(app))) {
            RecoveryScheduler.cancel(app);
            return;
        }

        final boolean serviceRunning = OllamaForegroundService.isServiceRunning();

        // Recovery: the previous process died and this tick cold-started a fresh one in which the
        // service is not up. Bring it back, then re-arm and wait a tick before considering recycle.
        if (RecoveryScheduler.isWatchdogEnabled(app) && !serviceRunning) {
            DiagnosticsLogger.logRecoveryEvent(app, "watchdog-restart",
                    "service not running while API enabled; restarting foreground service");
            OllamaForegroundService.startIfEnabled(app);
            RecoveryScheduler.schedule(app, RecoveryScheduler.tickIntervalMs());
            return;
        }

        // Prevention: proactively recycle our own process while idle, before an OEM battery manager
        // force-stops a long-lived process. Never interrupt an in-flight generation.
        if (RecoveryScheduler.isRecycleEnabled(app) && serviceRunning) {
            long ageMs = OllamaForegroundService.serviceUptimeMs();
            long intervalMs = RecoveryScheduler.getRecycleIntervalMinutes(app) * 60_000L;
            boolean idle = !ModelManager.getInstance(app).isBusy();
            if (ageMs >= intervalMs && idle) {
                DiagnosticsLogger.logRecoveryEvent(app, "proactive-recycle",
                        "idle recycle after " + (ageMs / 60000) + "min to stay under OEM force-stop radar");
                // Arm a fast comeback, then kill our own process. A self-kill (not a force-stop) leaves
                // the alarm intact, so the fresh process restarts the service via the watchdog path.
                RecoveryScheduler.schedule(app, RecoveryScheduler.recycleComebackMs());
                // Clear any stale in-progress marker so the recycle is not later misreported as a crash.
                DiagnosticsLogger.clearGenerationInProgress(app);
                android.os.Process.killProcess(android.os.Process.myPid());
                return;
            }
        }

        // Normal re-arm.
        RecoveryScheduler.schedule(app, RecoveryScheduler.tickIntervalMs());
    }
}
