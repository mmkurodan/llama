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
    /**
     * Bounded set of pids this app intentionally self-killed (proactive recycle / user exit), stored
     * as CSV of {@code "pid:kind"} tokens. Needed because a deliberate {@code killProcess(myPid())}
     * is reported by {@link ApplicationExitInfo} identically to an OOM kill
     * ({@link ApplicationExitInfo#REASON_SIGNALED} / status=9); the next launch consults this to tell
     * the benign self-restart apart from a real memory kill.
     */
    private static final String PREF_INTENTIONAL_SELF_KILLS = "intentional_self_kills";
    private static final int MAX_TRACKED_SELF_KILLS = 16;
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
                // A SIGNALED exit whose pid we recorded as a deliberate self-kill is a benign
                // recycle/exit, not an OOM SIGKILL — flag it so log analysis does not conflate them.
                String selfKillKind = info.getReason() == ApplicationExitInfo.REASON_SIGNALED
                        ? intentionalSelfKillKind(context, info.getPid())
                        : null;
                String line = buildHeader("previous-exit")
                        + " pid=" + info.getPid()
                        + " reason=" + exitReasonName(info.getReason()) + "(" + info.getReason() + ")"
                        + " status=" + info.getStatus()
                        + " importance=" + info.getImportance()
                        + " pss=" + formatBytes(info.getPss() * 1024L)
                        + " rss=" + formatBytes(info.getRss() * 1024L)
                        + " time=" + fmt.format(new Date(info.getTimestamp()))
                        + (selfKillKind != null ? " intentional=" + selfKillKind : "")
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

    /** Snapshot of the most recent process exit: reason, signal/exit status, and RSS at death. */
    public static final class ExitSummary {
        public final int reason;     // ApplicationExitInfo.REASON_* or EXIT_REASON_UNAVAILABLE
        public final int status;     // signal number when reason==SIGNALED; otherwise process exit status
        public final long rssBytes;  // resident set size at death (bytes), 0 when unknown
        /** Non-null (e.g. "proactive-recycle", "user-exit") when the exit was a deliberate self-kill
         *  by this app rather than an external/OOM kill; null otherwise. */
        public final String intentionalSelfKill;

        public ExitSummary(int reason, int status, long rssBytes, String intentionalSelfKill) {
            this.reason = reason;
            this.status = status;
            this.rssBytes = rssBytes;
            this.intentionalSelfKill = intentionalSelfKill;
        }
    }

    /**
     * Richer variant of {@link #getLastExitReason} that also returns the signal/exit status and the
     * RSS at death, so callers can distinguish e.g. an OOM/SIGKILL from an orderly exit and report an
     * accurate cause. Returns an {@link ExitSummary} with {@link #EXIT_REASON_UNAVAILABLE} when the
     * information cannot be read (API &lt; 30, no service, or no recorded history).
     */
    public static ExitSummary getLastExitSummary(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return new ExitSummary(EXIT_REASON_UNAVAILABLE, 0, 0L, null);
        }
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return new ExitSummary(EXIT_REASON_UNAVAILABLE, 0, 0L, null);
        }
        try {
            List<ApplicationExitInfo> infos =
                    activityManager.getHistoricalProcessExitReasons(null, 0, 1);
            if (infos == null || infos.isEmpty()) {
                return new ExitSummary(EXIT_REASON_UNAVAILABLE, 0, 0L, null);
            }
            ApplicationExitInfo info = infos.get(0);
            String selfKillKind = info.getReason() == ApplicationExitInfo.REASON_SIGNALED
                    ? intentionalSelfKillKind(context, info.getPid())
                    : null;
            return new ExitSummary(info.getReason(), info.getStatus(), info.getRss() * 1024L, selfKillKind);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read last exit summary", t);
            return new ExitSummary(EXIT_REASON_UNAVAILABLE, 0, 0L, null);
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
     * Records that {@code pid} (this process) is about to be intentionally terminated via
     * {@code Process.killProcess(myPid())} — a proactive recycle or a user-requested exit — so the
     * next launch can distinguish this benign self-kill from an OOM kill, which
     * {@link ApplicationExitInfo} reports identically as {@link ApplicationExitInfo#REASON_SIGNALED}
     * / status=9. MUST be called immediately before the self-kill; uses a synchronous
     * {@code commit()} so the marker is persisted before the process dies.
     *
     * @param kind short label for the exit, e.g. {@code "proactive-recycle"} or {@code "user-exit"}
     */
    public static void markIntentionalSelfKill(Context context, int pid, String kind) {
        if (context == null) {
            return;
        }
        String cleanKind = safe(kind).replace(',', ' ').replace(':', ' ').trim();
        synchronized (LOCK) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String prefix = pid + ":";
            java.util.ArrayList<String> tokens = new java.util.ArrayList<>();
            for (String token : prefs.getString(PREF_INTENTIONAL_SELF_KILLS, "").split(",")) {
                // Drop blanks and any stale entry for this same (possibly reused) pid.
                if (!token.isEmpty() && !token.startsWith(prefix)) {
                    tokens.add(token);
                }
            }
            tokens.add(prefix + cleanKind);
            while (tokens.size() > MAX_TRACKED_SELF_KILLS) {
                tokens.remove(0);
            }
            prefs.edit()
                    .putString(PREF_INTENTIONAL_SELF_KILLS, android.text.TextUtils.join(",", tokens))
                    .commit();
        }
    }

    /**
     * Returns the recorded self-kill kind (e.g. {@code "proactive-recycle"}) for {@code pid}, or
     * {@code null} if that pid was not an intentional self-termination by this app.
     */
    private static String intentionalSelfKillKind(Context context, int pid) {
        if (context == null) {
            return null;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String csv = prefs.getString(PREF_INTENTIONAL_SELF_KILLS, "");
        if (csv.isEmpty()) {
            return null;
        }
        String prefix = pid + ":";
        for (String token : csv.split(",")) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * Records a self-recovery lifecycle event (watchdog restart / proactive recycle) to the shared
     * ollama.log so the alarm-driven restart chain is auditable alongside the exit-reason records.
     */
    public static void logRecoveryEvent(Context context, String category, String detail) {
        String line = buildHeader(category) + " " + safe(detail);
        synchronized (LOCK) {
            appendToOllamaLogLocked(context, line);
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
        // RssAnon / RssFile split the resident set into the anonymous vs file-backed buckets
        // that the Google Play "Anonymous RSS + Swap" memory requirement is measured against.
        appendProcStatusValue(sb, procStatus, "RssAnon");
        appendProcStatusValue(sb, procStatus, "RssFile");
        appendProcStatusValue(sb, procStatus, "RssShmem");
        appendProcStatusValue(sb, procStatus, "Threads");
        appendProcStatusValue(sb, procStatus, "FDSize");
        appendSmapsRollup(sb);
        return sb.toString();
    }

    /**
     * Appends a one-line summary of {@code /proc/self/smaps_rollup}, the aggregate memory
     * accounting whose Anonymous/Swap fields mirror what Google Play measures for the
     * "reduced memory usage" requirement (90th-percentile Anonymous RSS + Swap). The headline
     * {@code anonPlusSwap} is the direct proxy for that metric; {@code filePss} is the
     * file-backed portion that mmap'd model weights should fall into (and therefore be excluded
     * from the metric). Silently no-ops when the file is unavailable (older kernels / restricted
     * mounts) so it can never break snapshot logging.
     */
    private static void appendSmapsRollup(StringBuilder sb) {
        Map<String, Long> rollup = readSmapsRollup();
        if (rollup.isEmpty()) {
            return;
        }
        long anon = rollup.getOrDefault("Anonymous", -1L);
        long swap = rollup.getOrDefault("Swap", -1L);
        long anonPlusSwap = anon >= 0 && swap >= 0 ? anon + swap : -1L;
        sb.append("\n  smapsRollup");
        appendKb(sb, "rss", rollup.get("Rss"));
        appendKb(sb, "pss", rollup.get("Pss"));
        appendKb(sb, "anon", rollup.get("Anonymous"));
        appendKb(sb, "anonPss", rollup.get("Pss_Anon"));
        appendKb(sb, "filePss", rollup.get("Pss_File"));
        appendKb(sb, "privDirty", rollup.get("Private_Dirty"));
        appendKb(sb, "swap", rollup.get("Swap"));
        appendKb(sb, "swapPss", rollup.get("SwapPss"));
        if (anonPlusSwap >= 0) {
            sb.append(" anonPlusSwap=").append(formatBytes(anonPlusSwap * 1024L));
        }
    }

    private static void appendKb(StringBuilder sb, String label, Long valueKb) {
        if (valueKb == null || valueKb < 0) {
            return;
        }
        sb.append(' ').append(label).append('=').append(formatBytes(valueKb * 1024L));
    }

    /**
     * Reads {@code /proc/self/smaps_rollup} into a map of field name to kB value. Each data line
     * has the form {@code "Anonymous:  123456 kB"}; the leading address/range header line is
     * ignored. Returns an empty map on any error or when the file is absent.
     */
    private static Map<String, Long> readSmapsRollup() {
        Map<String, Long> values = new LinkedHashMap<>();
        File rollupFile = new File("/proc/self/smaps_rollup");
        if (!rollupFile.exists()) {
            return values;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(rollupFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                String rest = line.substring(colon + 1).trim();
                int space = rest.indexOf(' ');
                String number = space > 0 ? rest.substring(0, space) : rest;
                try {
                    values.put(key, Long.parseLong(number.trim()));
                } catch (NumberFormatException ignored) {
                    // Header/non-numeric line (e.g. the address-range row); skip it.
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read /proc/self/smaps_rollup", e);
        }
        return values;
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
                        && !"RssAnon".equals(key)
                        && !"RssFile".equals(key)
                        && !"RssShmem".equals(key)
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
