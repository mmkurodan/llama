package com.micklab.llama;

import android.app.Application;
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

                File crashFile = new File(getFilesDir(), CRASH_LOG_FILENAME);
                try (FileWriter fw = new FileWriter(crashFile, false)) {
                    fw.write(sw.toString());
                }
                Log.e(TAG, "Crash log written to " + crashFile.getAbsolutePath());
            } catch (Throwable ignored) {
                // Best-effort — don't make things worse
            }

            // Delegate to the system default handler so the process terminates normally
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(t, e);
            }
        });
    }
}
