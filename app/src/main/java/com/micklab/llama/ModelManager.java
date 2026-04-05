package com.micklab.llama;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int PRELOAD_N_CTX = 64;
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
        boolean shouldClearPendingLoad = false;
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

            final boolean requiresModelInit = !modelPath.equals(currentModelPath) || !modelLoaded;
            
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
                    unloadCurrentModelLocked();
                } else {
                    clearLoadedModelState();
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
     * Caller must hold the busy lock.
     *
     * @param configName Configuration name to reload
     * @return true if successful, false otherwise
     */
    public boolean reinitializeConfiguration(String configName) {
        String resolvedConfigName = (configName == null || configName.trim().isEmpty())
                ? DEFAULT_CONFIG_NAME
                : configName.trim();
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
                if (currentModelPath != null || modelLoaded) {
                    unloadCurrentModelLocked();
                } else {
                    clearLoadedModelState();
                }
            } finally {
                busy.set(false);
            }
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
        currentConfigName = null;
        modelLoaded = false;
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

    private String ensureModelFilesAvailable(ConfigurationManager.Configuration config, File destFile) {
        boolean needsDownload = !destFile.exists() || destFile.length() == 0;
        String missingShardPath = findMissingSplitShardPath(destFile);

        if (!needsDownload && missingShardPath != null) {
            Log.w(TAG, "Incomplete split model detected, repairing download. Missing: " + missingShardPath);
            needsDownload = true;
        }

        if (needsDownload) {
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
