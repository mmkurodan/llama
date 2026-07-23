package com.micklab.llama;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ConfigurationManager {
    private static final String TAG = "ConfigurationManager";
    private static final String CONFIG_DIR = "configs";
    private static final String DEFAULT_CONFIG_NAME = "default";
    
    private final Context context;
    private final File configDir;
    
    public static class Configuration {
        // Default constant for DRY sequence breakers (must match C++ DEFAULT_DRY_SEQUENCE_BREAKERS)
        public static final String DEFAULT_DRY_SEQUENCE_BREAKERS = "\\n,:,\",*";

        // Prefill batch size (n_batch; JNI also uses it for n_ubatch). 128 balances prompt
        // processing speed against the transient compute buffer size on mobile.
        public static final int DEFAULT_N_BATCH = 128;
        // Stored batch sizes at or below this legacy ceiling are treated as the old throttled
        // default and migrated up to DEFAULT_N_BATCH on load.
        private static final int LEGACY_N_BATCH_CEILING = 16;

        public String name;
        public String modelUrl;
        public int nCtx;
        public int nThreads;
        public int nBatch;
        public double temp;
        public double topP;
        public int topK;
        public String promptTemplate;
        
        // New settings for prompt generation
        public String systemPrompt;          // 前提プロンプト (system prompt)
        public String customChatTemplate;    // カスタムプロンプトテンプレート
        public String multimodalProjectorUrl; // Optional multimodal projector (mmproj) reference
        public boolean multimodalProjectorManualSelection; // True when the user explicitly chose a GGUF as projector
        // True when the user explicitly cleared the projector. Distinguishes an intentional
        // "disable vision" from an unset projector: when disabled, no co-located mmproj is
        // auto-detected, so clearing actually takes effect (previously auto-discovery re-applied it).
        public boolean multimodalProjectorDisabled;

        // MTP (multi-token prediction) speculative decoding — per-model (experimental).
        public boolean mtpEnabled;          // explicit enable toggle
        public String  mtpModelReference;   // draft source: "" = model's own embedded MTP head; else a GGUF reference
        public int     mtpNDraft;           // max tokens drafted per step

        // Penalty parameters
        public int penaltyLastN;
        public double penaltyRepeat;
        public double penaltyFreq;
        public double penaltyPresent;
        
        // Mirostat parameters
        public int mirostat;
        public double mirostatTau;
        public double mirostatEta;
        
        // Additional sampling parameters
        public double minP;
        public double typicalP;
        public double dynatempRange;
        public double dynatempExponent;
        public double xtcProbability;
        public double xtcThreshold;
        public double topNSigma;
        
        // DRY parameters
        public double dryMultiplier;
        public double dryBase;
        public int dryAllowedLength;
        public int dryPenaltyLastN;
        public String drySequenceBreakers;
        
        // Streaming parameter
        public boolean streaming;

        // Runtime behavior switches
        public int gpuOffloadLayers;
        public boolean enableThinking;

        // Compute backend
        // 0=CPU  1=GPU  (NPU/HTP removed — Qualcomm Hexagon SDK redistribution restriction)
        public static final int BACKEND_CPU     = 0;
        public static final int BACKEND_GPU     = 1;
        public int  backendType;
        // Retained for serialization back-compat only; always false (NPU support removed).
        public boolean npuEnabled;
        
        public Configuration() {
            // Default values - Gemma 1B assistant
            name = DEFAULT_CONFIG_NAME;
            modelUrl = "https://huggingface.co/vinhnx90/gemma-3-1b-thinking-v2-Q4_K_M-GGUF/resolve/main/gemma-3-1b-thinking-v2-q4_k_m.gguf?download=true";
            nCtx = 2048;
            nThreads = 4;
            nBatch = DEFAULT_N_BATCH;
            temp = 0.7;
            topP = 0.9;
            topK = 40;
            promptTemplate = "<start_of_turn>system\nYou are a helpful assistant. Please respond in the user's language.\n<end_of_turn>\n<start_of_turn>user\n{USER_INPUT}\n<end_of_turn>\n<start_of_turn>model";
            
            // Penalty parameters defaults
            penaltyLastN = 64;
            penaltyRepeat = 1.1;
            penaltyFreq = 0.0;
            penaltyPresent = 0.0;
            
            // Mirostat parameters defaults
            mirostat = 0;
            mirostatTau = 5.0;
            mirostatEta = 0.1;
            
            // Additional sampling parameters defaults
            minP = 0.01;
            typicalP = 1.0;
            dynatempRange = 0.0;
            dynatempExponent = 1.0;
            xtcProbability = 0.0;
            xtcThreshold = 0.1;
            topNSigma = -1.0;
            
            // DRY parameters defaults
            dryMultiplier = 0.0;
            dryBase = 1.75;
            dryAllowedLength = 2;
            dryPenaltyLastN = -1;
            drySequenceBreakers = DEFAULT_DRY_SEQUENCE_BREAKERS;
            
            // Streaming default
            streaming = true;

            // Runtime behavior defaults
            gpuOffloadLayers = 0;
            enableThinking = true;

            // Backend defaults
            backendType = BACKEND_CPU;
            npuEnabled  = false;
            
            // New prompt settings defaults
            systemPrompt = "";           // Empty by default
            customChatTemplate = "";     // Empty by default (use auto-detection)
            multimodalProjectorUrl = ""; // Empty by default (use auto-discovery)
            multimodalProjectorManualSelection = false;
            multimodalProjectorDisabled = false; // Auto-discovery allowed until the user explicitly clears
            mtpEnabled = false;
            mtpModelReference = "";
            mtpNDraft = 2;
        }
        
        public Configuration(String name) {
            this();
            this.name = name;
        }
        
        public JSONObject toJSON() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("modelUrl", modelUrl);
            json.put("nCtx", nCtx);
            json.put("nThreads", nThreads);
            json.put("nBatch", nBatch);
            json.put("temp", temp);
            json.put("topP", topP);
            json.put("topK", topK);
            json.put("promptTemplate", promptTemplate);
            
            // Penalty parameters
            json.put("penaltyLastN", penaltyLastN);
            json.put("penaltyRepeat", penaltyRepeat);
            json.put("penaltyFreq", penaltyFreq);
            json.put("penaltyPresent", penaltyPresent);
            
            // Mirostat parameters
            json.put("mirostat", mirostat);
            json.put("mirostatTau", mirostatTau);
            json.put("mirostatEta", mirostatEta);
            
            // Additional sampling parameters
            json.put("minP", minP);
            json.put("typicalP", typicalP);
            json.put("dynatempRange", dynatempRange);
            json.put("dynatempExponent", dynatempExponent);
            json.put("xtcProbability", xtcProbability);
            json.put("xtcThreshold", xtcThreshold);
            json.put("topNSigma", topNSigma);
            
            // DRY parameters
            json.put("dryMultiplier", dryMultiplier);
            json.put("dryBase", dryBase);
            json.put("dryAllowedLength", dryAllowedLength);
            json.put("dryPenaltyLastN", dryPenaltyLastN);
            json.put("drySequenceBreakers", drySequenceBreakers);
            
            // Streaming
            json.put("streaming", streaming);

            // Runtime behavior switches
            json.put("gpuOffloadLayers", gpuOffloadLayers);
            json.put("gpuOffloadEnabled", gpuOffloadLayers != 0);
            json.put("enableThinking", enableThinking);

            // Compute backend
            json.put("backendType", backendType);
            json.put("npuEnabled",  npuEnabled);

            // New prompt settings
            json.put("systemPrompt", systemPrompt);
            json.put("customChatTemplate", customChatTemplate);
            json.put("multimodalProjectorUrl", multimodalProjectorUrl);
            json.put("multimodalProjectorManualSelection", multimodalProjectorManualSelection);
            json.put("multimodalProjectorDisabled", multimodalProjectorDisabled);
            json.put("mtpEnabled", mtpEnabled);
            json.put("mtpModelReference", mtpModelReference);
            json.put("mtpNDraft", mtpNDraft);

            return json;
        }
        
        public static Configuration fromJSON(JSONObject json) throws JSONException {
            Configuration config = new Configuration();
            config.name = json.optString("name", config.name);
            config.modelUrl = json.optString("modelUrl", config.modelUrl);
            config.nCtx = json.optInt("nCtx", config.nCtx);
            config.nThreads = json.optInt("nThreads", config.nThreads);
            config.nBatch = json.optInt("nBatch", config.nBatch);
            // Migrate legacy throttled batch sizes (the old default was 16) up to the current
            // default so existing configs benefit from the restored prefill speed.
            if (config.nBatch > 0 && config.nBatch <= LEGACY_N_BATCH_CEILING) {
                config.nBatch = DEFAULT_N_BATCH;
            }
            config.temp = json.optDouble("temp", config.temp);
            config.topP = json.optDouble("topP", config.topP);
            config.topK = json.optInt("topK", config.topK);
            config.promptTemplate = json.optString("promptTemplate", config.promptTemplate);
            
            // Penalty parameters (with defaults for backward compatibility)
            config.penaltyLastN = json.optInt("penaltyLastN", 64);
            config.penaltyRepeat = json.optDouble("penaltyRepeat", 1.1);
            config.penaltyFreq = json.optDouble("penaltyFreq", 0.0);
            config.penaltyPresent = json.optDouble("penaltyPresent", 0.0);
            
            // Mirostat parameters (with defaults for backward compatibility)
            config.mirostat = json.optInt("mirostat", 0);
            config.mirostatTau = json.optDouble("mirostatTau", 5.0);
            config.mirostatEta = json.optDouble("mirostatEta", 0.1);
            
            // Additional sampling parameters (with defaults for backward compatibility)
            config.minP = json.optDouble("minP", 0.01);
            config.typicalP = json.optDouble("typicalP", 1.0);
            config.dynatempRange = json.optDouble("dynatempRange", 0.0);
            config.dynatempExponent = json.optDouble("dynatempExponent", 1.0);
            config.xtcProbability = json.optDouble("xtcProbability", 0.0);
            config.xtcThreshold = json.optDouble("xtcThreshold", 0.1);
            config.topNSigma = json.optDouble("topNSigma", -1.0);
            
            // DRY parameters (with defaults for backward compatibility)
            config.dryMultiplier = json.optDouble("dryMultiplier", 0.0);
            config.dryBase = json.optDouble("dryBase", 1.75);
            config.dryAllowedLength = json.optInt("dryAllowedLength", 2);
            config.dryPenaltyLastN = json.optInt("dryPenaltyLastN", -1);
            config.drySequenceBreakers = json.optString("drySequenceBreakers", DEFAULT_DRY_SEQUENCE_BREAKERS);
            
            // Streaming (with default for backward compatibility)
            config.streaming = json.optBoolean("streaming", true);

            // Runtime behavior switches (with defaults for backward compatibility)
            if (json.has("gpuOffloadLayers")) {
                config.gpuOffloadLayers = json.optInt("gpuOffloadLayers", 0);
            } else {
                boolean enabled = json.optBoolean("gpuOffloadEnabled", false);
                config.gpuOffloadLayers = enabled ? -1 : 0;
            }
            config.enableThinking = json.optBoolean("enableThinking", true);

            // Compute backend (backward compat: default CPU)
            config.backendType = json.optInt("backendType", Configuration.BACKEND_CPU);
            if (config.backendType < BACKEND_CPU || config.backendType > BACKEND_GPU) {
                config.backendType = BACKEND_CPU;  // legacy NPU(2)/GPU+NPU(3) profiles fall back to CPU
            }
            config.npuEnabled = false;  // NPU support removed; ignore any stored value

            // New prompt settings (with defaults for backward compatibility)
            config.systemPrompt = json.optString("systemPrompt", "");
            config.customChatTemplate = json.optString("customChatTemplate", "");
            config.multimodalProjectorUrl = json.optString("multimodalProjectorUrl", "");
            config.multimodalProjectorManualSelection =
                    json.optBoolean("multimodalProjectorManualSelection", false);
            config.multimodalProjectorDisabled =
                    json.optBoolean("multimodalProjectorDisabled", false);
            config.mtpEnabled = json.optBoolean("mtpEnabled", false);
            config.mtpModelReference = json.optString("mtpModelReference", "");
            config.mtpNDraft = json.optInt("mtpNDraft", 2);

            return config;
        }
    }
    
    public ConfigurationManager(Context context) {
        this.context = context;
        this.configDir = new File(context.getExternalFilesDir(null), CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        ensureDefaultConfig();
    }
    
    private void ensureDefaultConfig() {
        File defaultFile = new File(configDir, DEFAULT_CONFIG_NAME + ".json");
        if (!defaultFile.exists()) {
            try {
                saveConfiguration(new Configuration());
                Log.d(TAG, "Created default configuration");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Failed to create default configuration", e);
            }
        }
    }
    
    // Shared across ALL ConfigurationManager instances (MainActivity, SettingsActivity and the API
    // server each construct their own, all pointing at the same directory). Serialises config file
    // I/O process-wide so a save can never interleave with a concurrent read or another save.
    private static final Object CONFIG_IO_LOCK = new Object();

    public void saveConfiguration(Configuration config) throws IOException, JSONException {
        if (config.name == null || config.name.trim().isEmpty()) {
            throw new IllegalArgumentException("Configuration name cannot be empty");
        }

        String json = config.toJSON().toString(2); // Pretty print with indent of 2
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        File configFile = new File(configDir, config.name + ".json");
        File tempFile = new File(configDir, config.name + ".json.tmp");
        synchronized (CONFIG_IO_LOCK) {
            // Write to a temp file, flush it to disk, then atomically rename over the real file so a
            // reader (even another instance/process) always sees either the complete old or complete
            // new file — never a half-written one. A crash mid-write leaves the previous config
            // intact instead of a truncated, unparseable JSON.
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(bytes);
                fos.flush();
                fos.getFD().sync();
            }
            if (!tempFile.renameTo(configFile)) {
                // renameTo can fail if the destination exists on some filesystems; retry after delete.
                if (!(configFile.delete() && tempFile.renameTo(configFile))) {
                    tempFile.delete();
                    throw new IOException("Failed to persist configuration: " + config.name);
                }
            }
        }
        Log.d(TAG, "Saved configuration: " + config.name);
    }

    public Configuration loadConfiguration(String name) throws IOException, JSONException {
        File configFile = new File(configDir, name + ".json");
        String content;
        synchronized (CONFIG_IO_LOCK) {
            if (!configFile.exists()) {
                throw new IOException("Configuration not found: " + name);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            content = sb.toString();
        }

        JSONObject json = new JSONObject(content);
        Configuration config = Configuration.fromJSON(json);
        Log.d(TAG, "Loaded configuration: " + name);
        return config;
    }
    
    public List<String> listConfigurations() {
        List<String> configs = new ArrayList<>();
        File[] files = configDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                // Remove .json extension
                configs.add(name.substring(0, name.length() - 5));
            }
        }
        return configs;
    }
    
    public boolean deleteConfiguration(String name) {
        if (DEFAULT_CONFIG_NAME.equals(name)) {
            Log.w(TAG, "Cannot delete default configuration");
            return false;
        }
        
        File configFile = new File(configDir, name + ".json");
        boolean deleted = configFile.delete();
        if (deleted) {
            Log.d(TAG, "Deleted configuration: " + name);
        }
        return deleted;
    }
}
