package com.micklab.llama;

import android.app.Application;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LlamaApplication extends Application {

    private static final String TAG = "LlamaApplication";
    private static final String CRASH_LOG_FILENAME = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        configureNativeLogging();
        // Determine the authoritative reason the previous process died (ApplicationExitInfo,
        // API 30+) BEFORE classifying any leftover generation marker. A surviving marker only
        // means the process died inside a long native generate() call; the dominant cause is a
        // routine external kill (dev re-install / Settings force-stop / user "Exit"), which must
        // not be reported as a crash. Only genuine in-process failures (or platforms where the
        // reason is unavailable) warrant the subprocess-spawning logcat capture; ApplicationExitInfo
        // itself is persisted and always recorded below regardless.
        final int lastExitReason = DiagnosticsLogger.getLastExitReason(this);
        final boolean captureCrashDiagnostics =
                DiagnosticsLogger.logIncompleteGenerationIfPresent(this, lastExitReason);
        DiagnosticsLogger.logEvent(this, "app", "Application created");
        DiagnosticsLogger.logMemorySnapshot(this, "app-start", "Application onCreate");
        new Thread(() -> {
            DiagnosticsLogger.logPreviousExitReasons(LlamaApplication.this);
            if (captureCrashDiagnostics) {
                DiagnosticsLogger.captureRecentLogcat(LlamaApplication.this, "previous-crash");
            }
        }, "diag-logcat").start();

        // Re-assert a persisted "API enabled" intent as early as possible so the server comes back
        // after a process restart (e.g. START_STICKY did not fire, or the process was revived by a
        // component other than the activity). Best-effort: background foreground-service start
        // restrictions are handled inside startIfEnabled(); MainActivity re-asserts again as the
        // reliable foreground path.
        OllamaForegroundService.startIfEnabled(this);

        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String timestamp = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("=== Uncaught Exception ===");
                pw.println("Time   : " + timestamp);
                pw.println("Thread : " + t.getName() + " (id=" + t.getId() + ")");
                pw.println();
                e.printStackTrace(pw);
                pw.flush();

                File crashFile = getCrashLogFile();
                try (FileWriter fw = new FileWriter(crashFile, false)) {
                    fw.write(sw.toString());
                }
                DiagnosticsLogger.appendToOllamaLog(LlamaApplication.this, sw.toString());
                Log.e(TAG, "Crash log written to " + crashFile.getAbsolutePath());
                DiagnosticsLogger.logEvent(LlamaApplication.this, "java-crash", "Crash log written: " + crashFile.getAbsolutePath());
                DiagnosticsLogger.logMemorySnapshot(LlamaApplication.this, "java-crash", e.toString());
            } catch (Throwable logError) {
                Log.e(TAG, "Failed to persist Java crash log", logError);
            }

            // Delegate to the system default handler so the process terminates normally
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(t, e);
                return;
            }

            Process.killProcess(Process.myPid());
            System.exit(10);
        });
    }

    private void configureNativeLogging() {
        try {
            File logFile = DiagnosticsLogger.getOllamaLogFile(this);
            if (logFile == null) {
                return;
            }
            new LlamaNative().setLogPath(logFile.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to configure native logging", t);
        }
    }

    private File getCrashLogFile() {
        File baseDir = DiagnosticsLogger.getAppFilesBaseDir(this);
        return new File(baseDir != null ? baseDir : getFilesDir(), CRASH_LOG_FILENAME);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        DiagnosticsLogger.logMemorySnapshot(this, "memory-warning", "onLowMemory");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // onTrimMemory fires routinely (e.g. UI_HIDDEN every time the app is backgrounded).
        // The full snapshot runs a slow Debug.getMemoryInfo() + /proc scan, so reserve it for
        // genuinely critical levels and emit a cheap one-line event otherwise.
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level == TRIM_MEMORY_COMPLETE) {
            DiagnosticsLogger.logMemorySnapshot(this, "memory-trim", "onTrimMemory level=" + level);
        } else {
            DiagnosticsLogger.logEvent(this, "memory-trim", "onTrimMemory level=" + level);
        }
    }
}
