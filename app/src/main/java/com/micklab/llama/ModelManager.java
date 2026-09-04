package com.micklab.llama;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Debug;
import android.os.PowerManager;
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
    private static final String DOWNLOAD_WAKE_LOCK_TAG = "llama:model-download";
    // Safety cap so a stuck/native-hung download can never hold the CPU awake forever.
    private static final long DOWNLOAD_WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L;
    
    private static ModelManager instance;
    
    private final Context context;
    private final LlamaNative llama;
    private final ConfigurationManager configManager;
    private final AtomicInteger generationCounter = new AtomicInteger(0);
    // Incremented whenever the model is fully (re)initialized. The WebUI reads this via
    // /props webui_settings.settings_version and resets its settings to the app defaults
    // on any change, then tracks the version per session to preserve user overrides afterwards.
    private final AtomicInteger modelLoadVersion = new AtomicInteger(0);
    // Incremented on model load AND on settings changes that affect the WebUI (e.g. enableThinking).
    // /props exposes this as settings_version so the WebUI can detect either kind of change.
    private final AtomicInteger webUiVersion = new AtomicInteger(0);

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
    // use_mmap is a model-build parameter (like the backend/offload above): it only takes effect at
    // init, so a change must force a full reload rather than the re-apply-parameters fast path.
    // Defaults to the native g_use_mmap default (true).
    private volatile boolean currentUseMmap = true;
    // KV cache quantization is likewise applied at init only, so a change must also force a reload.
    // Integer.MIN_VALUE = "unknown" so the first load always initializes.
    private volatile int currentKvCacheTypeK = Integer.MIN_VALUE;
    private volatile int currentKvCacheTypeV = Integer.MIN_VALUE;
    // Set when settings are saved: force a model reload on the next load so any config
    // change (incl. GPU/NPU backend) takes effect for both direct and API runs.
    private volatile boolean reloadRequested = false;
    // n_ctx actually used by the currently loaded llama_context.
    private volatile int currentNCtx = 0;
    // Per-request n_ctx override from options.num_ctx; 0 = use config value.
    private volatile int nCtxOverrideForNextLoad = 0;

    // ---- n_ctx auto-promotion (Q2) budget ----
    // The Android/Play per-app memory governor force-stops the process when anon RSS + swap
    // exceeds ~3 GB. On CPU the KV cache is anonymous memory, so n_ctx auto-promotion must stay
    // under that budget; on GPU the KV lives in an OpenCL buffer (not anon) and is bounded by
    // VRAM instead. Verified on-device: CPU Qwen3VL-2B @ n_ctx=8192 peaks anonPlusSwap≈2.5 GB.
    private static final long CTX_PROMOTE_ANON_BUDGET_BYTES = 3L * 1024 * 1024 * 1024; // 3 GB policy
    private static final long CTX_PROMOTE_ANON_SAFETY_BYTES = 512L * 1024 * 1024;      // headroom
    // Fallback CPU non-KV baseline when a live anon reading is unavailable (measured ~1.7 GB for a
    // 2B Q4 model + clip); conservative so promotion never over-commits.
    private static final long CTX_PROMOTE_CPU_BASELINE_FALLBACK_BYTES = 1800L * 1024 * 1024;
    // GPU KV ceiling: OpenCL single-buffer max alloc is ~2 GB and VRAM here is ~10 GB, so a 3 GB
    // KV budget is comfortable while still finite.
    private static final long CTX_PROMOTE_GPU_KV_BUDGET_BYTES = 3L * 1024 * 1024 * 1024;
    private static final int CTX_PROMOTE_MAX_NCTX = 32768;
    // Machine-parseable native error prefix emitted by the multimodal context-fit pre-check (Q1).
    private static final Pattern CTX_TOO_SMALL_PATTERN =
            Pattern.compile("CTX_TOO_SMALL need=(\\d+) have=(\\d+)");
    // Text-path context overflow errors already carry the needed token count in prose.
    private static final Pattern CTX_TEXT_NEEDS_PATTERN =
            Pattern.compile("needs (\\d+) tokens but n_ctx=(\\d+)");
    private static final Pattern CTX_TEXT_NOROOM_PATTERN =
            Pattern.compile("prompt \\((\\d+) tokens\\) leaves no room in context n_ctx=(\\d+)");
    private volatile String currentMmprojPath = null;
    private volatile boolean currentSupportsVision = false;
    private volatile boolean currentSupportsAudio = false;
    private volatile boolean modelLoaded = false;
    // Filename of an mmproj that the last load() disabled for being incompatible (request #6),
    // or null. The ModelManager.ModelListener is currently unused, so the UI consumes this
    // after loadConfiguration() returns to show the user a message.
    private volatile String lastDisabledMmprojMessage = null;

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

    /** Version counter that increments each time the model is fully (re)initialized. */
    public int getModelLoadVersion() {
        return modelLoadVersion.get();
    }

    /** Increment the WebUI settings version due to a settings change (not a model reload). */
    public void notifySettingsChanged() {
        webUiVersion.incrementAndGet();
    }

    public int getWebUiVersion() {
        return webUiVersion.get();
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

    /**
     * True when the model file for the given reference is already present on the device
     * (or the reference is empty, i.e. there is nothing to download). A remote URL that has
     * not been fetched yet returns false — used to warn before the first large download.
     */
    public boolean isModelReferenceDownloaded(String modelReference) {
        if (modelReference == null || modelReference.trim().isEmpty()) {
            return true;
        }
        File file = ModelFileHelper.resolveStoredModelFile(context, modelReference);
        return file != null && file.exists();
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

    /**
     * Returns (and clears) the filename of an mmproj that the most recent load disabled for being
     * incompatible with the model, or null if none. Lets the UI inform the user (request #6).
     */
    public String consumeLastDisabledMmprojMessage() {
        String message = lastDisabledMmprojMessage;
        lastDisabledMmprojMessage = null;
        return message;
    }

    /** Force the next load() to actually reload (e.g. after settings are saved). */
    public void requestReloadOnNextLoad() {
        reloadRequested = true;
    }

    /** n_ctx of the currently loaded llama_context (0 if nothing is loaded yet). */
    public int getCurrentNCtx() {
        return currentNCtx;
    }

    /**
     * Override n_ctx for the next loadConfiguration() call.
     * If the requested value differs from the loaded context, a model reload is triggered
     * automatically so the new context window takes effect immediately.
     */
    public void setNCtxOverrideForNextLoad(int nCtx) {
        if (nCtx > 0) {
            nCtxOverrideForNextLoad = nCtx;
            if (nCtx != currentNCtx) {
                reloadRequested = true;
            }
        }
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
                    && config.gpuOffloadLayers == currentGpuOffloadLayers
                    // use_mmap / KV cache 量子化も同様にロード時にしか効かないため、変更時は再ロードが必要
                    && config.useMmap == currentUseMmap
                    && config.kvCacheTypeK == currentKvCacheTypeK
                    && config.kvCacheTypeV == currentKvCacheTypeV;
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
        return downloadConfigurationAssets(configName, true);
    }

    public boolean downloadConfigurationAssets(String configName, boolean allowProjectorDownload) {
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
                    projectorResolution.projectorPath,
                    allowProjectorDownload);
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
        return loadConfiguration(configName, preferVisionProjector, preferAudioProjector, true);
    }

    public boolean loadConfiguration(
            String configName,
            boolean preferVisionProjector,
            boolean preferAudioProjector,
            boolean allowProjectorDownload) {
        lastDisabledMmprojMessage = null;
        boolean shouldClearPendingLoad = false;
        try {
            ConfigurationManager.Configuration config = configManager.loadConfiguration(configName);
            // Apply per-request n_ctx override (from options.num_ctx) if set.
            if (nCtxOverrideForNextLoad > 0) {
                config.nCtx = nCtxOverrideForNextLoad;
                nCtxOverrideForNextLoad = 0;
            }
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
                    projectorResolution.projectorPath,
                    allowProjectorDownload);
            if (availableProjector.errorMessage != null) {
                Log.e(TAG, availableProjector.errorMessage);
                if (listener != null) {
                    listener.onError(availableProjector.errorMessage);
                }
                return false;
            }
            String mmprojPath = availableProjector.projectorPath;

            // Guard against an incompatible mmproj that would crash native clip init
            // (GGML_ASSERT -> abort, which cannot be caught in Java). When the cheap
            // metadata pre-check is confident the projector does not fit this model,
            // disable it, load the model text-only, and tell the user. (request #6)
            if (mmprojPath != null) {
                String mmprojValidation = llama.validateMmproj(modelPath, mmprojPath);
                if (mmprojValidation != null && mmprojValidation.startsWith("incompatible")) {
                    String disabledMmprojName = new File(mmprojPath).getName();
                    Log.w(TAG, "Disabling incompatible mmproj (" + mmprojValidation + "): " + mmprojPath);
                    DiagnosticsLogger.logEvent(context, "mmproj-incompatible",
                            "config=" + configName + " mmproj=" + disabledMmprojName + " reason=" + mmprojValidation);
                    // Surfaced to the UI after this call returns (the ModelListener is unused).
                    lastDisabledMmprojMessage = disabledMmprojName;
                    if (listener != null) {
                        listener.onError("Selected mmproj is incompatible with this model and was disabled;"
                                + " loaded text-only (" + disabledMmprojName + ")");
                    }
                    mmprojPath = null;
                }
            }

            boolean enableAudioForLoad = mmprojPath != null;
            boolean projectorRequestedButInactive =
                    mmprojPath != null && !currentSupportsVision && !currentSupportsAudio;

            // A backend/offload change (or a settings save) requires a FULL reload, because
            // tensor placement and the accelerator are decided at model-build time —
            // re-applying parameters alone does not move the model onto the new backend.
            boolean backendMatches = currentBackendType == config.backendType
                    && currentNpuEnabled == config.npuEnabled
                    && currentGpuOffloadLayers == config.gpuOffloadLayers
                    // use_mmap / KV cache quantization only apply at init, so a change requires a full
                    // reload, not the re-apply-parameters fast path — otherwise the new value is
                    // silently ignored.
                    && currentUseMmap == config.useMmap
                    && currentKvCacheTypeK == config.kvCacheTypeK
                    && currentKvCacheTypeV == config.kvCacheTypeV
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
                // GPU backend switch stabilization: insert pre-reset wait to reduce crash rate.
                int stabilLevel = config.gpuSwitchStabilization;
                boolean backendChanged = currentBackendType != config.backendType
                        || currentGpuOffloadLayers != config.gpuOffloadLayers;
                if (stabilLevel > 0 && backendChanged && (currentModelPath != null || modelLoaded)) {
                    int waitMs = stabilLevel == 1 ? 200 : stabilLevel == 2 ? 500 : 1000;
                    Log.i(TAG, "GPU stabilization level=" + stabilLevel + " waiting " + waitMs + "ms before reload");
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }

                if (currentModelPath != null || modelLoaded) {
                    clearTransientLoadReferences();
                    unloadCurrentModelLocked();
                    if (stabilLevel >= 2 && backendChanged) {
                        int postFreeWaitMs = stabilLevel == 2 ? 300 : 500;
                        Log.i(TAG, "GPU stabilization post-free wait " + postFreeWaitMs + "ms");
                        try { Thread.sleep(postFreeWaitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
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
                    // GPU→CPU auto-fallback (general, not vision-specific): a GPU model init can fail
                    // for any model due to OpenCL buffer allocation / kernel compilation / VRAM. Note
                    // the "GPU device not found" case already falls back inside native build_model_params
                    // and returns "ok"; this handles a *present* GPU that fails to initialize. Retry
                    // once on pure CPU so the model still loads instead of leaving the user stuck.
                    boolean gpuWasRequested =
                            config.backendType != ConfigurationManager.Configuration.BACKEND_CPU
                                    || config.gpuOffloadLayers != 0;
                    if (gpuWasRequested) {
                        Log.w(TAG, "GPU model init failed (" + initResult + "); retrying on CPU");
                        DiagnosticsLogger.logEvent(context, "model-load",
                                "GPU init failed → CPU auto-fallback: " + initResult);
                        config.backendType = ConfigurationManager.Configuration.BACKEND_CPU;
                        config.gpuOffloadLayers = 0;
                        prepareForLargeModelLoad(modelPath);
                        applyLoadParameters(config, config.nCtx);
                        initResult = llama.initWithMmproj(
                                modelPath,
                                mmprojPath != null ? mmprojPath : "",
                                enableAudioForLoad);
                        if ("ok".equals(initResult)) {
                            DiagnosticsLogger.logEvent(context, "model-load", "CPU auto-fallback init ok");
                            if (listener != null) {
                                listener.onError("GPU unavailable for this model — loaded on CPU instead."
                                        + "／GPUで初期化できなかったためCPUで読み込みました。");
                            }
                        }
                    }
                    if (!"ok".equals(initResult)) {
                        Log.e(TAG, "Model init failed: " + initResult);
                        if (listener != null) {
                            listener.onError("Model init failed: " + initResult);
                        }
                        return false;
                    }
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
            // Notify the WebUI that the model has changed → it will reset settings to app defaults.
            if (requiresModelInit) {
                modelLoadVersion.incrementAndGet();
                webUiVersion.incrementAndGet();
            }
            // この設定でモデルを構築したことを記録 (再ロード判定に使用)
            currentBackendType = config.backendType;
            currentNpuEnabled = config.npuEnabled;
            currentGpuOffloadLayers = config.gpuOffloadLayers;
            currentUseMmap = config.useMmap;
            currentKvCacheTypeK = config.kvCacheTypeK;
            currentKvCacheTypeV = config.kvCacheTypeV;
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
                            + " mmap=" + config.useMmap
                            + " gpuLayers=" + config.gpuOffloadLayers
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
        llama.setNPredict(config.nPredict);
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
        currentNCtx = nCtx;
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

        // KV cache quantization types (applied at next model init)
        llama.setKvCacheType(config.kvCacheTypeK, config.kvCacheTypeV);

        // Memory-map toggle (applied at next model init). Disabling helps very large models
        // load reliably when mmap's contiguous address-space reservation intermittently fails.
        llama.setUseMmap(config.useMmap);

        // Compute backend を JNI へ通知 (ADSP_LIBRARY_PATH 設定含む)
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        llama.setBackendConfig(config.backendType, config.npuEnabled, nativeLibDir);

        // MTP (multi-token prediction) speculative decoding — per-model config, applied at init.
        // mtpModelReference="" with mtpEnabled means "use the loaded model's own embedded MTP head"
        // (Qwen3.5-MTP / Gemma 4); a reference names a separate sidecar draft GGUF.
        int mtpNDraft = config.mtpNDraft > 0 ? config.mtpNDraft : 2;
        String mtpPathToUse = "";
        if (config.mtpEnabled && config.mtpModelReference != null && !config.mtpModelReference.isEmpty()) {
            File mtpFile = new File(config.mtpModelReference);
            if (!mtpFile.isAbsolute()) {
                mtpFile = new File(getModelStorageDir(), config.mtpModelReference);
            }
            if (mtpFile.isFile()) {
                mtpPathToUse = mtpFile.getAbsolutePath();
            }
        }
        llama.setSpeculative(config.mtpEnabled ? mtpPathToUse : "", mtpNDraft, config.mtpEnabled);
    }

    private int safePositive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private float safeFinite(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    // ---- n_ctx auto-promotion (Q2) helpers ----

    /**
     * If {@code result} is a context-too-small error (multimodal CTX_TOO_SMALL, or a text-path
     * overflow), return the number of prompt tokens that need to fit (including a small generation
     * headroom); otherwise 0. Used to decide whether to auto-promote n_ctx and retry.
     */
    private int parseCtxTooSmallNeed(String result) {
        if (result == null) {
            return 0;
        }
        Matcher m = CTX_TOO_SMALL_PATTERN.matcher(result);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) { return 0; }
        }
        m = CTX_TEXT_NEEDS_PATTERN.matcher(result);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)) + 16; } catch (NumberFormatException ignored) { return 0; }
        }
        m = CTX_TEXT_NOROOM_PATTERN.matcher(result);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)) + 16; } catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private boolean isCurrentBackendGpu() {
        return currentBackendType != ConfigurationManager.Configuration.BACKEND_CPU
                || currentGpuOffloadLayers != 0;
    }

    private static int nextPow2AtLeast(int v) {
        if (v <= 1) {
            return 1;
        }
        int p = Integer.highestOneBit(v - 1) << 1;
        return p > 0 ? p : Integer.MAX_VALUE;
    }

    /**
     * Compute the n_ctx to promote to so a prompt of {@code needTokens} fits, bounded by the memory
     * budget (approach A): on CPU by the ~3 GB anon governor (KV is anon), on GPU by a VRAM-friendly
     * KV ceiling (KV is an OpenCL buffer, off-anon). Returns 0 if promotion can't help / isn't
     * possible (unknown per-cell size, or the cap can't even hold the prompt).
     */
    private int computePromotedNCtx(int needTokens) {
        if (needTokens <= 0) {
            return 0;
        }
        long perCell;
        try {
            perCell = llama.getKvBytesPerCell();
        } catch (Throwable t) {
            perCell = 0;
        }
        int target = Math.min(CTX_PROMOTE_MAX_NCTX, nextPow2AtLeast(needTokens));

        int cap;
        if (perCell <= 0) {
            // Unknown model dims — fall back to the absolute cap only.
            cap = CTX_PROMOTE_MAX_NCTX;
        } else {
            // Byte budget for the KV cache we may add. The 3 GB policy is the target ceiling, but a
            // device with less memory than that is the tighter upper bound — so bound by the memory
            // actually available now (minus safety headroom) as well.
            long kvBudget;
            if (isCurrentBackendGpu()) {
                // GPU: KV lives in an OpenCL buffer (off-anon); bounded by VRAM, which is unified
                // with system RAM on these SoCs, so the live-available reading still applies.
                kvBudget = CTX_PROMOTE_GPU_KV_BUDGET_BYTES;
            } else {
                // CPU: KV is anon. Budget the *projected* anon against ~3 GB using a live reading of
                // the current anon footprint minus the KV already resident (self-calibrating).
                long liveAnon = DiagnosticsLogger.getCurrentAnonPlusSwapBytes();
                long baselineNonKv;
                if (liveAnon > 0) {
                    long currentKv = perCell * Math.max(0, currentNCtx);
                    baselineNonKv = Math.max(0, liveAnon - currentKv);
                } else {
                    baselineNonKv = CTX_PROMOTE_CPU_BASELINE_FALLBACK_BYTES;
                }
                kvBudget = CTX_PROMOTE_ANON_BUDGET_BYTES - CTX_PROMOTE_ANON_SAFETY_BYTES - baselineNonKv;
            }
            // Upper-bound by the device's currently-available memory (min of 3 GB target and the
            // real device headroom): never try to grab more KV than the device can actually give.
            long deviceAvail = getAvailableSystemMemoryBytes();
            if (deviceAvail > 0) {
                kvBudget = Math.min(kvBudget, deviceAvail - CTX_PROMOTE_ANON_SAFETY_BYTES);
            }
            long maxCells = kvBudget > 0 ? kvBudget / perCell : 0;
            cap = (int) Math.min(CTX_PROMOTE_MAX_NCTX, Math.max(0, maxCells));
        }

        int promoted = Math.min(target, cap);
        // Only worthwhile if it grows the window AND can actually hold the prompt.
        if (promoted <= currentNCtx || promoted < needTokens) {
            return 0;
        }
        return promoted;
    }

    /** Currently-available system memory in bytes (ActivityManager.MemoryInfo.availMem), or -1. */
    private long getAvailableSystemMemoryBytes() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return mi.availMem;
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    /** User-facing, actionable message when a prompt can't fit even after bounded n_ctx promotion. */
    private String ctxTooSmallUserMessage(int needTokens, int haveNCtx) {
        return "Input too large for the available memory budget: it needs about " + needTokens
                + " context tokens but only " + haveNCtx + " fit within the memory limit (~3 GB or the "
                + "device's available memory, whichever is smaller). Use a smaller image or shorter input. "
                + "／ 入力が利用可能メモリに対して大きすぎます（約" + needTokens + "トークン必要ですが、"
                + "メモリ上限（3GBまたは実機の空きメモリの小さい方）内では" + haveNCtx
                + "しか確保できません）。画像を小さくするか入力を短くしてください。";
    }

    /** Message when the prompt exceeds n_ctx and auto-expand is turned off. */
    private String ctxTooSmallDisabledMessage(int needTokens, int haveNCtx) {
        return "Input needs about " + needTokens + " context tokens but n_ctx=" + haveNCtx
                + ". Increase Context Size, or enable \"Auto-expand context\" in Settings, or shorten the input. "
                + "／ 入力に約" + needTokens + "トークン必要ですが n_ctx=" + haveNCtx
                + " です。コンテキストサイズを増やすか、設定の「コンテキスト自動拡張」を有効にするか、入力を短くしてください。";
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
        
        String result = null;
        // Q2: allow one context-too-small auto-promotion + retry. Qwen3-VL and other native
        // dynamic-resolution VLMs can emit more image tokens than the configured n_ctx; when the
        // native pre-check reports CTX_TOO_SMALL we grow n_ctx (bounded by the ~3 GB memory budget),
        // reload, and retry once instead of returning a cryptic decode failure.
        boolean ctxPromoteTried = false;
        for (int attempt = 0; attempt < 2; attempt++) {
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

            // Q2: context-too-small → promote n_ctx within the memory budget and retry once.
            if (!ctxPromoteTried && attempt == 0) {
                int need = parseCtxTooSmallNeed(result);
                if (need > 0) {
                    ctxPromoteTried = true;
                    boolean autoExpand = lastLoadedConfig != null && lastLoadedConfig.nCtxAutoExpand;
                    int newNCtx = autoExpand ? computePromotedNCtx(need) : 0;
                    if (newNCtx > 0 && currentConfigName != null) {
                        DiagnosticsLogger.logEvent(context, "ctx-autopromote",
                                "need=" + need + " from=" + currentNCtx + " to=" + newNCtx
                                        + " backend=" + (isCurrentBackendGpu() ? "GPU" : "CPU"));
                        setNCtxOverrideForNextLoad(newNCtx);
                        if (loadConfiguration(currentConfigName)) {
                            continue;   // retry generation at the larger context
                        }
                        Log.e(TAG, "ctx auto-promote reload failed; returning original error");
                    } else {
                        // Either auto-expand is disabled, or we can't grow enough within the memory
                        // budget — surface an actionable error instead of the raw native string.
                        DiagnosticsLogger.logEvent(context, "ctx-autopromote",
                                "need=" + need + " have=" + currentNCtx
                                        + " result=" + (autoExpand ? "capped(no-promote)" : "disabled"));
                        result = autoExpand
                                ? ctxTooSmallUserMessage(need, currentNCtx)
                                : ctxTooSmallDisabledMessage(need, currentNCtx);
                    }
                }
            }
            break;
        }

        if (listener != null) {
            listener.onGenerationComplete(currentConfigName, result);
        }

        return result;
    }

    /**
     * Set the GBNF grammar / JSON schema constraint applied by the next {@link #generate(String)}.
     * Pass empty strings to clear. NOT thread-safe; caller holds the busy lock (same as generate).
     */
    public void setGrammarConstraint(String gbnf, String jsonSchema) {
        if (!modelLoaded) {
            return;
        }
        try {
            llama.setGrammar(gbnf == null ? "" : gbnf, jsonSchema == null ? "" : jsonSchema);
        } catch (Throwable t) {
            Log.e(TAG, "setGrammarConstraint failed", t);
        }
    }

    /**
     * Compute a sentence embedding for {@code text}. Returns the native JSON string
     * ({@code {"embedding":[...]}} on success, {@code {"error":"..."}} on failure).
     * NOT thread-safe; caller holds the busy lock (same as generate).
     */
    public String embed(String text) {
        if (!modelLoaded) {
            return "{\"error\":\"model not loaded\"}";
        }
        try {
            return llama.embed(text == null ? "" : text);
        } catch (Throwable t) {
            Log.e(TAG, "embed failed", t);
            return "{\"error\":\"embed exception\"}";
        }
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
        currentUseMmap = true;
        currentKvCacheTypeK = Integer.MIN_VALUE;
        currentKvCacheTypeV = Integer.MIN_VALUE;
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

    /**
     * Run the native download while holding a partial wake lock so the transfer is less
     * likely to be interrupted when the app is backgrounded or the screen turns off.
     * The wake lock has a long safety timeout and is always released in the finally block.
     */
    private String downloadWithWakeLock(String url, String destPath) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, DOWNLOAD_WAKE_LOCK_TAG);
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(DOWNLOAD_WAKE_LOCK_TIMEOUT_MS);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to acquire download wake lock; continuing without it", t);
            wakeLock = null;
        }

        try {
            return llama.download(url, destPath);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to release download wake lock", t);
                }
            }
        }
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
            String downloadResult = downloadWithWakeLock(config.modelUrl, destFile.getAbsolutePath());
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
        // If the selected model is itself an mmproj/projector GGUF, never resolve or download a
        // separate projector for it. This avoids trying to (re)download an mmproj while the user
        // is downloading an mmproj file directly (request #5).
        if (ModelFileHelper.isLikelyProjectorFilename(extractFilenameFromUrl(config.modelUrl))) {
            return new MultimodalProjectorResolution(null, null);
        }
        if (config.multimodalProjectorUrl == null || config.multimodalProjectorUrl.trim().isEmpty()) {
            // The user explicitly cleared the projector: honor that and do NOT auto-discover a
            // co-located mmproj (otherwise "Clear Projector" would silently re-enable vision).
            if (config.multimodalProjectorDisabled) {
                return new MultimodalProjectorResolution(null, null);
            }
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
            String mmprojPath,
            boolean allowProjectorDownload) {
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

        // The mmproj is remote and not yet downloaded. Only download it when the caller (UI)
        // has confirmed it (request #2). Otherwise skip it and load the model text-only.
        if (!allowProjectorDownload) {
            Log.i(TAG, "Skipping mmproj download (not permitted by caller); loading text-only: " + mmprojReference);
            return new MultimodalProjectorResolution(null, null);
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
        String downloadResult = downloadWithWakeLock(mmprojReference, mmprojFile.getAbsolutePath());
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
