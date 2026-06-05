package com.micklab.llama;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DiagnosticsLogger {
    private static final String TAG = "DiagnosticsLogger";
    private static final String DIAGNOSTICS_DIR_NAME = "diagnostics";
    private static final String PROCESS_LOG_FILENAME = "process_diagnostics.log";
    private static final String LAST_STATE_FILENAME = "last_state.txt";
    private static final String LOGCAT_FILENAME = "recent_logcat.txt";
    private static final String INCOMPLETE_GENERATION_FILENAME = "generation_in_progress.txt";
    private static final String OLLAMA_LOG_FILENAME = "ollama.log";
    private static final String JAVA_CRASH_FILENAME = "last_crash.txt";
    private static final String NATIVE_CRASH_FILENAME = "native_crash.txt";
    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_LOG_LEVEL = "log_level";
    private static final int LOG_LEVEL_MAX_DEBUG = 0;
    private static final long MAX_PROCESS_LOG_BYTES = 512L * 1024L;
    /** Sentinel returned by {@link #getLastExitReason} when the reason cannot be determined. */
    public static final int EXIT_REASON_UNAVAILABLE = -1;
    private static final Object LOCK = new Object();

    private DiagnosticsLogger() {
    }

    public static void logEvent(Context context, String category, String message) {
        String line = buildHeader(category) + " " + safe(message);
        synchronized (LOCK) {
            appendLine(context, PROCESS_LOG_FILENAME, line);
            appendToOllamaLogLocked(context, line);
        }
    }

    public static void logMemorySnapshot(Context context, String category, String message) {
        String snapshot = buildHeader(category)
                + " " + safe(message)
                + "\n"
                + collectMemorySnapshot(context);
        synchronized (LOCK) {
            appendLine(context, PROCESS_LOG_FILENAME, snapshot);
            overwriteFile(context, LAST_STATE_FILENAME, snapshot + "\n");
            appendToOllamaLogLocked(context, snapshot);
        }
    }

    public static void captureRecentLogcat(Context context, String reason) {
        StringBuilder output = new StringBuilder();
        output.append(buildHeader("logcat")).append(" reason=").append(safe(reason)).append('\n');
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder(
                    "logcat",
                    "-d",
                    "-v",
                    "time",
                    "-t",
                    "400",
                    "LLAMA_JNI:I",
                    "ModelManager:I",
                    "MainActivity:I",
                    "LlamaApplication:I",
                    "OllamaApiServer:I",
                    "AndroidRuntime:E",
                    "ActivityManager:I",
                    "DEBUG:I",
                    "crash_dump64:I",
                    "libc:F",
                    "libc:E",
                    "lmkd:I",
                    "lowmemorykiller:I",
                    "*:S")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            output.append("exitCode=").append(exitCode).append('\n');
        } catch (IOException e) {
            output.append("logcat unavailable: ").append(e).append('\n');
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output.append("logcat interrupted: ").append(e).append('\n');
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        synchronized (LOCK) {
            overwriteFile(context, LOGCAT_FILENAME, output.toString());
            if (isMaxDebugEnabled(context)) {
                appendToOllamaLogLocked(context, output.toString());
            }
        }
    }

    /**
     * Records why recent previous processes of this app terminated, using
     * {@link ActivityManager#getHistoricalProcessExitReasons} (API 30+). Unlike the
     * logcat ring buffer this is persisted by the system and survives an arbitrary gap
     * between the death and the next launch, so it reliably distinguishes an OS-level
     * kill (LOW_MEMORY / SIGNALED-SIGKILL / EXCESSIVE_RESOURCE_USAGE / USER_REQUESTED)
     * from an in-process native or Java crash (CRASH_NATIVE / CRASH / SIGNALED-SIGSEGV).
     */
    public static void logPreviousExitReasons(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return;
        }
        try {
            List<ApplicationExitInfo> infos =
                    activityManager.getHistoricalProcessExitReasons(null, 0, 8);
            if (infos == null || infos.isEmpty()) {
                return;
            }
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            for (ApplicationExitInfo info : infos) {
                String line = buildHeader("previous-exit")
                        + " pid=" + info.getPid()
                        + " reason=" + exitReasonName(info.getReason()) + "(" + info.getReason() + ")"
                        + " status=" + info.getStatus()
                        + " importance=" + info.getImportance()
                        + " pss=" + formatBytes(info.getPss() * 1024L)
                        + " rss=" + formatBytes(info.getRss() * 1024L)
                        + " time=" + fmt.format(new Date(info.getTimestamp()))
                        + " desc=" + toSingleLine(info.getDescription());
                synchronized (LOCK) {
                    appendLine(context, PROCESS_LOG_FILENAME, line);
                    appendToOllamaLogLocked(context, line);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read historical exit reasons", t);
        }
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            case ApplicationExitInfo.REASON_FREEZER: return "FREEZER";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "PACKAGE_STATE_CHANGE";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "PACKAGE_UPDATED";
            default: return "UNKNOWN";
        }
    }

    /**
     * Returns the reason code of the most recent recorded process exit
     * ({@link ApplicationExitInfo}, API 30+), or {@link #EXIT_REASON_UNAVAILABLE} when it
     * cannot be determined (older platforms, no service, or no recorded history). This is
     * the authoritative source that distinguishes a real in-process crash from an orderly
     * external termination such as a dev re-install or a Settings "Force stop".
     */
    public static int getLastExitReason(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return EXIT_REASON_UNAVAILABLE;
        }
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return EXIT_REASON_UNAVAILABLE;
        }
        try {
            List<ApplicationExitInfo> infos =
                    activityManager.getHistoricalProcessExitReasons(null, 0, 1);
            if (infos == null || infos.isEmpty()) {
                return EXIT_REASON_UNAVAILABLE;
            }
            return infos.get(0).getReason();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read last exit reason", t);
            return EXIT_REASON_UNAVAILABLE;
        }
    }

    /**
     * Classifies whether an {@link ApplicationExitInfo} reason represents a genuine
     * in-process failure (native/Java crash, kernel signal, OOM/LMK kill, excessive
     * resource kill) rather than an orderly external termination (re-install, force-stop,
     * user-requested exit, package state change). Used to avoid reporting routine external
     * kills as "crashes".
     */
    public static boolean isGenuineCrashReason(int reason) {
        if (reason == EXIT_REASON_UNAVAILABLE || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_SIGNALED:
            case ApplicationExitInfo.REASON_LOW_MEMORY:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return true;
            default:
                return false;
        }
    }

    public static void markGenerationInProgress(
            Context context,
            int generationId,
            String configName,
            String modelName,
            int promptLen,
            int mediaCount) {
        String line = buildHeader("generation-pending")
                + " id=" + generationId
                + " config=" + safe(configName)
                + " model=" + safe(modelName)
                + " promptLen=" + Math.max(promptLen, 0)
                + " mediaCount=" + Math.max(mediaCount, 0);
        synchronized (LOCK) {
            overwriteFile(context, INCOMPLETE_GENERATION_FILENAME, line + "\n");
            appendToOllamaLogLocked(context, line);
        }
    }

    public static void clearGenerationInProgress(Context context) {
        synchronized (LOCK) {
            deleteFile(context, INCOMPLETE_GENERATION_FILENAME);
        }
    }

    /**
     * If the previous process left a generation marker behind, records why and returns
     * whether crash diagnostics (logcat) are worth capturing. The marker alone only means
     * "the process died during a native generate call"; the most common cause is a routine
     * external kill (dev re-install, Settings force-stop, user exit) which must NOT be
     * reported as a crash. We therefore cross-reference {@code lastExitReason} (from
     * {@link #getLastExitReason}) and only classify as a crash when the OS attributes the
     * death to a real in-process failure. When the reason is unavailable (API &lt; 30) we
     * stay conservative and still capture logcat without asserting a crash.
     *
     * @param lastExitReason authoritative reason of the previous exit, or
     *                       {@link #EXIT_REASON_UNAVAILABLE}
     * @return true if logcat crash diagnostics should be captured
     */
    public static boolean logIncompleteGenerationIfPresent(Context context, int lastExitReason) {
        synchronized (LOCK) {
            File markerFile = getDiagnosticsFile(context, INCOMPLETE_GENERATION_FILENAME);
            if (markerFile == null || !markerFile.exists()) {
                return false;
            }

            final boolean reasonKnown = lastExitReason != EXIT_REASON_UNAVAILABLE
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
            final boolean genuineCrash = isGenuineCrashReason(lastExitReason);
            final String category;
            final String classification;
            final boolean captureDiagnostics;
            if (genuineCrash) {
                category = "previous-crash";
                classification = "reason=" + exitReasonName(lastExitReason) + "(" + lastExitReason + ")";
                captureDiagnostics = true;
            } else if (reasonKnown) {
                // Orderly external termination (re-install / force-stop / user exit): not a crash.
                category = "previous-exit-during-generation";
                classification = "benign reason=" + exitReasonName(lastExitReason) + "(" + lastExitReason + ")";
                captureDiagnostics = false;
            } else {
                // No authoritative reason on this platform; flag as unclean but do not assert a crash.
                category = "previous-unclean-exit";
                classification = "reason=unavailable";
                captureDiagnostics = true;
            }

            String marker = readFileContents(markerFile).trim();
            if (!marker.isEmpty()) {
                String message = buildHeader(category)
                        + " Detected unfinished generation from previous process (" + classification + "): "
                        + toSingleLine(marker);
                appendLine(context, PROCESS_LOG_FILENAME, message);
                appendToOllamaLogLocked(context, message);
            }
            deleteFile(markerFile);
            return captureDiagnostics;
        }
    }

    public static File getAppFilesBaseDir(Context context) {
        if (context == null) {
            return null;
        }
        File externalDir = context.getExternalFilesDir(null);
        return externalDir != null ? externalDir : context.getFilesDir();
    }

    public static File getOllamaLogFile(Context context) {
        File baseDir = getAppFilesBaseDir(context);
        return baseDir != null ? new File(baseDir, OLLAMA_LOG_FILENAME) : null;
    }

    public static void appendToOllamaLog(Context context, String contents) {
        synchronized (LOCK) {
            appendToOllamaLogLocked(context, contents);
        }
    }

    public static void clearLogFiles(Context context) {
        synchronized (LOCK) {
            truncateFile(getOllamaLogFile(context));
            deleteFile(context, PROCESS_LOG_FILENAME);
            deleteFile(context, LAST_STATE_FILENAME);
            deleteFile(context, LOGCAT_FILENAME);
            deleteFile(context, INCOMPLETE_GENERATION_FILENAME);

            File baseDir = getAppFilesBaseDir(context);
            if (baseDir != null) {
                deleteFile(new File(baseDir, JAVA_CRASH_FILENAME));
                deleteFile(new File(baseDir, NATIVE_CRASH_FILENAME));
            }
        }
    }

    private static String collectMemorySnapshot(Context context) {
        Runtime runtime = Runtime.getRuntime();
        long javaUsed = runtime.totalMemory() - runtime.freeMemory();
        long javaTotal = runtime.totalMemory();
        long javaMax = runtime.maxMemory();
        long nativeHeap = Debug.getNativeHeapAllocatedSize();
        long nativeHeapFree = Debug.getNativeHeapFreeSize();
        long nativeHeapSize = Debug.getNativeHeapSize();

        Debug.MemoryInfo debugInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(debugInfo);

        ActivityManager.MemoryInfo systemInfo = new ActivityManager.MemoryInfo();
        ActivityManager.RunningAppProcessInfo processInfo = new ActivityManager.RunningAppProcessInfo();
        ActivityManager activityManager = context != null
                ? (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)
                : null;
        if (activityManager != null) {
            activityManager.getMemoryInfo(systemInfo);
        }
        ActivityManager.getMyMemoryState(processInfo);

        Map<String, String> procStatus = readProcStatus();
        int fdCount = countOpenFileDescriptors();

        StringBuilder sb = new StringBuilder();
        sb.append("  javaUsed=").append(formatBytes(javaUsed))
                .append(" javaTotal=").append(formatBytes(javaTotal))
                .append(" javaMax=").append(formatBytes(javaMax))
                .append(" nativeHeap=").append(formatBytes(nativeHeap))
                .append(" nativeHeapFree=").append(formatBytes(nativeHeapFree))
                .append(" nativeHeapSize=").append(formatBytes(nativeHeapSize))
                .append('\n');
        sb.append("  pssTotal=").append(formatKb(debugInfo.getTotalPss()))
                .append(" privateDirty=").append(formatKb(debugInfo.getTotalPrivateDirty()))
                .append(" sharedDirty=").append(formatKb(debugInfo.getTotalSharedDirty()))
                .append(" nativePss=").append(formatKb(debugInfo.nativePss))
                .append(" dalvikPss=").append(formatKb(debugInfo.dalvikPss))
                .append(" otherPss=").append(formatKb(debugInfo.otherPss))
                .append('\n');
        sb.append("  systemAvail=").append(formatBytes(systemInfo.availMem))
                .append(" systemThreshold=").append(formatBytes(systemInfo.threshold))
                .append(" lowMemory=").append(systemInfo.lowMemory)
                .append(" trimLevel=").append(processInfo.lastTrimLevel)
                .append(" importance=").append(processInfo.importance)
                .append(" fdCount=").append(fdCount)
                .append('\n');
        sb.append("  procStatus");
        appendProcStatusValue(sb, procStatus, "VmRSS");
        appendProcStatusValue(sb, procStatus, "VmHWM");
        appendProcStatusValue(sb, procStatus, "VmSize");
        appendProcStatusValue(sb, procStatus, "VmSwap");
        appendProcStatusValue(sb, procStatus, "Threads");
        appendProcStatusValue(sb, procStatus, "FDSize");
        return sb.toString();
    }

    private static void appendProcStatusValue(StringBuilder sb, Map<String, String> status, String key) {
        String value = status.get(key);
        if (value != null && !value.isEmpty()) {
            sb.append(' ').append(key).append('=').append(value);
        }
    }

    private static Map<String, String> readProcStatus() {
        Map<String, String> values = new LinkedHashMap<>();
        File statusFile = new File("/proc/self/status");
        if (!statusFile.exists()) {
            return values;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon <= 0 || colon + 1 >= line.length()) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                if (!"VmRSS".equals(key)
                        && !"VmHWM".equals(key)
                        && !"VmSize".equals(key)
                        && !"VmSwap".equals(key)
                        && !"Threads".equals(key)
                        && !"FDSize".equals(key)) {
                    continue;
                }
                values.put(key, line.substring(colon + 1).trim());
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read /proc/self/status", e);
        }
        return values;
    }

    private static int countOpenFileDescriptors() {
        File fdDir = new File("/proc/self/fd");
        File[] files = fdDir.listFiles();
        return files != null ? files.length : -1;
    }

    private static void appendLine(Context context, String filename, String line) {
        File file = getDiagnosticsFile(context, filename);
        if (file == null) {
            return;
        }
        trimIfNeeded(file);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            if (!line.endsWith("\n")) {
                writer.write('\n');
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to append diagnostics log", e);
        }
    }

    private static void appendToOllamaLogLocked(Context context, String contents) {
        if (contents == null || contents.isEmpty()) {
            return;
        }
        File logFile = getOllamaLogFile(context);
        if (logFile == null) {
            return;
        }
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            Log.e(TAG, "Failed to create log directory: " + parent.getAbsolutePath());
            return;
        }
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(contents);
            if (!contents.endsWith("\n")) {
                writer.write('\n');
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to append unified log", e);
        }
    }

    private static void overwriteFile(Context context, String filename, String contents) {
        File file = getDiagnosticsFile(context, filename);
        if (file == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(contents != null ? contents : "");
        } catch (IOException e) {
            Log.e(TAG, "Failed to overwrite diagnostics file", e);
        }
    }

    private static String readFileContents(File file) {
        StringBuilder contents = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (!firstLine) {
                    contents.append('\n');
                }
                contents.append(line);
                firstLine = false;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read diagnostics file", e);
        }
        return contents.toString();
    }

    private static void deleteFile(Context context, String filename) {
        File file = getDiagnosticsFile(context, filename);
        deleteFile(file);
    }

    private static void deleteFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete diagnostics file: " + file.getAbsolutePath());
        }
    }

    private static void truncateFile(File file) {
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            Log.e(TAG, "Failed to create log directory: " + parent.getAbsolutePath());
            return;
        }
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write("");
        } catch (IOException e) {
            Log.e(TAG, "Failed to truncate log file", e);
        }
    }

    private static void trimIfNeeded(File file) {
        if (file == null || !file.exists() || file.length() < MAX_PROCESS_LOG_BYTES) {
            return;
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to trim diagnostics log: " + file.getAbsolutePath());
        }
    }

    private static File getDiagnosticsFile(Context context, String filename) {
        File baseDir = getAppFilesBaseDir(context);
        if (baseDir == null) {
            return null;
        }
        File diagnosticsDir = new File(baseDir, DIAGNOSTICS_DIR_NAME);
        if (!diagnosticsDir.exists() && !diagnosticsDir.mkdirs() && !diagnosticsDir.isDirectory()) {
            Log.e(TAG, "Failed to create diagnostics directory: " + diagnosticsDir.getAbsolutePath());
            return null;
        }
        return new File(diagnosticsDir, filename);
    }

    private static boolean isMaxDebugEnabled(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_LOG_LEVEL, 2) == LOG_LEVEL_MAX_DEBUG;
    }

    private static String buildHeader(String category) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        return timestamp + " pid=" + Process.myPid() + " [" + safe(category) + "]";
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String toSingleLine(String value) {
        return safe(value).replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return String.valueOf(bytes);
        }
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        return String.format(Locale.US, "%.1f%s", value, units[unitIndex]);
    }

    private static String formatKb(int valueKb) {
        if (valueKb < 0) {
            return String.valueOf(valueKb);
        }
        return formatBytes(valueKb * 1024L);
    }
}
