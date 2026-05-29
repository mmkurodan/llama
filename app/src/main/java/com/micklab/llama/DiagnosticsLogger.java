package com.micklab.llama;

import android.app.ActivityManager;
import android.content.Context;
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
import java.util.Locale;
import java.util.Map;

public final class DiagnosticsLogger {
    private static final String TAG = "DiagnosticsLogger";
    private static final String DIAGNOSTICS_DIR_NAME = "diagnostics";
    private static final String PROCESS_LOG_FILENAME = "process_diagnostics.log";
    private static final String LAST_STATE_FILENAME = "last_state.txt";
    private static final String LOGCAT_FILENAME = "recent_logcat.txt";
    private static final String INCOMPLETE_GENERATION_FILENAME = "generation_in_progress.txt";
    private static final long MAX_PROCESS_LOG_BYTES = 512L * 1024L;
    private static final Object LOCK = new Object();

    private DiagnosticsLogger() {
    }

    public static void logEvent(Context context, String category, String message) {
        String line = buildHeader(category) + " " + safe(message);
        synchronized (LOCK) {
            appendLine(context, PROCESS_LOG_FILENAME, line);
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
        }
    }

    public static void clearGenerationInProgress(Context context) {
        synchronized (LOCK) {
            deleteFile(context, INCOMPLETE_GENERATION_FILENAME);
        }
    }

    public static void logIncompleteGenerationIfPresent(Context context) {
        synchronized (LOCK) {
            File markerFile = getDiagnosticsFile(context, INCOMPLETE_GENERATION_FILENAME);
            if (markerFile == null || !markerFile.exists()) {
                return;
            }

            String marker = readFileContents(markerFile).trim();
            if (!marker.isEmpty()) {
                appendLine(
                        context,
                        PROCESS_LOG_FILENAME,
                        buildHeader("previous-crash")
                                + " Detected unfinished generation from previous process: "
                                + toSingleLine(marker));
            }
            deleteFile(markerFile);
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

    private static void trimIfNeeded(File file) {
        if (file == null || !file.exists() || file.length() < MAX_PROCESS_LOG_BYTES) {
            return;
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to trim diagnostics log: " + file.getAbsolutePath());
        }
    }

    private static File getDiagnosticsFile(Context context, String filename) {
        if (context == null) {
            return null;
        }
        File baseDir = context.getExternalFilesDir(null);
        if (baseDir == null) {
            baseDir = context.getFilesDir();
        }
        File diagnosticsDir = new File(baseDir, DIAGNOSTICS_DIR_NAME);
        if (!diagnosticsDir.exists() && !diagnosticsDir.mkdirs() && !diagnosticsDir.isDirectory()) {
            Log.e(TAG, "Failed to create diagnostics directory: " + diagnosticsDir.getAbsolutePath());
            return null;
        }
        return new File(diagnosticsDir, filename);
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
