package com.micklab.llama;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.app.ActivityManager;
import android.Manifest;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.widget.EditText;
import android.widget.Button;

import org.json.JSONException;

import java.io.IOException;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_SETTINGS = 1;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 2;
    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_API_PORT = "api_port";
    private static final String[] STREAM_REMOVE_MARKERS = {
            "<|im_start|>", "<|IM_START|>", "<|im_end|>", "<|IM_END|>", "<|im_end|", "<|IM_END|"
    };
    
    private static String stripResponseMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String marker : STREAM_REMOVE_MARKERS) {
            result = result.replace(marker, "");
        }
        return result;
    }
    
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
    private Button updateLogButton;
    private Button copyLogButton;
    private boolean isViewingLog = false;
    private String savedOutputText = null;
    private TextView apiServerStatusMain;
    
    // Model Manager (singleton)
    private ModelManager modelManager;
    
    // Configuration
    private ConfigurationManager configManager;
    private ConfigurationManager.Configuration currentConfig;
    
    // API Server (via Foreground Service)
    private int apiPort = OllamaApiServer.DEFAULT_PORT;
    private boolean isServiceRunning = false;
    
    // Timestamp formatter for log messages
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private static class StreamOutputFilter {
        private final StringBuilder pending = new StringBuilder();
        private final int holdbackLength;

        StreamOutputFilter() {
            int maxMarkerLength = 0;
            for (String marker : STREAM_REMOVE_MARKERS) {
                if (marker.length() > maxMarkerLength) {
                    maxMarkerLength = marker.length();
                }
            }
            holdbackLength = Math.max(0, maxMarkerLength - 1);
        }

        String onToken(String token) {
            if (token == null || token.isEmpty()) {
                return "";
            }
            pending.append(token);
            return flushFiltered(false);
        }

        String onComplete() {
            return flushFiltered(true);
        }

        private String flushFiltered(boolean flushAll) {
            if (pending.length() == 0) {
                return "";
            }
            String filtered = stripMarkers(pending.toString());
            pending.setLength(0);
            if (flushAll) {
                return filtered;
            }
            int emitLength = filtered.length() - holdbackLength;
            if (emitLength > 0) {
                String out = filtered.substring(0, emitLength);
                pending.append(filtered, emitLength, filtered.length());
                return out;
            }
            pending.append(filtered);
            return "";
        }

        private String stripMarkers(String text) {
            return MainActivity.stripResponseMarkers(text);
        }
    }
    
    // Broadcast receiver for service logs
    private BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (OllamaForegroundService.ACTION_LOG.equals(action)) {
                String message = intent.getStringExtra(OllamaForegroundService.EXTRA_LOG_MESSAGE);
                if (message != null) {
                    appendMessage(message);
                }
            } else if (OllamaForegroundService.ACTION_STATUS_CHANGED.equals(action)) {
                String status = intent.getStringExtra(OllamaForegroundService.EXTRA_STATUS);
                int port = intent.getIntExtra(OllamaForegroundService.EXTRA_PORT, apiPort);
                isServiceRunning = "running".equals(status);
                apiPort = port;
                updateApiServerUI();
            }
        }
    };

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
        updateLogButton = findViewById(R.id.updateLogButton);
        copyLogButton = findViewById(R.id.copyLogButton);
        apiServerStatusMain = findViewById(R.id.apiServerStatusMain);

        appendMessage("UI ready.");

        // Request notification permission for Android 13+
        requestNotificationPermission();

        // Initialize ModelManager singleton
        modelManager = ModelManager.getInstance(this);
        // Ensure UI buttons start enabled
        sendButton.setEnabled(true);
        initModelButton.setEnabled(true);

        // Set up button listeners
        settingsButton.setOnClickListener(v -> openSettings());
        initModelButton.setOnClickListener(v -> reinitializeModel());
        viewLogButton.setOnClickListener(v -> toggleViewLog());
        clearLogButton.setOnClickListener(v -> clearLogFile());
        apiServerButton.setOnClickListener(v -> toggleApiServer());
        copyOutputButton.setOnClickListener(v -> copyToClipboard("Output", outputView.getText().toString()));
        updateLogButton.setOnClickListener(v -> { if (isViewingLog) { refreshLogView(); } });
        copyLogButton.setOnClickListener(v -> copyToClipboard("Log", logView.getText().toString()));
        updateLogButton.setVisibility(Button.GONE);
        
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

            if (isViewingLog) {
                isViewingLog = false;
                viewLogButton.setText("View Log");
                updateLogButton.setEnabled(false);
                updateLogButton.setVisibility(Button.GONE);
            }
            
            // Check if busy
            if (!modelManager.tryAcquire()) {
                showToast("Model is busy processing another request");
                return;
            }
            
            // If model not loaded, load it first
            if (!modelManager.isModelLoaded()) {
                final String configName = resolveDirectInputConfigName();
                appendMessage("Model not loaded. Initial loading for profile \"" + configName + "\" may take some time...");
                new Thread(() -> {
                    try {
                        boolean loadSuccess = modelManager.loadConfiguration(configName);
                        if (!loadSuccess) {
                            modelManager.release();
                            runOnUiThread(() -> {
                                showToast("Failed to load model. Please check Settings.");
                                appendMessage("Model load failed.");
                            });
                            return;
                        }
                        runOnUiThread(() -> {
                            appendMessage("Model loaded successfully. Processing prompt...");
                        });
                        // Now proceed with generation
                        processGeneration(userPrompt);
                    } catch (Throwable t) {
                        modelManager.release();
                        appendException("Model load error", t);
                        showToast("Model load error: " + t.getMessage());
                    }
                }).start();
                return;
            }

            // Apply prompt template
            final String chatPrompt = applyPromptTemplate(userPrompt);

            appendMessage("Running generate...");
            outputView.setText("");
            new Thread(() -> {
                LlamaNative.TokenListener tListener = null;
                try {
                    // Set parameters before generating
                    if (currentConfig != null) {
                        modelManager.applyConfiguration(currentConfig);
                    }

                    if (currentConfig != null && currentConfig.streaming) {
                        final StreamOutputFilter streamOutputFilter = new StreamOutputFilter();
                        tListener = new LlamaNative.TokenListener() {
                            @Override
                            public void onToken(String token) {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "stream token len=" + (token != null ? token.length() : 0));
                                }
                                final String filteredToken = streamOutputFilter.onToken(token);
                                if (!filteredToken.isEmpty()) {
                                    runOnUiThread(() -> outputView.append(filteredToken));
                                }
                            }

                            @Override
                            public void onComplete() {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "stream complete");
                                }
                                final String tail = streamOutputFilter.onComplete();
                                runOnUiThread(() -> {
                                    if (!tail.isEmpty()) {
                                        outputView.append(tail);
                                    }
                                    appendMessage("streaming complete");
                                });
                            }

                            @Override
                            public void onError(String error) {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "stream error: " + error);
                                }
                                final String safeError = (error == null || "null".equalsIgnoreCase(error.trim()))
                                        ? "unknown error" : error;
                                runOnUiThread(() -> appendMessage("streaming error: " + safeError));
                            }
                        };
                        modelManager.getLlama().setTokenListener(tListener);
                    } else {
                        modelManager.getLlama().setTokenListener(null);
                    }

                    String gen = modelManager.generate(chatPrompt);
                    final String finalGen = gen;
                    runOnUiThread(() -> {
                        appendMessage("generate() returned.");
                        outputView.setText(stripResponseMarkers(finalGen));
                    });
                } catch (Throwable t) {
                    appendException("generate() threw", t);
                    showToast("Generate error: " + t.getMessage());
                } finally {
                    modelManager.getLlama().setTokenListener(null);
                    modelManager.release();
                }
            }).start();
        });
    }
    
    private void processGeneration(String userPrompt) {
        final String chatPrompt = applyPromptTemplate(userPrompt);
        
        runOnUiThread(() -> {
            if (isViewingLog) {
                isViewingLog = false;
                viewLogButton.setText("View Log");
                updateLogButton.setEnabled(false);
                updateLogButton.setVisibility(Button.GONE);
            }
            appendMessage("Running generate...");
            outputView.setText("");
        });
        
        LlamaNative.TokenListener tListener = null;
        try {
            // Set parameters before generating
            if (currentConfig != null) {
                modelManager.applyConfiguration(currentConfig);
            }

            if (currentConfig != null && currentConfig.streaming) {
                final StreamOutputFilter streamOutputFilter = new StreamOutputFilter();
                tListener = new LlamaNative.TokenListener() {
                    @Override
                    public void onToken(String token) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "stream token len=" + (token != null ? token.length() : 0));
                        }
                        final String filteredToken = streamOutputFilter.onToken(token);
                        if (!filteredToken.isEmpty()) {
                            runOnUiThread(() -> outputView.append(filteredToken));
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "stream complete");
                        }
                        final String tail = streamOutputFilter.onComplete();
                        runOnUiThread(() -> {
                            if (!tail.isEmpty()) {
                                outputView.append(tail);
                            }
                            appendMessage("streaming complete");
                        });
                    }

                    @Override
                    public void onError(String error) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "stream error: " + error);
                        }
                        final String safeError = (error == null || "null".equalsIgnoreCase(error.trim()))
                                ? "unknown error" : error;
                        runOnUiThread(() -> appendMessage("streaming error: " + safeError));
                    }
                };
                modelManager.getLlama().setTokenListener(tListener);
            } else {
                modelManager.getLlama().setTokenListener(null);
            }
            
            String gen = modelManager.generate(chatPrompt);
            final String finalGen = gen;
            runOnUiThread(() -> {
                appendMessage("generate() returned.");
                outputView.setText(stripResponseMarkers(finalGen));
            });
        } catch (Throwable t) {
            appendException("generate() threw", t);
            showToast("Generate error: " + t.getMessage());
        } finally {
            modelManager.getLlama().setTokenListener(null);
            modelManager.release();
        }
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
                    
                    // Apply configuration to model immediately if loaded
                    if (modelManager.isModelLoaded()) {
                        modelManager.applyConfiguration(currentConfig);
                        appendMessage("Applied configuration to model");
                    }
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
        
        appendMessage("Initializing model reload...");
        
        // Disable buttons during reinitialization
        sendButton.setEnabled(false);
        initModelButton.setEnabled(false);
        
        new Thread(() -> {
            try {
                // 1. Disconnect all API connections
                appendMessage("Disconnecting all API connections...");
                if (isServiceRunning) {
                    Intent disconnectIntent = new Intent(this, OllamaForegroundService.class);
                    disconnectIntent.setAction(OllamaForegroundService.ACTION_DISCONNECT_ALL);
                    startService(disconnectIntent);
                }
                
                // 2. Reset busy state (force clear any stuck 503 state)
                appendMessage("Resetting busy state...");
                modelManager.resetBusy();
                
                // Small delay to ensure connections are closed
                Thread.sleep(200);
                
                // 3. Free and reload model
                appendMessage("Freeing current model...");
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
                            // Re-enable buttons
                            sendButton.setEnabled(true);
                            initModelButton.setEnabled(true);
                        });
                    } finally {
                        modelManager.release();
                    }
                } else {
                    runOnUiThread(() -> {
                        appendMessage("Could not acquire model lock for reinitialization");
                        sendButton.setEnabled(true);
                        initModelButton.setEnabled(true);
                    });
                }
            } catch (Throwable t) {
                appendException("Model re-initialization error", t);
                showToast("Error: " + t.getMessage());
                runOnUiThread(() -> {
                    sendButton.setEnabled(true);
                    initModelButton.setEnabled(true);
                });
            }
        }).start();
    }
    
    private void viewLogFile() {
        // Deprecated: use toggleViewLog which manages state
        toggleViewLog();
    }

    private void toggleViewLog() {
        if (!isViewingLog) {
            // Save current output and display logs
            savedOutputText = outputView.getText().toString();
            isViewingLog = true;
            viewLogButton.setText("Hide Log");
            updateLogButton.setEnabled(true);
            updateLogButton.setVisibility(Button.VISIBLE);
            refreshLogView();
        } else {
            // Restore output view
            isViewingLog = false;
            viewLogButton.setText("View Log");
            updateLogButton.setEnabled(false);
            updateLogButton.setVisibility(Button.GONE);
            if (savedOutputText != null) {
                outputView.setText(savedOutputText);
            }
        }
    }

    private void refreshLogView() {
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

    private String resolveDirectInputConfigName() {
        String configName = (currentConfig != null) ? currentConfig.name : null;
        if (configName == null) {
            return "default";
        }
        String trimmed = configName.trim();
        if (trimmed.isEmpty() || "default".equalsIgnoreCase(trimmed)) {
            return "default";
        }
        return trimmed;
    }
    
    private String applyPromptTemplate(String userInput) {
        // Use PromptTemplateManager for direct input
        String ggufChatTemplate = modelManager.getLlama().getChatTemplate();
        String customTemplate = (currentConfig != null) ? currentConfig.customChatTemplate : null;
        String settingsSystemPrompt = (currentConfig != null) ? currentConfig.systemPrompt : null;
        String modelPath = modelManager.getCurrentModelPath();

        PromptTemplateManager.PromptBuildResult result =
                PromptTemplateManager.buildPromptForDirectInputWithSelection(
                        userInput,
                        customTemplate,
                        ggufChatTemplate,
                        settingsSystemPrompt,
                        modelPath);
        logTemplateSelection("direct", result.selection);
        return result.prompt;
    }

    private void logTemplateSelection(String context, PromptTemplateManager.TemplateSelectionResult selection) {
        if (selection == null) {
            return;
        }
        String message = "Prompt template selection (" + context + "): " + selection.reason;
        Log.i(TAG, message);
        appendMessage(message);
    }

    private void appendMessage(final String msg) {
        runOnUiThread(() -> {
            String timestamp = timestampFormat.format(new Date());
            logView.append("[" + timestamp + "] " + msg + "\n");
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
            // Check notification permission before starting service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    showToast("Notification permission required for background service");
                    requestNotificationPermission();
                    return;
                }
            }
            startApiService();
        }
    }
    
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_PERMISSION);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendMessage("Notification permission granted");
            } else {
                appendMessage("Notification permission denied - background service may not show notifications");
            }
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
        // Register broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(OllamaForegroundService.ACTION_LOG);
        filter.addAction(OllamaForegroundService.ACTION_STATUS_CHANGED);
        registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        
        // Update service status when returning to the activity
        isServiceRunning = isServiceRunning(OllamaForegroundService.class);
        updateApiServerUI();
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // Unregister broadcast receiver
        try {
            unregisterReceiver(serviceReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Service continues running in background - don't stop it here
    }
}
