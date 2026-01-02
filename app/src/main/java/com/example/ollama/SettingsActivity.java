package com.example.ollama;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";
    
    public static final String EXTRA_CONFIG_NAME = "config_name";
    public static final String EXTRA_MODEL_PATH = "model_path";
    public static final String EXTRA_MODEL_LOADED = "model_loaded";
    
    private ConfigurationManager configManager;
    private LlamaNative llama;
    
    // UI elements
    private EditText configNameInput;
    private Spinner configSpinner;
    private EditText modelUrlInput;
    private EditText nCtxInput;
    private EditText nThreadsInput;
    private EditText nBatchInput;
    private EditText tempInput;
    private EditText topPInput;
    private EditText topKInput;
    private EditText promptTemplateInput;
    private TextView modelFileInfo;
    private ProgressBar modelProgressBar;
    private Button loadModelButton;
    
    private ConfigurationManager.Configuration currentConfig;
    private ArrayAdapter<String> configAdapter;
    private String loadedModelPath = null;
    private boolean modelLoadedSuccessfully = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        configManager = new ConfigurationManager(this);
        
        // Initialize LlamaNative for model loading
        llama = new LlamaNative() {
            @Override
            public void onDownloadProgress(final int percent) {
                runOnUiThread(() -> {
                    modelProgressBar.setProgress(percent);
                });
            }
        };
        
        // Set JNI log path
        File logFile = new File(getExternalFilesDir(null), "ollama.log");
        try {
            llama.setLogPath(logFile.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to set log path", t);
        }
        
        initViews();
        loadConfigList();
        
        // Load configuration from intent or default
        String configName = getIntent().getStringExtra(EXTRA_CONFIG_NAME);
        if (configName == null || configName.isEmpty()) {
            configName = "default";
        }
        loadConfigurationByName(configName);
    }
    
    private void initViews() {
        configNameInput = findViewById(R.id.configNameInput);
        configSpinner = findViewById(R.id.configSpinner);
        modelUrlInput = findViewById(R.id.modelUrlInput);
        nCtxInput = findViewById(R.id.nCtxInput);
        nThreadsInput = findViewById(R.id.nThreadsInput);
        nBatchInput = findViewById(R.id.nBatchInput);
        tempInput = findViewById(R.id.tempInput);
        topPInput = findViewById(R.id.topPInput);
        topKInput = findViewById(R.id.topKInput);
        promptTemplateInput = findViewById(R.id.promptTemplateInput);
        modelFileInfo = findViewById(R.id.modelFileInfo);
        modelProgressBar = findViewById(R.id.modelProgressBar);
        loadModelButton = findViewById(R.id.loadModelButton);
        
        Button saveConfigButton = findViewById(R.id.saveConfigButton);
        Button loadConfigButton = findViewById(R.id.loadConfigButton);
        Button deleteConfigButton = findViewById(R.id.deleteConfigButton);
        Button backButton = findViewById(R.id.backButton);
        
        saveConfigButton.setOnClickListener(v -> saveCurrentConfiguration());
        loadConfigButton.setOnClickListener(v -> loadSelectedConfiguration());
        deleteConfigButton.setOnClickListener(v -> deleteSelectedConfiguration());
        loadModelButton.setOnClickListener(v -> loadModel());
        backButton.setOnClickListener(v -> finish());
    }
    
    private void loadConfigList() {
        List<String> configs = configManager.listConfigurations();
        configAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, configs);
        configAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        configSpinner.setAdapter(configAdapter);
    }
    
    private void loadConfigurationByName(String name) {
        try {
            currentConfig = configManager.loadConfiguration(name);
            updateUIFromConfig(currentConfig);
            showToast("Loaded configuration: " + name);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load configuration: " + name, e);
            showToast("Failed to load configuration: " + e.getMessage());
            // Load default
            currentConfig = new ConfigurationManager.Configuration();
            updateUIFromConfig(currentConfig);
        }
    }
    
    private void updateUIFromConfig(ConfigurationManager.Configuration config) {
        configNameInput.setText(config.name);
        modelUrlInput.setText(config.modelUrl);
        nCtxInput.setText(String.valueOf(config.nCtx));
        nThreadsInput.setText(String.valueOf(config.nThreads));
        nBatchInput.setText(String.valueOf(config.nBatch));
        tempInput.setText(String.valueOf(config.temp));
        topPInput.setText(String.valueOf(config.topP));
        topKInput.setText(String.valueOf(config.topK));
        promptTemplateInput.setText(config.promptTemplate);
    }
    
    private ConfigurationManager.Configuration getConfigFromUI() {
        ConfigurationManager.Configuration config = new ConfigurationManager.Configuration();
        
        config.name = configNameInput.getText().toString().trim();
        if (config.name.isEmpty()) {
            config.name = "unnamed";
        }
        
        config.modelUrl = modelUrlInput.getText().toString().trim();
        
        try {
            config.nCtx = Integer.parseInt(nCtxInput.getText().toString());
        } catch (NumberFormatException e) {
            config.nCtx = 2048;
        }
        
        try {
            config.nThreads = Integer.parseInt(nThreadsInput.getText().toString());
        } catch (NumberFormatException e) {
            config.nThreads = 2;
        }
        
        try {
            config.nBatch = Integer.parseInt(nBatchInput.getText().toString());
        } catch (NumberFormatException e) {
            config.nBatch = 16;
        }
        
        try {
            config.temp = Double.parseDouble(tempInput.getText().toString());
        } catch (NumberFormatException e) {
            config.temp = 0.7;
        }
        
        try {
            config.topP = Double.parseDouble(topPInput.getText().toString());
        } catch (NumberFormatException e) {
            config.topP = 0.9;
        }
        
        try {
            config.topK = Integer.parseInt(topKInput.getText().toString());
        } catch (NumberFormatException e) {
            config.topK = 40;
        }
        
        config.promptTemplate = promptTemplateInput.getText().toString();
        if (config.promptTemplate.isEmpty()) {
            config.promptTemplate = "<|system|>\nYou are a helpful assistant.\n<|user|>\n{USER_INPUT}\n<|assistant|>\n";
        }
        
        return config;
    }
    
    private void saveCurrentConfiguration() {
        ConfigurationManager.Configuration config = getConfigFromUI();
        
        try {
            configManager.saveConfiguration(config);
            currentConfig = config;
            
            // Refresh spinner list
            loadConfigList();
            
            // Select the saved config in spinner
            int position = configAdapter.getPosition(config.name);
            if (position >= 0) {
                configSpinner.setSelection(position);
            }
            
            showToast("Configuration saved: " + config.name);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to save configuration", e);
            showToast("Failed to save: " + e.getMessage());
        }
    }
    
    private void loadSelectedConfiguration() {
        String selectedName = (String) configSpinner.getSelectedItem();
        if (selectedName == null || selectedName.isEmpty()) {
            showToast("No configuration selected");
            return;
        }
        loadConfigurationByName(selectedName);
    }
    
    private void deleteSelectedConfiguration() {
        String selectedName = (String) configSpinner.getSelectedItem();
        if (selectedName == null || selectedName.isEmpty()) {
            showToast("No configuration selected");
            return;
        }
        
        if ("default".equals(selectedName)) {
            showToast("Cannot delete default configuration");
            return;
        }
        
        if (configManager.deleteConfiguration(selectedName)) {
            loadConfigList();
            showToast("Deleted configuration: " + selectedName);
            // Load default after deletion
            loadConfigurationByName("default");
        } else {
            showToast("Failed to delete configuration");
        }
    }
    
    private void loadModel() {
        final String url = modelUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            showToast("Please enter a download URL");
            return;
        }
        
        // Extract filename from URL
        final String filename = extractFilenameFromUrl(url);
        if (filename == null || filename.isEmpty()) {
            showToast("Cannot determine filename from URL");
            return;
        }
        
        final File destFile = new File(getFilesDir(), filename);
        final String modelPath = destFile.getAbsolutePath();
        
        modelFileInfo.setText("Model file: " + filename + " (checking...)");
        modelProgressBar.setProgress(0);
        
        // If exists, skip download and init
        if (destFile.exists() && destFile.length() > 0) {
            modelFileInfo.setText("Model file: " + filename + " (" + destFile.length() + " bytes, exists)");
            showToast("Model file already exists");
            initModelInBackground(modelPath);
        } else {
            // Download then init
            new Thread(() -> {
                String dlResult = null;
                try {
                    dlResult = llama.download(url, modelPath);
                } catch (Throwable t) {
                    Log.e(TAG, "Download error", t);
                    showToast("Download error: " + t.getMessage());
                    return;
                }
                
                if (!"ok".equals(dlResult)) {
                    showToast("Download failed: " + dlResult);
                    return;
                }
                
                File f = new File(modelPath);
                runOnUiThread(() -> modelFileInfo.setText("Model file: " + filename + " (" + f.length() + " bytes, downloaded)"));
                
                // init model
                initModelInBackground(modelPath);
            }).start();
        }
    }
    
    private void initModelInBackground(final String modelPath) {
        runOnUiThread(() -> {
            modelFileInfo.setText("Initializing model...");
            modelProgressBar.setProgress(0);
            loadModelButton.setEnabled(false);
        });
        
        new Thread(() -> {
            String initResult = null;
            try {
                initResult = llama.init(modelPath);
            } catch (Throwable t) {
                Log.e(TAG, "Model init error", t);
                runOnUiThread(() -> {
                    showToast("Model init error: " + t.getMessage());
                    modelFileInfo.setText("Model init failed");
                    loadModelButton.setEnabled(true);
                });
                return;
            }
            
            final String finalInitResult = initResult;
            
            if (!"ok".equals(finalInitResult)) {
                runOnUiThread(() -> {
                    showToast("Model init failed: " + finalInitResult);
                    modelFileInfo.setText("Model init failed: " + finalInitResult);
                    loadModelButton.setEnabled(true);
                });
                return;
            }
            
            runOnUiThread(() -> {
                loadedModelPath = modelPath;
                modelLoadedSuccessfully = true;
                modelFileInfo.setText("Model loaded: " + (new File(modelPath).getName()));
                loadModelButton.setEnabled(true);
                modelProgressBar.setProgress(100);
                showToast("Model initialized successfully");
            });
        }).start();
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
    
    private void showToast(final String msg) {
        runOnUiThread(() -> {
            Toast toast = Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        });
    }
    
    @Override
    public void finish() {
        // Return the current configuration name and model info to MainActivity
        Intent resultIntent = new Intent();
        if (currentConfig != null) {
            resultIntent.putExtra(EXTRA_CONFIG_NAME, currentConfig.name);
        }
        if (loadedModelPath != null) {
            resultIntent.putExtra(EXTRA_MODEL_PATH, loadedModelPath);
            resultIntent.putExtra(EXTRA_MODEL_LOADED, modelLoadedSuccessfully);
        }
        setResult(RESULT_OK, resultIntent);
        super.finish();
    }
}
