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

    public synchronized ModelFileHelper.InferredModalities getAdvertisedModalities(String configName) {
        final String resolvedConfigName = normalizeConfigName(configName);
        final ConfigurationManager.Configuration config;
        try {
            config = configManager.loadConfiguration(resolvedConfigName);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Failed to load configuration for modality advertisement: " + resolvedConfigName, e);
            return new ModelFileHelper.InferredModalities(false, false);
        }

        if (config.modelUrl == null || config.modelUrl.trim().isEmpty()) {
            return new ModelFileHelper.InferredModalities(false, false);
        }

        boolean supportsVision = false;
        boolean supportsAudio = false;
        MultimodalProjectorResolution projectorResolution =
                resolveMultimodalProjectorPath(config, false, false);
        String configuredProjectorPath = projectorResolution.errorMessage == null
                ? projectorResolution.projectorPath
                : null;
        if (configuredProjectorPath != null) {
            ModelFileHelper.InferredModalities inferredSupport =
                    ModelFileHelper.inferAutoDetectedModalities(context, config.modelUrl);
            supportsVision = inferredSupport.supportsVision();
            supportsAudio = inferredSupport.supportsAudio();
        }

        File configuredModelFile = ModelFileHelper.resolveStoredModelFile(context, config.modelUrl);
        if (configuredModelFile != null
                && modelLoaded
                && configuredModelFile.getAbsolutePath().equals(currentModelPath)
                && Objects.equals(configuredProjectorPath, currentConfiguredMmprojPath)) {
            supportsVision |= currentSupportsVision;
            supportsAudio |= currentSupportsAudio;
        }

        return new ModelFileHelper.InferredModalities(supportsVision, supportsAudio);
    }
    private static final String TAG = "ModelManager";
    private static final String DEFAULT_CONFIG_NAME = "default";
    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_LOG_LEVEL = "log_level";
    private static final int DEFAULT_LOG_LEVEL_INFO = 2;
    private static final int DEFAULT_N_CTX = 2048;
    private static final int DEFAULT_N_THREADS = 2;
    private static final int DEFAULT_N_BATCH = ConfigurationManager.Configuration.DEFAULT_N_BATCH;
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
    private volatile ConfigurationManager.Configuration lastLoadedConfig = null;
    private volatile String currentConfiguredMmprojPath = null;
    // Backend config the currently-loaded model was built with. A change here must
    // force a model reload (tensors are placed on the accelerator at load time).
    private volatile int currentBackendType = -1;
    private volatile boolean currentNpuEnabled = false;
    private volatile int currentGpuOffloadLayers = Integer.MIN_VALUE;
    // Set when settings are saved: force a model reload on the next load so any config
    // change (incl. GPU/NPU backend) takes effect for both direct and API runs.
    private volatile boolean reloadRequested = false;
    private volatile String currentMmprojPath = null;
    private volatile boolean currentSupportsVision = false;
    private volatile boolean currentSupportsAudio = false;
    private volatile boolean modelLoaded = false;

    private static final class MultimodalProjectorResolution {
        private final String projectorPath;
        private final String errorMessage;

        private MultimodalProjectorResolution(String projectorPath, String errorMessage) {
            this.projectorPath = projectorPath;
            this.errorMessage = errorMessage;
        }
    }

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
        File logFile = DiagnosticsLogger.getOllamaLogFile(this.context);
        try {
            if (logFile != null) {
                llama.setLogPath(logFile.getAbsolutePath());
            }
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

    public ConfigurationManager.Configuration getCurrentConfig() {
        return lastLoadedConfig;
    }
    
    public String getCurrentModelPath() {
        return currentModelPath;
    }

    /** Force the next load() to actually reload (e.g. after settings are saved). */
    public void requestReloadOnNextLoad() {
        reloadRequested = true;
    }

    public boolean isLoadedConfigurationMatching(ConfigurationManager.Configuration config) {
        if (reloadRequested) {
            return false;   // settings changed -> reload to apply (backend, params, etc.)
        }
        if (config == null) {
            return true;
        }
        File configuredModelFile = ModelFileHelper.resolveStoredModelFile(context, config.modelUrl);
        if (configuredModelFile == null) {
            return true;
        }
        MultimodalProjectorResolution projectorResolution = resolveMultimodalProjectorPath(config, false, false);
        if (projectorResolution.errorMessage != null) {
            return false;
        }
        synchronized (stateLock) {
            return modelLoaded
                    && configuredModelFile.getAbsolutePath().equals(currentModelPath)
                    && Objects.equals(projectorResolution.projectorPath, currentConfiguredMmprojPath)
                    // バックエンド設定が変わったら別物として再ロードさせる (NPU/GPU はロード時にしか効かない)
                    && config.backendType == currentBackendType
                    && config.npuEnabled == currentNpuEnabled
                    && config.gpuOffloadLayers == currentGpuOffloadLayers;
        }
    }

    public boolean supportsVision() {
        return modelLoaded && currentSupportsVision;
    }

    public boolean supportsAudio() {
        return modelLoaded && currentSupportsAudio;
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

    public boolean downloadConfigurationAssets(String configName) {
        boolean shouldClearPendingLoad = false;
        try {
            ConfigurationManager.Configuration config = configManager.loadConfiguration(configName);
            DiagnosticsLogger.logEvent(context, "model-download", "Downloading configuration assets: " + configName);

            String filename = extractFilenameFromUrl(config.modelUrl);
            if (filename == null || filename.isEmpty()) {
                Log.e(TAG, "Cannot determine filename from model reference: " + config.modelUrl);
                return false;
            }

            File destFile = new File(getModelStorageDir(), filename);
            String modelPath = destFile.getAbsolutePath();
            MultimodalProjectorResolution projectorResolution = resolveMultimodalProjectorPath(
                    config,
                    false,
                    false);
            if (projectorResolution.errorMessage != null) {
                Log.e(TAG, projectorResolution.errorMessage);
                if (listener != null) {
                    listener.onError(projectorResolution.errorMessage);
                }
                return false;
            }

            try {
                PendingModelLoadStore.writePendingLoad(context, configName, modelPath, config.modelUrl);
                shouldClearPendingLoad = true;
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Failed to persist pending model load marker", e);
                if (listener != null) {
                    listener.onError("Failed to persist pending model load marker: " + e.getMessage());
                }
                return false;
            }

            MultimodalProjectorResolution availableProjector = ensureMultimodalProjectorAvailable(
                    config,
                    projectorResolution.projectorPath);
            if (availableProjector.errorMessage != null) {
                Log.e(TAG, availableProjector.errorMessage);
                if (listener != null) {
                    listener.onError(availableProjector.errorMessage);
                }
                return false;
            }

            String fileAvailabilityError = ensureModelFilesAvailable(config, destFile);
            if (fileAvailabilityError != null) {
                Log.e(TAG, fileAvailabilityError);
                if (listener != null) {
                    listener.onError(fileAvailabilityError);
                }
                return false;
            }

            DiagnosticsLogger.logMemorySnapshot(
                    context,
                    "model-download-complete",
                    "config=" + configName
                            + " model=" + destFile.getName()
                            + " mmproj=" + (availableProjector.projectorPath != null
                            ? new File(availableProjector.projectorPath).getName()
                            : "(none)"));
            return true;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to download configuration assets", e);
            if (listener != null) {
                listener.onError("Failed to download configuration assets: " + e.getMessage());
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
            MultimodalProjectorResolution projectorResolution = resolveMultimodalProjectorPath(
                    config,
                    preferVisionProjector,
                    preferAudioProjector);
            if (projectorResolution.errorMessage != null) {
                Log.e(TAG, projectorResolution.errorMessage);
                if (listener != null) {
                    listener.onError(projectorResolution.errorMessage);
                }
                return false;
            }
            MultimodalProjectorResolution availableProjector = ensureMultimodalProjectorAvailable(
                    config,
                    projectorResolution.projectorPath);
            if (availableProjector.errorMessage != null) {
                Log.e(TAG, availableProjector.errorMessage);
                if (listener != null) {
                    listener.onError(availableProjector.errorMessage);
                }
                return false;
            }
            String mmprojPath = availableProjector.projectorPath;
            boolean enableAudioForLoad = mmprojPath != null;
            boolean projectorRequestedButInactive =
                    mmprojPath != null && !currentSupportsVision && !currentSupportsAudio;

            // A backend/offload change (or a settings save) requires a FULL reload, because
            // tensor placement and the accelerator are decided at model-build time —
            // re-applying parameters alone does not move the model onto the new backend.
            boolean backendMatches = currentBackendType == config.backendType
                    && currentNpuEnabled == config.npuEnabled
                    && currentGpuOffloadLayers == config.gpuOffloadLayers
                    && !reloadRequested;

            // If same model is already loaded AND the backend is unchanged, just re-apply parameters
            if (modelPath.equals(currentModelPath)
                    && Objects.equals(mmprojPath, currentConfiguredMmprojPath)
                    && modelLoaded
                    && backendMatches
                    && !projectorRequestedButInactive
                    && (!enableAudioForLoad || currentSupportsAudio)) {
                Log.i(TAG, "Same model already loaded, re-applying parameters: " + configName);
                applyConfiguration(config);
                currentConfigName = configName;
                lastLoadedConfig = config;
                DiagnosticsLogger.logMemorySnapshot(
                        context,
                        "model-load-skip",
                        "config=" + configName
                                + " model already loaded"
                                + " audioRequested=" + enableAudioForLoad
                                + " audioActive=" + currentSupportsAudio
                                + " projectorActive=" + (currentSupportsVision || currentSupportsAudio));
                return true;
            }

            final boolean requiresModelInit =
                    !modelPath.equals(currentModelPath)
                            || !Objects.equals(mmprojPath, currentConfiguredMmprojPath)
                            || !modelLoaded
                            || !backendMatches
                            || projectorRequestedButInactive
                            || (enableAudioForLoad && !currentSupportsAudio);

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
                String initResult = llama.initWithMmproj(
                        modelPath,
                        mmprojPath != null ? mmprojPath : "",
                        enableAudioForLoad);
                if (!"ok".equals(initResult)) {
                    Log.e(TAG, "Model init failed: " + initResult);
                    if (listener != null) {
                        listener.onError("Model init failed: " + initResult);
                    }
                    return false;
                }

                currentModelPath = modelPath;
                currentConfiguredMmprojPath = mmprojPath;
                currentSupportsVision = llama.supportsVision();
                currentSupportsAudio = llama.supportsAudio();
                boolean multimodalActive = currentSupportsVision || currentSupportsAudio;
                if (!multimodalActive && mmprojPath != null) {
                    Log.i(TAG, "Projector request was not activated by native init; treating model as text-only");
                } else if (mmprojPath != null && !enableAudioForLoad && currentSupportsVision && !currentSupportsAudio) {
                    Log.i(TAG, "Projector initialized in vision-only mode; audio encoder will be loaded on demand");
                }
                currentMmprojPath = multimodalActive ? mmprojPath : null;
            }

            // Set parameters from configuration
            applyConfiguration(config);

            currentConfigName = configName;
            lastLoadedConfig = config;
            modelLoaded = true;
            // この設定でモデルを構築したことを記録 (再ロード判定に使用)
            currentBackendType = config.backendType;
            currentNpuEnabled = config.npuEnabled;
            currentGpuOffloadLayers = config.gpuOffloadLayers;
            reloadRequested = false;   // 反映済み

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
        // GPU/NPU backend を選択した場合は n_gpu_layers を有効化。
        // CPU のみ (backendType=0) の場合は強制 0。
        int nGpuLayers;
        if (config.backendType == ConfigurationManager.Configuration.BACKEND_CPU) {
            nGpuLayers = 0;
        } else {
            nGpuLayers = (config.gpuOffloadLayers < 0 || config.gpuOffloadLayers > 39)
                       ? GPU_LAYERS_ENABLED_ALL
                       : Math.max(0, config.gpuOffloadLayers);
        }
        llama.setLoadParameters(nCtx, nThreads, nBatch, temp, topP, topK, nGpuLayers);

        // Compute backend を JNI へ通知 (ADSP_LIBRARY_PATH 設定含む)
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        llama.setBackendConfig(config.backendType, config.npuEnabled, nativeLibDir);
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
        currentConfiguredMmprojPath = null;
        currentMmprojPath = null;
        currentSupportsVision = false;
        currentSupportsAudio = false;
        currentConfigName = null;
        currentBackendType = -1;
        currentNpuEnabled = false;
        currentGpuOffloadLayers = Integer.MIN_VALUE;
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

    private MultimodalProjectorResolution resolveMultimodalProjectorPath(
            ConfigurationManager.Configuration config,
            boolean preferVisionProjector,
            boolean preferAudioProjector) {
        if (config.multimodalProjectorUrl == null || config.multimodalProjectorUrl.trim().isEmpty()) {
            File autoDetectedProjector = ModelFileHelper.findAutoDetectedMultimodalProjectorFile(
                    context,
                    config.modelUrl,
                    preferVisionProjector,
                    preferAudioProjector);
            return new MultimodalProjectorResolution(
                    autoDetectedProjector != null ? autoDetectedProjector.getAbsolutePath() : null,
                    null);
        }

        if (!config.multimodalProjectorManualSelection
                && !ModelFileHelper.canAutoApplyProjectorReference(config.modelUrl, config.multimodalProjectorUrl)) {
            return new MultimodalProjectorResolution(
                    null,
                    "Configured mmproj is incompatible with the selected model. Open Settings and choose a matching mmproj.");
        }

        File configuredFile = ModelFileHelper.resolveStoredModelFile(context, config.multimodalProjectorUrl);
        if (configuredFile == null) {
            return new MultimodalProjectorResolution(
                    null,
                    "Configured mmproj could not be resolved. Open Settings and choose the mmproj again.");
        }
        return new MultimodalProjectorResolution(configuredFile.getAbsolutePath(), null);
    }

    private MultimodalProjectorResolution ensureMultimodalProjectorAvailable(
            ConfigurationManager.Configuration config,
            String mmprojPath) {
        if (mmprojPath == null || mmprojPath.isEmpty()) {
            return new MultimodalProjectorResolution(null, null);
        }

        File mmprojFile = new File(mmprojPath);
        if (mmprojFile.exists() && mmprojFile.length() > 0) {
            return new MultimodalProjectorResolution(mmprojFile.getAbsolutePath(), null);
        }

        String mmprojReference = config.multimodalProjectorUrl;
        if (mmprojReference == null || mmprojReference.trim().isEmpty()) {
            return new MultimodalProjectorResolution(null, null);
        }

        if (!ModelFileHelper.isRemoteModelReference(mmprojReference)) {
            return new MultimodalProjectorResolution(
                    null,
                    "Configured mmproj file is missing: " + mmprojFile.getAbsolutePath());
        }

        if (mmprojReference.regionMatches(true, 0, "https://", 0, 8)) {
            String trustStoreError = configureNativeDownloadTrustStore();
            if (trustStoreError != null) {
                return new MultimodalProjectorResolution(
                        null,
                        "Failed to prepare mmproj download: " + trustStoreError);
            }
        }

        Log.i(TAG, "Downloading multimodal projector from: " + mmprojReference);
        String downloadResult = llama.download(mmprojReference, mmprojFile.getAbsolutePath());
        if (!"ok".equals(downloadResult)) {
            return new MultimodalProjectorResolution(
                    null,
                    "Configured mmproj download failed: " + downloadResult);
        }
        if (!mmprojFile.exists() || mmprojFile.length() == 0) {
            return new MultimodalProjectorResolution(
                    null,
                    "Configured mmproj is unavailable after download: " + mmprojFile.getAbsolutePath());
        }
        return new MultimodalProjectorResolution(mmprojFile.getAbsolutePath(), null);
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
