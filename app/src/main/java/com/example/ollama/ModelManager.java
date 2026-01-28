package com.example.ollama;

import android.content.Context;
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
     * Load a configuration and its model if not already loaded.
     * This method is NOT thread-safe - caller must hold busy lock.
     * 
     * @param configName Configuration name to load
     * @return true if successful, false otherwise
     */
    public boolean loadConfiguration(String configName) {
        // If same config is already loaded, just return true
        if (configName.equals(currentConfigName) && modelLoaded) {
            Log.i(TAG, "Configuration already loaded: " + configName);
            return true;
        }
        
        try {
            ConfigurationManager.Configuration config = configManager.loadConfiguration(configName);
            
            if (listener != null) {
                listener.onModelLoading(configName);
            }
            
            // Extract filename from URL
            String filename = extractFilenameFromUrl(config.modelUrl);
            if (filename == null || filename.isEmpty()) {
                Log.e(TAG, "Cannot determine filename from URL: " + config.modelUrl);
                return false;
            }
            
            File destFile = new File(context.getFilesDir(), filename);
            String modelPath = destFile.getAbsolutePath();
            
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
        
        String result = llama.generate(prompt);
        
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
}
