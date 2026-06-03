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
        final boolean previousCrash = DiagnosticsLogger.logIncompleteGenerationIfPresent(this);
        DiagnosticsLogger.logEvent(this, "app", "Application created");
        DiagnosticsLogger.logMemorySnapshot(this, "app-start", "Application onCreate");
        // If the previous process disappeared mid-generation (e.g. an uncatchable SIGKILL
        // from the low-memory killer), grab logcat as early as possible so the
        // lowmemorykiller / tombstone lines are still in the ring buffer.
        final String logcatReason = previousCrash ? "previous-crash" : "app-start";
        new Thread(() -> DiagnosticsLogger.captureRecentLogcat(LlamaApplication.this, logcatReason), "diag-logcat").start();

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
        DiagnosticsLogger.logMemorySnapshot(this, "memory-trim", "onTrimMemory level=" + level);
    }
}
