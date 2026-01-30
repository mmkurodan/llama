package com.example.ollama;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

import android.widget.EditText;
import android.widget.Button;

import org.json.JSONException;

import java.io.IOException;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_SETTINGS = 1;
    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_API_PORT = "api_port";
    
    private TextView logView;           // log view (append-only)
    private ScrollView logScrollView;
    private TextView outputView;
    private ScrollView outputScrollView;

    private EditText promptInput;
    private Button sendButton;
    private Button settingsButton;
    private Button initModelButton;
    private Button viewLogButton;
    private Button clearLogButton;
    private Button apiServerButton;
    private Button copyOutputButton;
    private Button copyLogButton;
    private TextView apiServerStatusMain;
    
    // Model Manager (singleton)
    private ModelManager modelManager;
    
    // Configuration
    private ConfigurationManager configManager;
    private ConfigurationManager.Configuration currentConfig;
    
    // API Server (via Foreground Service)
    private int apiPort = OllamaApiServer.DEFAULT_PORT;
    private boolean isServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize configuration manager
        configManager = new ConfigurationManager(this);
        
        // Load default configuration
        try {
            currentConfig = configManager.loadConfiguration("default");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load default config", e);
            currentConfig = new ConfigurationManager.Configuration();
        }

        // Initialize views from XML
        logView = findViewById(R.id.logView);
        logScrollView = findViewById(R.id.logScrollView);
        outputView = findViewById(R.id.outputView);
        outputScrollView = findViewById(R.id.outputScrollView);
        promptInput = findViewById(R.id.promptInput);
        sendButton = findViewById(R.id.sendButton);
        settingsButton = findViewById(R.id.settingsButton);
        initModelButton = findViewById(R.id.initModelButton);
        viewLogButton = findViewById(R.id.viewLogButton);
        clearLogButton = findViewById(R.id.clearLogButton);
        apiServerButton = findViewById(R.id.apiServerButton);
        copyOutputButton = findViewById(R.id.copyOutputButton);
        copyLogButton = findViewById(R.id.copyLogButton);
        apiServerStatusMain = findViewById(R.id.apiServerStatusMain);

        appendMessage("UI ready.");

        // Initialize ModelManager singleton
        modelManager = ModelManager.getInstance(this);
        // Ensure UI buttons start enabled
        sendButton.setEnabled(true);
        initModelButton.setEnabled(true);

        // Set up button listeners
        settingsButton.setOnClickListener(v -> openSettings());
        initModelButton.setOnClickListener(v -> reinitializeModel());
        viewLogButton.setOnClickListener(v -> viewLogFile());
        clearLogButton.setOnClickListener(v -> clearLogFile());
        apiServerButton.setOnClickListener(v -> toggleApiServer());
        copyOutputButton.setOnClickListener(v -> copyToClipboard("Output", outputView.getText().toString()));
        copyLogButton.setOnClickListener(v -> copyToClipboard("Log", logView.getText().toString()));
        
        // Initialize API server via Foreground Service
        initApiServer();
        
        // Check if service is already running
        isServiceRunning = isServiceRunning(OllamaForegroundService.class);
        updateApiServerUI();

        // Send button behavior
        sendButton.setOnClickListener(v -> {
            final String userPrompt = promptInput.getText().toString();
            if (userPrompt == null || userPrompt.trim().isEmpty()) {
                showToast("Please enter a prompt");
                return;
            }
            if (!modelManager.isModelLoaded()) {
                showToast("Model not loaded yet. Please load a model in Settings.");
                return;
            }
            
            // Check if busy
            if (!modelManager.tryAcquire()) {
                showToast("Model is busy processing another request");
                return;
            }

            // Apply prompt template
            final String chatPrompt = applyPromptTemplate(userPrompt);

            appendMessage("Running generate...");
            outputView.setText("");
            new Thread(() -> {
                try {
                    // Set parameters before generating
                    if (currentConfig != null) {
                        modelManager.applyConfiguration(currentConfig);
                    }
                    
                    String gen = modelManager.generate(chatPrompt);
                    final String finalGen = gen;
                    runOnUiThread(() -> {
                        appendMessage("generate() returned.");
                        outputView.setText(finalGen);
                    });
                } catch (Throwable t) {
                    appendException("generate() threw", t);
                    showToast("Generate error: " + t.getMessage());
                } finally {
                    modelManager.release();
                }
            }).start();
        });
    }
    
    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        if (currentConfig != null) {
            intent.putExtra(SettingsActivity.EXTRA_CONFIG_NAME, currentConfig.name);
        }
        startActivityForResult(intent, REQUEST_SETTINGS);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK && data != null) {
            String configName = data.getStringExtra(SettingsActivity.EXTRA_CONFIG_NAME);
            if (configName != null) {
                try {
                    currentConfig = configManager.loadConfiguration(configName);
                    appendMessage("Loaded configuration: " + configName);
                } catch (IOException | JSONException e) {
                    Log.e(TAG, "Failed to load configuration", e);
                    appendMessage("Failed to load configuration: " + e.getMessage());
                }
            }
            
            // Check if a model was loaded in Settings
            String modelPath = data.getStringExtra(SettingsActivity.EXTRA_MODEL_PATH);
            boolean wasModelLoaded = data.getBooleanExtra(SettingsActivity.EXTRA_MODEL_LOADED, false);
            if (modelPath != null && wasModelLoaded) {
                appendMessage("Model loaded from Settings: " + new File(modelPath).getName());
            }
            
            // Update API port if changed
            int newPort = data.getIntExtra(SettingsActivity.EXTRA_API_PORT, OllamaApiServer.DEFAULT_PORT);
            if (newPort != apiPort) {
                apiPort = newPort;
                appendMessage("API port changed to: " + apiPort);
                // Restart service if running
                if (isServiceRunning) {
                    stopApiService();
                    startApiService();
                }
            }
        }
    }
    
    private void reinitializeModel() {
        String currentModelPath = modelManager.getCurrentModelPath();
        if (currentModelPath == null || currentModelPath.isEmpty()) {
            showToast("No model path available. Please load a model in Settings first.");
            return;
        }
        
        appendMessage("Freeing current model...");
        new Thread(() -> {
            try {
                modelManager.free();
                runOnUiThread(() -> {
                    appendMessage("Model freed.");
                });
                
                // Small delay to ensure cleanup
                Thread.sleep(500);
                
                // Re-initialize via loading default config
                if (modelManager.tryAcquire()) {
                    try {
                        appendMessage("Re-initializing model...");
                        boolean success = modelManager.loadConfiguration("default");
                        
                        runOnUiThread(() -> {
                            if (success) {
                                appendMessage("Model re-initialized successfully");
                                showToast("Model re-initialized successfully");
                            } else {
                                appendMessage("Model re-initialization failed");
                                showToast("Model re-initialization failed");
                            }
                        });
                    } finally {
                        modelManager.release();
                    }
                }
            } catch (Throwable t) {
                appendException("Model re-initialization error", t);
                showToast("Error: " + t.getMessage());
            }
        }).start();
    }
    
    private void viewLogFile() {
        File logFile = new File(getExternalFilesDir(null), "ollama.log");
        if (!logFile.exists()) {
            showToast("Log file does not exist");
            return;
        }
        
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                
                final String logContent = sb.toString();
                runOnUiThread(() -> {
                    outputView.setText(logContent);
                    showToast("Displaying log file content");
                });
            } catch (IOException e) {
                Log.e(TAG, "Failed to read log file", e);
                showToast("Failed to read log file: " + e.getMessage());
            }
        }).start();
    }
    
    private void clearLogFile() {
        File logFile = new File(getExternalFilesDir(null), "ollama.log");
        try (FileWriter writer = new FileWriter(logFile, false)) {
            writer.write(""); // Clear the file
            appendMessage("Log file cleared.");
            showToast("Log file cleared");
        } catch (IOException e) {
            Log.e(TAG, "Failed to clear log file", e);
            appendMessage("Failed to clear log file: " + e.getMessage());
            showToast("Failed to clear log file");
        }
    }
    
    private String applyPromptTemplate(String userInput) {
        if (currentConfig == null || currentConfig.promptTemplate == null || currentConfig.promptTemplate.isEmpty()) {
            // Fallback to default template
            return "<|system|>\n"
                 + "You are a helpful assistant.\n"
                 + "<|user|>\n"
                 + userInput + "\n"
                 + "<|assistant|>\n";
        }
        return currentConfig.promptTemplate.replace("{USER_INPUT}", userInput);
    }

    private void appendMessage(final String msg) {
        runOnUiThread(() -> {
            logView.append(msg + "\n");
            logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void appendException(final String prefix, final Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        appendMessage(prefix + ": " + t.getMessage());
        appendMessage(sw.toString());
    }

    private void showToast(final String msg) {
        runOnUiThread(() -> {
            Toast toast = Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        });
    }
    
    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        showToast(label + " copied to clipboard");
    }
    
    private void initApiServer() {
        // Load saved port from preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        apiPort = prefs.getInt(PREF_API_PORT, OllamaApiServer.DEFAULT_PORT);
        
        appendMessage("API server initialized (port: " + apiPort + ")");
    }
    
    private void updateApiServerUI() {
        if (isServiceRunning) {
            apiServerButton.setText("Stop API Server");
            apiServerStatusMain.setText("API: Running on port " + apiPort);
        } else {
            apiServerButton.setText("Start API Server");
            apiServerStatusMain.setText("API: Stopped");
        }
    }
    
    private void toggleApiServer() {
        if (isServiceRunning) {
            stopApiService();
        } else {
            startApiService();
        }
    }
    
    private void startApiService() {
        Intent serviceIntent = new Intent(this, OllamaForegroundService.class);
        serviceIntent.setAction(OllamaForegroundService.ACTION_START);
        serviceIntent.putExtra("port", apiPort);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        isServiceRunning = true;
        updateApiServerUI();
        appendMessage("Starting API server service on port " + apiPort);
    }
    
    private void stopApiService() {
        Intent serviceIntent = new Intent(this, OllamaForegroundService.class);
        serviceIntent.setAction(OllamaForegroundService.ACTION_STOP);
        startService(serviceIntent);
        
        isServiceRunning = false;
        updateApiServerUI();
        appendMessage("Stopping API server service");
    }
    
    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Update service status when returning to the activity
        isServiceRunning = isServiceRunning(OllamaForegroundService.class);
        updateApiServerUI();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Service continues running in background - don't stop it here
    }
}
