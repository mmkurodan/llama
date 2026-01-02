package com.example.ollama;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ConfigurationManager {
    private static final String TAG = "ConfigurationManager";
    private static final String CONFIG_DIR = "configs";
    private static final String DEFAULT_CONFIG_NAME = "default";
    
    private final Context context;
    private final File configDir;
    
    public static class Configuration {
        public String name;
        public String modelUrl;
        public int nCtx;
        public int nThreads;
        public int nBatch;
        public double temp;
        public double topP;
        public int topK;
        public String promptTemplate;
        
        public Configuration() {
            // Default values
            name = DEFAULT_CONFIG_NAME;
            modelUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf";
            nCtx = 2048;
            nThreads = 2;
            nBatch = 16;
            temp = 0.7;
            topP = 0.9;
            topK = 40;
            promptTemplate = "<|system|>\nYou are a helpful assistant.\n<|user|>\n{USER_INPUT}\n<|assistant|>\n";
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
            return json;
        }
        
        public static Configuration fromJSON(JSONObject json) throws JSONException {
            Configuration config = new Configuration();
            config.name = json.getString("name");
            config.modelUrl = json.getString("modelUrl");
            config.nCtx = json.getInt("nCtx");
            config.nThreads = json.getInt("nThreads");
            config.nBatch = json.getInt("nBatch");
            config.temp = json.getDouble("temp");
            config.topP = json.getDouble("topP");
            config.topK = json.getInt("topK");
            config.promptTemplate = json.getString("promptTemplate");
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
    
    public void saveConfiguration(Configuration config) throws IOException, JSONException {
        if (config.name == null || config.name.trim().isEmpty()) {
            throw new IllegalArgumentException("Configuration name cannot be empty");
        }
        
        File configFile = new File(configDir, config.name + ".json");
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(config.toJSON().toString(2)); // Pretty print with indent of 2
        }
        Log.d(TAG, "Saved configuration: " + config.name);
    }
    
    public Configuration loadConfiguration(String name) throws IOException, JSONException {
        File configFile = new File(configDir, name + ".json");
        if (!configFile.exists()) {
            throw new IOException("Configuration not found: " + name);
        }
        
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        
        JSONObject json = new JSONObject(sb.toString());
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
