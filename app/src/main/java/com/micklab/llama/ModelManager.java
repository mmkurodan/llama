package com.micklab.llama;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton class that manages model loading and generation.
 * Provides unified access for both UI and API, with busy state tracking.
 */
public class ModelManager {
    private static final String TAG = "ModelManager";
    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_LOG_LEVEL = "log_level";
    private static final int DEFAULT_LOG_LEVEL_INFO = 2;
    private static final int PRELOAD_N_CTX = 64;
    private static final int DEFAULT_N_CTX = 2048;
    private static final int DEFAULT_N_THREADS = 2;
    private static final int DEFAULT_N_BATCH = 16;
    private static final float DEFAULT_TEMP = 0.7f;
    private static final float DEFAULT_TOP_P = 0.9f;
    private static final int DEFAULT_TOP_K = 40;
    
    private static ModelManager instance;
    
    private final Context context;
    private final LlamaNative llama;
    private final ConfigurationManager configManager;
    
    // State tracking
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile String currentConfigName = null;
    private volatile String currentModelPath = null;
    private volatile boolean modelLoaded = false;
    
    // Listener interface
    public interface ModelListener {
        void onModelLoading(String configName);
        void onModelLoaded(String configName);
        void onGenerating(String configName);
        void onGenerationComplete(String configName, String result);
        void onError(String error);
    }
    
    private ModelListener listener;
    
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
    
    public LlamaNative getLlama() {
        return llama;
    }
    
    public boolean isBusy() {
        return busy.get();
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
    
    /**
     * Try to acquire the busy lock for generation.
     * @return true if lock acquired, false if already busy
     */
    public boolean tryAcquire() {
        return busy.compareAndSet(false, true);
    }
    
    /**
     * Release the busy lock.
     */
    public void release() {
        busy.set(false);
    }
    
    /**
     * Force reset the busy state.
     * This is used during model reinitialization to clear any stuck busy state.
     */
    public void resetBusy() {
        busy.set(false);
        Log.i(TAG, "Busy state forcefully reset");
    }
    
    /**
     * Load a configuration and its model if not already loaded.
     * This method is NOT thread-safe - caller must hold busy lock.
     * 
     * @param configName Configuration name to load
     * @return true if successful, false otherwise
     */
    public boolean loadConfiguration(String configName) {
        try {
            ConfigurationManager.Configuration config = configManager.loadConfiguration(configName);
            
            // Extract filename from URL
            String filename = extractFilenameFromUrl(config.modelUrl);
            if (filename == null || filename.isEmpty()) {
                Log.e(TAG, "Cannot determine filename from URL: " + config.modelUrl);
                return false;
            }
            
            File destFile = new File(getModelStorageDir(), filename);
            String modelPath = destFile.getAbsolutePath();
            
            // If same model is already loaded, just re-apply parameters
            if (modelPath.equals(currentModelPath) && modelLoaded) {
                Log.i(TAG, "Same model already loaded, re-applying parameters: " + configName);
                applyConfiguration(config);
                currentConfigName = configName;
                return true;
            }
            
            if (listener != null) {
                listener.onModelLoading(configName);
            }
            
            // Download if not exists
            if (!destFile.exists() || destFile.length() == 0) {
                Log.i(TAG, "Downloading model from: " + config.modelUrl);
                String dlResult = llama.download(config.modelUrl, modelPath);
                if (!"ok".equals(dlResult)) {
                    Log.e(TAG, "Download failed: " + dlResult);
                    if (listener != null) {
                        listener.onError("Download failed: " + dlResult);
                    }
                    return false;
                }
            }
            
            // Initialize model if path changed
            if (!modelPath.equals(currentModelPath)) {
                if (currentModelPath != null) {
                    llama.free();
                }

                applyLoadParameters(config, PRELOAD_N_CTX);
                String preloadResult = llama.init(modelPath);
                if (!"ok".equals(preloadResult)) {
                    Log.e(TAG, "Model preload failed: " + preloadResult);
                    if (listener != null) {
                        listener.onError("Model preload failed: " + preloadResult);
                    }
                    return false;
                }

                llama.free();
                applyLoadParameters(config, config.nCtx);
                String initResult = llama.init(modelPath);
                if (!"ok".equals(initResult)) {
                    Log.e(TAG, "Model init failed: " + initResult);
                    if (listener != null) {
                        listener.onError("Model init failed: " + initResult);
                    }
                    return false;
                }
                
                currentModelPath = modelPath;
            }
            
            // Set parameters from configuration
            applyConfiguration(config);
            
            currentConfigName = configName;
            modelLoaded = true;
            
            if (listener != null) {
                listener.onModelLoaded(configName);
            }
            
            Log.i(TAG, "Configuration loaded: " + configName);
            return true;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load configuration: " + configName, e);
            if (listener != null) {
                listener.onError("Failed to load configuration: " + e.getMessage());
            }
            return false;
        }
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
        llama.setLoadParameters(nCtx, nThreads, nBatch, temp, topP, topK);
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
        if (!modelLoaded) {
            return "Model not loaded";
        }
        
        if (listener != null) {
            listener.onGenerating(currentConfigName);
        }
        
        String result;
        try {
            result = llama.generate(prompt);
        } catch (Throwable t) {
            // Log full stack trace and notify listener so the server can respond gracefully
            Log.e(TAG, "Exception during generate", t);
            if (listener != null) {
                listener.onError("Generation exception: " + t.toString());
            }
            // Return a clear error string so API layer can send a proper error response
            return "generate failed: " + t.toString();
        }
        
        if (listener != null) {
            listener.onGenerationComplete(currentConfigName, result);
        }
        
        return result;
    }
    
    /**
     * Free the model resources.
     */
    public void free() {
        if (busy.compareAndSet(false, true)) {
            try {
                llama.free();
                currentModelPath = null;
                currentConfigName = null;
                modelLoaded = false;
            } finally {
                busy.set(false);
            }
        }
    }
    
    private String extractFilenameFromUrl(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        String pure = (q >= 0) ? url.substring(0, q) : url;
        int slash = pure.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < pure.length()) {
            return pure.substring(slash + 1);
        }
        return null;
    }

    private File getModelStorageDir() {
        File externalDir = context.getExternalFilesDir(null);
        return externalDir != null ? externalDir : context.getFilesDir();
    }
}
