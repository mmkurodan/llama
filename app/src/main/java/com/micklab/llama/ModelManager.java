package com.micklab.llama;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Debug;
import android.util.Log;

import org.json.JSONException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Singleton class that manages model loading and generation.
 * Provides unified access for both UI and API, with busy state tracking.
 */
public class ModelManager {
    private static final String TAG = "ModelManager";
    private static final String DEFAULT_CONFIG_NAME = "default";
    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_LOG_LEVEL = "log_level";
    private static final int DEFAULT_LOG_LEVEL_INFO = 2;
    private static final int DEFAULT_N_CTX = 2048;
    private static final int DEFAULT_N_THREADS = 2;
    private static final int DEFAULT_N_BATCH = 16;
    private static final float DEFAULT_TEMP = 0.7f;
    private static final float DEFAULT_TOP_P = 0.9f;
    private static final int DEFAULT_TOP_K = 40;
    private static final int GPU_LAYERS_DISABLED = 0;
    private static final int GPU_LAYERS_ENABLED_ALL = -1;
    private static final Pattern SPLIT_GGUF_PATTERN = Pattern.compile("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$");
    private static final String DOWNLOAD_CA_BUNDLE_FILENAME = "download-ca-bundle.pem";
    
    private static ModelManager instance;
    
    private final Context context;
    private final LlamaNative llama;
    private final ConfigurationManager configManager;
    private final AtomicInteger generationCounter = new AtomicInteger(0);
    
    // State tracking
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Object stateLock = new Object();
    private boolean resetPending = false;
    private boolean reinitializing = false;
    private volatile String currentConfigName = null;
    private volatile String currentModelPath = null;
    private volatile String currentMmprojPath = null;
    private volatile boolean modelLoaded = false;
    public enum ForceReinitializeResult {
        SUCCESS,
        FAILED,
        ALREADY_PENDING
    }
    
    // Listener interface
    public interface ModelListener {
        void onModelLoading(String configName);
        void onModelLoaded(String configName);
        void onGenerating(String configName);
        void onGenerationComplete(String configName, String result);
        void onError(String error);
    }

    public interface BusyStateListener {
        void onBusyStateChanged(boolean busy);
    }
    
    private ModelListener listener;
    private final CopyOnWriteArrayList<BusyStateListener> busyStateListeners = new CopyOnWriteArrayList<>();
    
    private ModelManager(Context context) {
        this.context = context.getApplicationContext();
        this.llama = new LlamaNative();
        this.configManager = new ConfigurationManager(this.context);
        
        // Set JNI log path
        File logFile = new File(context.getExternalFilesDir(null), "ollama.log");
        try {
            llama.setLogPath(logFile.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to set log path", t);
        }

        SharedPreferences prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int defaultLogLevel = DEFAULT_LOG_LEVEL_INFO;
        int savedLogLevel = prefs.contains(PREF_LOG_LEVEL)
                ? prefs.getInt(PREF_LOG_LEVEL, defaultLogLevel)
                : defaultLogLevel;
        llama.setLogLevel(savedLogLevel);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Initial log level set to " + savedLogLevel);
        }
    }
    
    public static synchronized ModelManager getInstance(Context context) {
        if (instance == null) {
            instance = new ModelManager(context);
        }
        return instance;
    }
    
    public void setListener(ModelListener listener) {
        this.listener = listener;
    }

    public void addBusyStateListener(BusyStateListener listener) {
        if (listener == null) {
            return;
        }
        busyStateListeners.addIfAbsent(listener);
        listener.onBusyStateChanged(isBusy());
    }

    public void removeBusyStateListener(BusyStateListener listener) {
        if (listener == null) {
            return;
        }
        busyStateListeners.remove(listener);
    }
    
    public LlamaNative getLlama() {
        return llama;
    }
    
    public boolean isBusy() {
        synchronized (stateLock) {
            return busy.get() || resetPending || reinitializing;
        }
    }

    public boolean isResetPendingOrInProgress() {
        synchronized (stateLock) {
            return resetPending || reinitializing;
        }
    }
    
    public boolean isModelLoaded() {
        return modelLoaded;
    }
    
    public String getCurrentConfigName() {
        return currentConfigName;
    }
    
    public String getCurrentModelPath() {
        return currentModelPath;
    }

    public boolean supportsVision() {
        return modelLoaded && llama.supportsVision();
    }

    public boolean supportsAudio() {
        return modelLoaded && llama.supportsAudio();
    }
    
    /**
     * Try to acquire the busy lock for generation.
     * @return true if lock acquired, false if already busy
     */
    public boolean tryAcquire() {
        boolean previousBusy;
        boolean currentBusy;
        synchronized (stateLock) {
            if (busy.get() || resetPending || reinitializing) {
                return false;
            }
            previousBusy = currentBusyStateLocked();
            busy.set(true);
            currentBusy = currentBusyStateLocked();
        }
        notifyBusyStateIfChanged(previousBusy, currentBusy);
        return true;
    }
    
    /**
     * Release the busy lock.
     */
    public void release() {
        boolean previousBusy;
        boolean currentBusy;
        synchronized (stateLock) {
            previousBusy = currentBusyStateLocked();
            busy.set(false);
            stateLock.notifyAll();
            currentBusy = currentBusyStateLocked();
        }
        notifyBusyStateIfChanged(previousBusy, currentBusy);
    }
    
    /**
     * Force reset the busy state.
     * This is used during model reinitialization to clear any stuck busy state.
     */
    public void resetBusy() {
        boolean previousBusy;
        boolean currentBusy;
        synchronized (stateLock) {
            previousBusy = currentBusyStateLocked();
            busy.set(false);
            stateLock.notifyAll();
            currentBusy = currentBusyStateLocked();
        }
        notifyBusyStateIfChanged(previousBusy, currentBusy);
        Log.i(TAG, "Busy state forcefully reset");
    }

    public ForceReinitializeResult forceReinitializeConfiguration(String configName) {
        String resolvedConfigName = normalizeConfigName(configName);
        boolean shouldInterruptCurrentWork;
        boolean previousBusy;
        boolean currentBusy;

        synchronized (stateLock) {
            if (resetPending || reinitializing) {
                Log.i(TAG, "Force reinitialize already pending/in progress: " + resolvedConfigName);
                return ForceReinitializeResult.ALREADY_PENDING;
            }
            previousBusy = currentBusyStateLocked();
            resetPending = true;
            shouldInterruptCurrentWork = busy.get();
            currentBusy = currentBusyStateLocked();
        }
        notifyBusyStateIfChanged(previousBusy, currentBusy);

        if (shouldInterruptCurrentWork) {
            Log.i(TAG, "Interrupting current model work before force reinitialize: " + resolvedConfigName);
            llama.cancelGeneration();
        }

        try {
            synchronized (stateLock) {
                while (busy.get()) {
                    try {
                        stateLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Interrupted while waiting to force reinitialize", e);
                        return ForceReinitializeResult.FAILED;
                    }
                }
                previousBusy = currentBusyStateLocked();
                resetPending = false;
                reinitializing = true;
                currentBusy = currentBusyStateLocked();
            }
            notifyBusyStateIfChanged(previousBusy, currentBusy);

            llama.setTokenListener(null);
            boolean success = reinitializeConfiguration(resolvedConfigName);
            return success ? ForceReinitializeResult.SUCCESS : ForceReinitializeResult.FAILED;
        } finally {
            boolean busyChanged;
            synchronized (stateLock) {
                previousBusy = currentBusyStateLocked();
                resetPending = false;
                reinitializing = false;
                stateLock.notifyAll();
                currentBusy = currentBusyStateLocked();
                busyChanged = previousBusy != currentBusy;
            }
            if (busyChanged) {
                notifyBusyStateListeners(currentBusy);
            }
        }
    }
    
    /**
     * Load a configuration and its model if not already loaded.
     * This method is NOT thread-safe - caller must hold busy lock.
     * 
     * @param configName Configuration name to load
     * @return true if successful, false otherwise
     */
    public boolean loadConfiguration(String configName) {
        return loadConfiguration(configName, false, false);
    }

    public boolean loadConfiguration(String configName, boolean preferVisionProjector, boolean preferAudioProjector) {
        boolean shouldClearPendingLoad = false;
        try {
            ConfigurationManager.Configuration config = configManager.loadConfiguration(configName);
            DiagnosticsLogger.logEvent(context, "model-load", "Loading configuration: " + configName);
            
            // Extract filename from URL or imported local model reference
            String filename = extractFilenameFromUrl(config.modelUrl);
            if (filename == null || filename.isEmpty()) {
                Log.e(TAG, "Cannot determine filename from model reference: " + config.modelUrl);
                return false;
            }
            
            File destFile = new File(getModelStorageDir(), filename);
            String modelPath = destFile.getAbsolutePath();
            String resolvedMmprojPath = resolveMultimodalProjectorPath(
                    config,
                    preferVisionProjector,
                    preferAudioProjector);
            String mmprojPath = ensureMultimodalProjectorAvailable(config, resolvedMmprojPath);
            
            // If same model is already loaded, just re-apply parameters
            if (modelPath.equals(currentModelPath) && Objects.equals(mmprojPath, currentMmprojPath) && modelLoaded) {
                Log.i(TAG, "Same model already loaded, re-applying parameters: " + configName);
                applyConfiguration(config);
                currentConfigName = configName;
                DiagnosticsLogger.logMemorySnapshot(
                        context,
                        "model-load-skip",
                        "config=" + configName + " model already loaded");
                return true;
            }

            final boolean requiresModelInit =
                    !modelPath.equals(currentModelPath)
                            || !Objects.equals(mmprojPath, currentMmprojPath)
                            || !modelLoaded;
            
            if (listener != null) {
                listener.onModelLoading(configName);
            }

            if (requiresModelInit) {
                shouldClearPendingLoad = true;
                try {
                    PendingModelLoadStore.writePendingLoad(context, configName, modelPath, config.modelUrl);
                } catch (IOException | JSONException e) {
                    Log.e(TAG, "Failed to persist pending model load marker", e);
                    if (listener != null) {
                        listener.onError("Failed to persist pending model load marker: " + e.getMessage());
                    }
                    return false;
                }
            }

            String fileAvailabilityError = ensureModelFilesAvailable(config, destFile);
            if (fileAvailabilityError != null) {
                Log.e(TAG, fileAvailabilityError);
                if (listener != null) {
                    listener.onError(fileAvailabilityError);
                }
                return false;
            }

            if (requiresModelInit) {
                if (currentModelPath != null || modelLoaded) {
                    clearTransientLoadReferences();
                    unloadCurrentModelLocked();
                } else {
                    clearLoadedModelState();
                }

                prepareForLargeModelLoad(modelPath);
                applyLoadParameters(config, config.nCtx);
                String initResult = llama.initWithMmproj(modelPath, mmprojPath != null ? mmprojPath : "");
                if (!"ok".equals(initResult)) {
                    Log.e(TAG, "Model init failed: " + initResult);
                    if (listener != null) {
                        listener.onError("Model init failed: " + initResult);
                    }
                    return false;
                }

                currentModelPath = modelPath;
                boolean multimodalActive = llama.supportsVision() || llama.supportsAudio();
                if (!multimodalActive && mmprojPath != null) {
                    Log.i(TAG, "Projector request was not activated by native init; treating model as text-only");
                }
                currentMmprojPath = multimodalActive ? mmprojPath : null;
            }

            // Set parameters from configuration
            applyConfiguration(config);

            currentConfigName = configName;
            modelLoaded = true;

            if (listener != null) {
                listener.onModelLoaded(configName);
            }

            Log.i(TAG, "Configuration loaded: " + configName);
            DiagnosticsLogger.logMemorySnapshot(
                    context,
                    "model-load-complete",
                    "config=" + configName
                            + " model=" + new File(modelPath).getName()
                            + " mmproj=" + (currentMmprojPath != null ? new File(currentMmprojPath).getName() : "(none)"));
            return true;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load configuration: " + configName, e);
            if (listener != null) {
                listener.onError("Failed to load configuration: " + e.getMessage());
            }
            return false;
        } finally {
            if (shouldClearPendingLoad) {
                try {
                    PendingModelLoadStore.deletePendingLoad(context);
                } catch (IOException cleanupError) {
                    Log.e(TAG, "Failed to clear pending model load marker", cleanupError);
                }
            }
        }
    }

    /**
     * Force reinitialize a configuration even when the same model is already loaded.
     * Caller must already have exclusive access to the model state.
     *
     * @param configName Configuration name to reload
     * @return true if successful, false otherwise
     */
    public boolean reinitializeConfiguration(String configName) {
        String resolvedConfigName = normalizeConfigName(configName);
        Log.i(TAG, "Force reinitializing configuration: " + resolvedConfigName);

        if (currentModelPath != null || modelLoaded) {
            unloadCurrentModelLocked();
        } else {
            clearLoadedModelState();
        }

        return loadConfiguration(resolvedConfigName);
    }
    
    /**
     * Apply configuration parameters to the model.
     */
    public void applyConfiguration(ConfigurationManager.Configuration config) {
        llama.setParameters(
            config.penaltyLastN,
            (float)config.penaltyRepeat,
            (float)config.penaltyFreq,
            (float)config.penaltyPresent,
            config.mirostat,
            (float)config.mirostatTau,
            (float)config.mirostatEta,
            (float)config.minP,
            (float)config.typicalP,
            (float)config.dynatempRange,
            (float)config.dynatempExponent,
            (float)config.xtcProbability,
            (float)config.xtcThreshold,
            (float)config.topNSigma,
            (float)config.dryMultiplier,
            (float)config.dryBase,
            config.dryAllowedLength,
            config.dryPenaltyLastN,
            config.drySequenceBreakers
        );
    }

    private void applyLoadParameters(ConfigurationManager.Configuration config, int nCtxOverride) {
        int nCtx = safePositive(nCtxOverride > 0 ? nCtxOverride : config.nCtx, DEFAULT_N_CTX);
        int nThreads = safePositive(config.nThreads, DEFAULT_N_THREADS);
        int nBatch = safePositive(config.nBatch, DEFAULT_N_BATCH);
        float temp = safeFinite((float) config.temp, DEFAULT_TEMP);
        float topP = safeFinite((float) config.topP, DEFAULT_TOP_P);
        int topK = safePositive(config.topK, DEFAULT_TOP_K);
        int nGpuLayers = (config.gpuOffloadLayers < 0 || config.gpuOffloadLayers > 39) ? GPU_LAYERS_ENABLED_ALL : Math.max(0, config.gpuOffloadLayers);
        llama.setLoadParameters(nCtx, nThreads, nBatch, temp, topP, topK, nGpuLayers);
    }

    private int safePositive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private float safeFinite(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }
    
    /**
     * Generate response from prompt.
     * This method is NOT thread-safe - caller must hold busy lock.
     * 
     * @param prompt The prompt to generate from
     * @return Generated text or error message
     */
    public String generate(String prompt) {
        return generate(prompt, null);
    }

    public String generate(String prompt, byte[][] mediaFiles) {
        if (!modelLoaded) {
            return "Model not loaded";
        }
        
        if (listener != null) {
            listener.onGenerating(currentConfigName);
        }
        
        String result;
        int generationId = generationCounter.incrementAndGet();
        String loadedModelName = currentModelPath != null ? new File(currentModelPath).getName() : "(none)";
        int promptLength = prompt != null ? prompt.length() : 0;
        int mediaCount = mediaFiles != null ? mediaFiles.length : 0;
        DiagnosticsLogger.logMemorySnapshot(
                context,
                "generation-start",
                "id=" + generationId
                        + " config=" + currentConfigName
                        + " model=" + loadedModelName
                        + " promptLen=" + promptLength
                        + " mediaCount=" + mediaCount);
        DiagnosticsLogger.markGenerationInProgress(
                context,
                generationId,
                currentConfigName,
                loadedModelName,
                promptLength,
                mediaCount);
        DiagnosticsLogger.logEvent(context, "generation-stage", "id=" + generationId + " stage=native-call-start");
        try {
            result = (mediaFiles == null || mediaFiles.length == 0)
                    ? llama.generate(prompt)
                    : llama.generateWithMedia(prompt, mediaFiles);
            DiagnosticsLogger.logEvent(
                    context,
                    "generation-stage",
                    "id=" + generationId + " stage=native-call-end resultLen=" + (result != null ? result.length() : 0));
        } catch (Throwable t) {
            // Log full stack trace and notify listener so the server can respond gracefully
            Log.e(TAG, "Exception during generate", t);
            DiagnosticsLogger.logEvent(context, "generation-stage", "id=" + generationId + " stage=native-call-throw error=" + t);
            DiagnosticsLogger.logMemorySnapshot(
                    context,
                    "generation-error",
                    "id=" + generationId + " error=" + t);
            if (listener != null) {
                listener.onError("Generation exception: " + t.toString());
            }
            // Return a clear error string so API layer can send a proper error response
            return "generate failed: " + t.toString();
        } finally {
            DiagnosticsLogger.clearGenerationInProgress(context);
        }
        DiagnosticsLogger.logMemorySnapshot(
                context,
                "generation-end",
                "id=" + generationId + " resultLen=" + (result != null ? result.length() : 0));
        
        if (listener != null) {
            listener.onGenerationComplete(currentConfigName, result);
        }
        
        return result;
    }

    private void clearTransientLoadReferences() {
        try {
            llama.setTokenListener(null);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to clear token listener before model load", t);
        }
    }

    private void prepareForLargeModelLoad(String modelPath) {
        clearTransientLoadReferences();
        logRuntimeMemory("Before model load", modelPath);

        Runtime runtime = Runtime.getRuntime();
        runtime.runFinalization();
        runtime.gc();
        System.gc();
        System.runFinalization();
        runtime.gc();

        logRuntimeMemory("After model load cleanup", modelPath);
    }

    private void logRuntimeMemory(String phase, String modelPath) {
        Runtime runtime = Runtime.getRuntime();
        long javaUsed = runtime.totalMemory() - runtime.freeMemory();
        long javaTotal = runtime.totalMemory();
        long javaMax = runtime.maxMemory();
        long nativeHeap = Debug.getNativeHeapAllocatedSize();

        Log.i(TAG, phase
                + " for " + new File(modelPath).getName()
                + " | javaUsed=" + formatBytes(javaUsed)
                + " javaTotal=" + formatBytes(javaTotal)
                + " javaMax=" + formatBytes(javaMax)
                + " nativeHeap=" + formatBytes(nativeHeap));
        DiagnosticsLogger.logMemorySnapshot(
                context,
                "runtime-memory",
                phase + " model=" + new File(modelPath).getName());
    }

    private String formatBytes(long bytes) {
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        return String.format(Locale.US, "%.1f%s", value, units[unitIndex]);
    }

    private boolean currentBusyStateLocked() {
        return busy.get() || resetPending || reinitializing;
    }

    private void notifyBusyStateIfChanged(boolean previousBusy, boolean currentBusy) {
        if (previousBusy != currentBusy) {
            notifyBusyStateListeners(currentBusy);
        }
    }

    private void notifyBusyStateListeners(boolean busyState) {
        for (BusyStateListener listener : busyStateListeners) {
            listener.onBusyStateChanged(busyState);
        }
    }

    /**
     * Free the model resources.
     */
    public void free() {
        boolean previousBusy;
        boolean currentBusy;
        synchronized (stateLock) {
            if (busy.get() || resetPending || reinitializing) {
                return;
            }
            previousBusy = currentBusyStateLocked();
            busy.set(true);
            currentBusy = currentBusyStateLocked();
        }
        notifyBusyStateIfChanged(previousBusy, currentBusy);
        try {
            if (currentModelPath != null || modelLoaded) {
                unloadCurrentModelLocked();
            } else {
                clearLoadedModelState();
            }
        } finally {
            release();
        }
    }

    private void unloadCurrentModelLocked() {
        try {
            llama.free();
        } finally {
            clearLoadedModelState();
        }
    }

    private void clearLoadedModelState() {
        currentModelPath = null;
        currentMmprojPath = null;
        currentConfigName = null;
        modelLoaded = false;
    }

    private String normalizeConfigName(String configName) {
        return (configName == null || configName.trim().isEmpty())
                ? DEFAULT_CONFIG_NAME
                : configName.trim();
    }
    
    private String extractFilenameFromUrl(String url) {
        return ModelFileHelper.extractFilename(url);
    }

    private File getModelStorageDir() {
        return ModelFileHelper.getModelStorageDir(context);
    }

    private String ensureModelFilesAvailable(ConfigurationManager.Configuration config, File destFile) {
        boolean needsDownload = !destFile.exists() || destFile.length() == 0;
        String missingShardPath = findMissingSplitShardPath(destFile);

        if (!needsDownload && missingShardPath != null) {
            Log.w(TAG, "Incomplete split model detected, repairing download. Missing: " + missingShardPath);
            needsDownload = true;
        }

        if (needsDownload) {
            if (!ModelFileHelper.isRemoteModelReference(config.modelUrl)) {
                if (missingShardPath != null) {
                    return "Incomplete imported split model, missing file: " + missingShardPath;
                }
                return "Imported model file not found: " + destFile.getAbsolutePath();
            }

            if (config.modelUrl != null && config.modelUrl.regionMatches(true, 0, "https://", 0, 8)) {
                String trustStoreError = configureNativeDownloadTrustStore();
                if (trustStoreError != null) {
                    return trustStoreError;
                }
            }
            Log.i(TAG, "Downloading model from: " + config.modelUrl);
            String downloadResult = llama.download(config.modelUrl, destFile.getAbsolutePath());
            if (!"ok".equals(downloadResult)) {
                return "Download failed: " + downloadResult;
            }
        }

        missingShardPath = findMissingSplitShardPath(destFile);
        if (missingShardPath != null) {
            return "Incomplete split model download, missing file: " + missingShardPath;
        }
        if (!destFile.exists() || destFile.length() == 0) {
            return "Model file missing after download: " + destFile.getAbsolutePath();
        }

        return null;
    }

    private String resolveMultimodalProjectorPath(
            ConfigurationManager.Configuration config,
            boolean preferVisionProjector,
            boolean preferAudioProjector) {
        if (config.multimodalProjectorUrl != null && !config.multimodalProjectorUrl.trim().isEmpty()) {
            if (ModelFileHelper.canAutoApplyProjectorReference(config.modelUrl, config.multimodalProjectorUrl)) {
                File configuredFile = ModelFileHelper.resolveStoredModelFile(context, config.multimodalProjectorUrl);
                return configuredFile != null ? configuredFile.getAbsolutePath() : null;
            }
            Log.w(TAG, "Ignoring stale or incompatible multimodal projector for model: " + config.modelUrl);
        }

        File autoDetected = ModelFileHelper.findAutoDetectedMultimodalProjectorFile(
                context,
                config.modelUrl,
                preferVisionProjector,
                preferAudioProjector);
        if (autoDetected != null) {
            Log.i(TAG, "Auto-detected multimodal projector: " + autoDetected.getAbsolutePath());
            return autoDetected.getAbsolutePath();
        }
        return null;
    }

    private String ensureMultimodalProjectorAvailable(
            ConfigurationManager.Configuration config,
            String mmprojPath) {
        if (mmprojPath == null || mmprojPath.isEmpty()) {
            return null;
        }

        File mmprojFile = new File(mmprojPath);
        if (mmprojFile.exists() && mmprojFile.length() > 0) {
            return mmprojFile.getAbsolutePath();
        }

        String mmprojReference = config.multimodalProjectorUrl;
        if (mmprojReference == null || mmprojReference.trim().isEmpty()) {
            Log.w(TAG, "Auto-detected multimodal projector file not available: " + mmprojPath);
            return null;
        }

        if (!ModelFileHelper.isRemoteModelReference(mmprojReference)) {
            Log.w(TAG, "Imported multimodal projector file not found: " + mmprojPath);
            return null;
        }

        if (mmprojReference.regionMatches(true, 0, "https://", 0, 8)) {
            String trustStoreError = configureNativeDownloadTrustStore();
            if (trustStoreError != null) {
                Log.w(TAG, "Skipping multimodal projector download: " + trustStoreError);
                return null;
            }
        }

        Log.i(TAG, "Downloading multimodal projector from: " + mmprojReference);
        String downloadResult = llama.download(mmprojReference, mmprojFile.getAbsolutePath());
        if (!"ok".equals(downloadResult)) {
            Log.w(TAG, "Multimodal projector download failed: " + downloadResult);
            return null;
        }
        if (!mmprojFile.exists() || mmprojFile.length() == 0) {
            Log.w(TAG, "Multimodal projector still unavailable after download: " + mmprojFile.getAbsolutePath());
            return null;
        }
        return mmprojFile.getAbsolutePath();
    }

    private synchronized String configureNativeDownloadTrustStore() {
        File caBundleFile = new File(context.getFilesDir(), DOWNLOAD_CA_BUNDLE_FILENAME);
        try {
            writeAndroidCaBundle(caBundleFile);
            llama.setDownloadCaBundlePath(caBundleFile.getAbsolutePath());
            return null;
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to prepare native HTTPS trust store", e);
            return "Could not prepare HTTPS trust store: " + e.getMessage();
        }
    }

    private void writeAndroidCaBundle(File outputFile) throws IOException, GeneralSecurityException {
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create CA bundle directory: " + parent.getAbsolutePath());
        }

        KeyStore keyStore = KeyStore.getInstance("AndroidCAStore");
        keyStore.load(null, null);

        int certificateCount = 0;
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outputFile, false), StandardCharsets.US_ASCII))) {
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate certificate = keyStore.getCertificate(alias);
                if (!(certificate instanceof X509Certificate)) {
                    continue;
                }

                writePemCertificate(writer, (X509Certificate) certificate);
                certificateCount++;
            }
        }

        if (certificateCount == 0) {
            throw new GeneralSecurityException("Android CA store is empty");
        }
    }

    private void writePemCertificate(Writer writer, X509Certificate certificate)
            throws IOException, CertificateEncodingException {
        writer.write("-----BEGIN CERTIFICATE-----\n");

        String base64 = android.util.Base64.encodeToString(
                certificate.getEncoded(),
                android.util.Base64.NO_WRAP
        );
        for (int start = 0; start < base64.length(); start += 64) {
            int end = Math.min(start + 64, base64.length());
            writer.write(base64, start, end - start);
            writer.write('\n');
        }

        writer.write("-----END CERTIFICATE-----\n");
    }

    private String findMissingSplitShardPath(File modelFile) {
        if (modelFile == null) {
            return null;
        }

        Matcher matcher = SPLIT_GGUF_PATTERN.matcher(modelFile.getName());
        if (!matcher.matches()) {
            return null;
        }

        final int splitNo;
        final int splitCount;
        try {
            splitNo = Integer.parseInt(matcher.group(2));
            splitCount = Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException e) {
            Log.w(TAG, "Could not parse split model name: " + modelFile.getName(), e);
            return null;
        }

        if (splitNo <= 0 || splitCount <= 1 || splitNo > splitCount) {
            return null;
        }

        File parentDir = modelFile.getParentFile();
        if (parentDir == null) {
            return modelFile.getAbsolutePath();
        }

        String prefix = matcher.group(1);
        for (int idx = 1; idx <= splitCount; idx++) {
            File shardFile = new File(parentDir, String.format(Locale.US, "%s-%05d-of-%05d.gguf", prefix, idx, splitCount));
            if (!shardFile.exists() || shardFile.length() <= 0) {
                return shardFile.getAbsolutePath();
            }
        }

        return null;
    }
}
