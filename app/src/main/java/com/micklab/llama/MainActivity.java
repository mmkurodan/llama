package com.micklab.llama;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import android.widget.EditText;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_SETTINGS = 1;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 2;
    private static final int REQUEST_CREATE_LOG_FILE = 3;
    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_API_PORT = "api_port";
    private static final String PREF_LOG_LEVEL = "log_level";
    private static final String PREF_SHOW_STARTUP_INSTRUCTIONS = "show_startup_instructions";
    private static final String PREF_SHOW_API_ENABLE_PROMPT = "show_api_enable_prompt";
    private static final String[] LEGACY_PREF_SHOW_STARTUP_INSTRUCTIONS_KEYS = {
            "show_startup_message",
            "show_quick_start"
    };
    private static final int LOG_LEVEL_MAX_DEBUG = 0;
    private static final int LOG_LEVEL_INFO = 2;
    private static final int LOG_DISPLAY_MAX_LINES = 100;
    private static final int LOG_DISPLAY_MAX_DEBUG_LINES = 1000;
    private static final int PROCESSING_LOG_MAX_CHARS = 64 * 1024;
    private static final int PROCESSING_LOG_TRIM_TARGET_CHARS = 48 * 1024;
    private static String stripResponseMarkers(String text) {
        return ResponseMarkerSanitizer.stripResponseMarkers(text);
    }
    
    private TextView logView;           // log view (append-only)
    private ScrollView mainScrollView;
    private TextView outputView;
    private MaxHeightScrollView outputScroll;   // bounds outputView to <=50% screen
    private MaxHeightScrollView logScroll;       // bounds logView to <=50% screen
    private TextView statsView;         // generation speed (tok/s) + device temperature (under output)
    private TextView headerStatsView;   // top header: speed + temp + active backend
    private Spinner  profileSpinner;    // profile (configuration) selector in the direct section
    private TextView directSectionHeader;   // collapsible header for the direct-run section
    private View     directSectionContent;  // collapsible body of the direct-run section
    private View     processingSectionContent;  // collapsible body of the processing/log section
    private TextView webChatTitle;          // "Web AI Chat" card title
    private Button   openWebUiButton;       // opens the Web UI in the browser
    private ConnectivityManager connectivityManager;
    private TextView promptLabel;
    private TextView outputSectionLabel;
    private TextView processingSectionLabel;

    private EditText promptInput;
    private Button sendButton;
    private Button settingsButton;
    private Button initModelButton;
    private Button viewLogButton;
    private Button clearLogButton;
    private Button apiServerButton;
    private Button downloadLogButton;
    private Button updateLogButton;
    private boolean isViewingLog = false;
    private String savedProcessingText = null;   // live processing-status text saved while viewing the log file
    private String displayedLogContent = null;
    private String pendingLogDownloadContent = null;
    private TextView apiServerStatusMain;
    
    // Model Manager (singleton)
    private ModelManager modelManager;
    
    // Configuration
    private ConfigurationManager configManager;
    private ConfigurationManager.Configuration currentConfig;
    private String currentDisplayLanguage;
    
    // API Server (via Foreground Service)
    private int apiPort = OllamaApiServer.DEFAULT_PORT;
    private boolean isServiceRunning = false;
    private boolean pendingApiStartAfterPermission = false;
    private boolean continueStartupDialogsAfterPermission = false;
    private final AtomicInteger directGenerationSessionId = new AtomicInteger(0);
    
    // Timestamp formatter for log messages
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private final Handler busyStateHandler = new Handler(Looper.getMainLooper());
    private final Runnable busyStateUpdater = new Runnable() {
        @Override
        public void run() {
            updateSettingsButtonForBusyState();
            busyStateHandler.postDelayed(this, 200);
        }
    };
    // Periodically refresh the top header so it reflects the latest run (direct OR API).
    private final Runnable statsUpdater = new Runnable() {
        @Override
        public void run() {
            updateStatsView();
            busyStateHandler.postDelayed(this, 2000);
        }
    };

    private static class StreamOutputFilter {
        private final StringBuilder pending = new StringBuilder();
        private final int holdbackLength;

        StreamOutputFilter() {
            holdbackLength = ResponseMarkerSanitizer.getStreamingHoldbackLength();
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
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        currentDisplayLanguage = AppLanguageManager.getOrInitDisplayLanguage(this);
        
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
        mainScrollView = findViewById(R.id.mainScrollView);
        logView = findViewById(R.id.logView);
        outputView = findViewById(R.id.outputView);
        // Bound the output/log text areas to at most 50% of the screen height; scroll beyond.
        outputScroll = findViewById(R.id.outputScroll);
        logScroll = findViewById(R.id.logScroll);
        final int halfScreenPx = getResources().getDisplayMetrics().heightPixels / 2;
        if (outputScroll != null) outputScroll.setMaxHeightPx(halfScreenPx);
        if (logScroll != null) logScroll.setMaxHeightPx(halfScreenPx);
        statsView = findViewById(R.id.statsView);
        headerStatsView = findViewById(R.id.headerStatsView);
        profileSpinner = findViewById(R.id.profileSpinner);
        directSectionHeader = findViewById(R.id.directSectionHeader);
        directSectionContent = findViewById(R.id.directSectionContent);
        webChatTitle = findViewById(R.id.webChatTitle);
        openWebUiButton = findViewById(R.id.openWebUiButton);
        processingSectionContent = findViewById(R.id.processingSectionContent);
        processingSectionLabel = findViewById(R.id.processingSectionLabel);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        setupWebUiUrl();
        setupDirectSectionToggle();
        setupProcessingSectionToggle();
        setupProfileSpinner();
        updateStatsView();   // 起動時に温度を表示 (速度は生成完了後に更新)
        promptLabel = findViewById(R.id.promptLabel);
        outputSectionLabel = findViewById(R.id.outputSectionLabel);
        promptInput = findViewById(R.id.promptInput);
        sendButton = findViewById(R.id.sendButton);
        settingsButton = findViewById(R.id.settingsButton);
        initModelButton = findViewById(R.id.initModelButton);
        viewLogButton = findViewById(R.id.viewLogButton);
        clearLogButton = findViewById(R.id.clearLogButton);
        apiServerButton = findViewById(R.id.apiServerButton);
        downloadLogButton = findViewById(R.id.downloadLogButton);
        updateLogButton = findViewById(R.id.updateLogButton);
        apiServerStatusMain = findViewById(R.id.apiServerStatusMain);
        applyLocalizedUiText();
        // outputView/logView は textIsSelectable=true で選択コピー可能。
        // ScrollingMovementMethod を設定すると選択が無効になるため設定しない
        // (長文はページ全体の ScrollView でスクロールする)。

        appendMessage("UI ready.");

        // Initialize ModelManager singleton
        modelManager = ModelManager.getInstance(this);
        // Ensure UI buttons start enabled
        sendButton.setEnabled(true);
        initModelButton.setEnabled(true);
        updateSettingsButtonForBusyState();

        // Set up button listeners
        settingsButton.setOnClickListener(v -> openSettings());
        initModelButton.setOnClickListener(v -> reinitializeModel());
        viewLogButton.setOnClickListener(v -> toggleViewLog());
        clearLogButton.setOnClickListener(v -> clearLogFile());
        apiServerButton.setOnClickListener(v -> toggleApiServer());
        downloadLogButton.setOnClickListener(v -> downloadDisplayedLog());
        updateLogButton.setOnClickListener(v -> { if (isViewingLog) { refreshLogView(); } });
        updateLogButton.setVisibility(Button.GONE);
        downloadLogButton.setEnabled(true);
        
        // Initialize API server via Foreground Service
        initApiServer();
        
        // Check if service is already running
        isServiceRunning = isServiceRunning(OllamaForegroundService.class);
        updateApiServerUI();

        // Re-assert the persisted "API enabled" intent: if the user previously enabled the server,
        // keep it running across process death / task removal / reboot until they explicitly disable
        // it. (apiPort was just loaded by initApiServer() above.)
        if (!isServiceRunning && OllamaForegroundService.isApiEnabled(this)) {
            appendMessage("Re-enabling API/WebUI server (persisted enabled state)");
            startApiService();
        }

        // Send button behavior
        sendButton.setOnClickListener(v -> {
            final String userPrompt = promptInput.getText().toString();
            if (userPrompt == null || userPrompt.trim().isEmpty()) {
                showToast("Please enter a prompt");
                return;
            }
            // First-run guard: if the model still needs downloading, confirm before starting
            // (the download then happens as part of loading the configuration).
            if (!isCurrentModelDownloaded()) {
                confirmFirstModelDownloadThen(() -> startDirectGeneration(userPrompt));
            } else {
                startDirectGeneration(userPrompt);
            }
        });

        showStartupDialogs();
    }
    
    // True when the active configuration's model file is already on the device (or there is
    // nothing to download). Used to gate Send / Open-in-browser behind a first-download notice.
    private boolean isCurrentModelDownloaded() {
        String modelReference = (currentConfig != null) ? currentConfig.modelUrl : null;
        return modelManager == null || modelManager.isModelReferenceDownloaded(modelReference);
    }

    // First-run notice: downloading the default model takes time and a large amount of data.
    private void confirmFirstModelDownloadThen(Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle(localizedText("モデルのダウンロード", "Download model"))
                .setMessage(localizedText(
                        "初回のみ、モデルのダウンロードに長時間と大容量の通信（数GBになる場合があります）が発生します。可能な限り Wi-Fi での実行を推奨します。続行しますか？",
                        "The model will be downloaded now. The first time only, this takes time and a large amount of data (possibly several GB). Wi-Fi is strongly recommended. Continue?"))
                .setPositiveButton(localizedText("続行", "Continue"), (d, w) -> onConfirm.run())
                .setNegativeButton(localizedText("キャンセル", "Cancel"), null)
                .show();
    }

    private void openWebUiInBrowser() {
        final String url = buildWebUiUrl();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            showToast(localizedText("ブラウザを開けません: ", "Cannot open browser: ") + url);
        }
    }

    private void startDirectGeneration(final String userPrompt) {
        exitLogViewToStatus();

        // Check if busy
        if (!modelManager.tryAcquire()) {
            showToast("Model is busy processing another request");
            return;
        }
        updateSettingsButtonForBusyState();
        final int generationSessionId = beginDirectGenerationSession();

        final String configName = resolveDirectInputConfigName();
        // If the selected configuration model is not loaded, load it first
        if (shouldLoadConfigurationModelForDirectInput()) {
            appendMessage(modelManager.isModelLoaded()
                    ? "Loaded model differs from selected profile. Loading profile \"" + configName + "\"..."
                    : "Model not loaded. Initial loading for profile \"" + configName + "\" may take some time...");
            new Thread(() -> {
                try {
                    boolean loadSuccess = modelManager.loadConfiguration(configName);
                    if (!loadSuccess) {
                        modelManager.release();
                        showToast("Failed to load model. Please check Settings.");
                        appendMessage("Model load failed.");
                        return;
                    }
                    if (!shouldContinueDirectGeneration(generationSessionId)) {
                        modelManager.release();
                        runOnUiThread(this::updateSettingsButtonForBusyState);
                        appendMessage("Prompt cancelled before generation started.");
                        return;
                    }
                    appendMessage("Model loaded successfully. Processing prompt...");
                    // Now proceed with generation
                    processGeneration(userPrompt, generationSessionId);
                } catch (Throwable t) {
                    modelManager.release();
                    appendException("Model load error", t);
                    showToast("Model load error: " + t.getMessage());
                }
            }).start();
            return;
        }
        new Thread(() -> processGeneration(userPrompt, generationSessionId)).start();
    }

    private void processGeneration(String userPrompt, int generationSessionId) {
        if (!shouldContinueDirectGeneration(generationSessionId)) {
            modelManager.release();
            runOnUiThread(this::updateSettingsButtonForBusyState);
            return;
        }
        runOnUiThread(() -> {
            if (!isDirectGenerationSessionActive(generationSessionId)) {
                return;
            }
            exitLogViewToStatus();
            appendMessage("Running generate...");
            outputView.setText("");
            scrollTextViewToTop(outputView);
        });
        
        LlamaNative.TokenListener tListener = null;
        try {
            if (!shouldContinueDirectGeneration(generationSessionId)) {
                return;
            }
            // Set parameters before generating
            if (currentConfig != null) {
                modelManager.applyConfiguration(currentConfig);
            }

            if (hasSharedToolConfig()) {
                SharedToolManager.ChatResult toolResult = SharedToolManager.generateWithTools(
                        this,
                        modelManager.getLlama(),
                        buildDirectInputMessages(userPrompt),
                        null,
                        currentConfig != null ? currentConfig.customChatTemplate : null,
                        currentConfig != null ? currentConfig.systemPrompt : null,
                        null,
                        false,
                        currentConfig == null || currentConfig.enableThinking,
                        new byte[0][],
                        true,
                        true
                );
                if (toolResult != null) {
                    final String renderedOutput;
                    if (toolResult.content != null && !toolResult.content.isEmpty()) {
                        renderedOutput = stripResponseMarkers(toolResult.content);
                    } else if (toolResult.toolCalls != null && toolResult.toolCalls.length() > 0) {
                        renderedOutput = toolResult.toolCalls.toString();
                    } else {
                        renderedOutput = "";
                    }
                    runIfDirectGenerationSessionActive(generationSessionId, () -> {
                        appendMessage("shared MCP generation complete");
                        outputView.setText(renderedOutput);
                        scrollTextViewToTop(outputView);
                    });
                    return;
                }
            }

            final String chatPrompt;
            try {
                chatPrompt = applyPromptTemplate(userPrompt);
            } catch (RuntimeException e) {
                appendException("Prompt build error", e);
                showToast("Prompt build error: " + (e.getMessage() != null ? e.getMessage() : "unknown"));
                return;
            }
            if (!shouldContinueDirectGeneration(generationSessionId)) {
                return;
            }
            logMaxDebugPayload("direct.prompt", chatPrompt);

            if (currentConfig != null && currentConfig.streaming) {
                final StreamOutputFilter streamOutputFilter = new StreamOutputFilter();
                tListener = new LlamaNative.TokenListener() {
                    @Override
                    public void onToken(String token) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "stream token len=" + (token != null ? token.length() : 0));
                        }
                        logMaxDebugPayload("direct.stream.model.token", token);
                        final String filteredToken = streamOutputFilter.onToken(token);
                        if (!filteredToken.isEmpty()) {
                            logMaxDebugPayload("direct.stream.filtered.token", filteredToken);
                            runIfDirectGenerationSessionActive(generationSessionId, () -> {
                                outputView.append(filteredToken);
                                scrollTextViewToBottom(outputView);
                            });
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "stream complete");
                        }
                        final String tail = streamOutputFilter.onComplete();
                        final String metrics = buildPerfMetricsSuffix();
                        final boolean thinkingOff = currentConfig == null || !currentConfig.enableThinking;
                        runIfDirectGenerationSessionActive(generationSessionId, () -> {
                            if (!tail.isEmpty()) {
                                logMaxDebugPayload("direct.stream.tail", tail);
                                outputView.append(tail);
                            }
                            if (thinkingOff) {
                                String full = PromptTemplateManager.stripThinkingBlocks(
                                        outputView.getText().toString());
                                outputView.setText(full);
                            }
                            if (!metrics.isEmpty()) {
                                outputView.append(metrics);
                            }
                            scrollTextViewToBottom(outputView);
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
                        runIfDirectGenerationSessionActive(generationSessionId, () -> appendMessage("streaming error: " + safeError));
                    }
                };
                modelManager.getLlama().setTokenListener(tListener);
            } else {
                modelManager.getLlama().setTokenListener(null);
            }

            if (!shouldContinueDirectGeneration(generationSessionId)) {
                return;
            }
            String gen = modelManager.generate(chatPrompt);
            logMaxDebugPayload("direct.nonstream.model.raw", gen);
            final boolean thinkingEnabled = currentConfig != null && currentConfig.enableThinking;
            final String processedGen = thinkingEnabled
                    ? PromptTemplateManager.stripBlankThinkingBlocks(gen)
                    : PromptTemplateManager.stripThinkingBlocks(gen);
            final String finalGen = processedGen;
            final String metrics = buildPerfMetricsSuffix();
            runIfDirectGenerationSessionActive(generationSessionId, () -> {
                appendMessage("generate() returned.");
                String safe = stripResponseMarkers(finalGen);
                logMaxDebugPayload("direct.nonstream.output", safe);
                outputView.setText(metrics.isEmpty() ? safe : safe + metrics);
                scrollTextViewToTop(outputView);
            });
        } catch (Throwable t) {
            if (shouldContinueDirectGeneration(generationSessionId)) {
                appendException("generate() threw", t);
                showToast("Generate error: " + t.getMessage());
            }
        } finally {
            modelManager.getLlama().setTokenListener(null);
            modelManager.release();
            runOnUiThread(() -> {
                updateSettingsButtonForBusyState();
                // 速度・温度の更新を全経路 (streaming / 非streaming / MCP) で実施
                updateStatsView();
            });
        }
    }

    private JSONArray buildDirectInputMessages(String userPrompt) throws JSONException {
        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);
        messages.put(userMessage);
        return messages;
    }
    
    private void openSettings() {
        if (modelManager != null && modelManager.isBusy()) {
            showToast("Model is busy processing another request");
            return;
        }
        Intent intent = new Intent(this, SettingsActivity.class);
        if (currentConfig != null) {
            intent.putExtra(SettingsActivity.EXTRA_CONFIG_NAME, currentConfig.name);
        }
        startActivityForResult(intent, REQUEST_SETTINGS);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CREATE_LOG_FILE) {
            handleLogDownloadResult(resultCode, data);
            return;
        }
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK && data != null) {
            String configName = data.getStringExtra(SettingsActivity.EXTRA_CONFIG_NAME);
            if (configName != null) {
                try {
                    currentConfig = configManager.loadConfiguration(configName);
                    appendMessage("Loaded configuration: " + configName);
                    setupProfileSpinner();   // 設定画面での追加/改名/選択を反映
                    // 設定保存後は次回実行(直接/API)で必ずモデルを再ロードして反映する
                    modelManager.requestReloadOnNextLoad();
                    // Notify WebUI that settings changed so it re-applies the new profile values.
                    modelManager.notifySettingsChanged();
                    
                    // Apply configuration immediately only when selected model already matches.
                    if (modelManager.isModelLoaded()) {
                        if (isLoadedModelMatchingCurrentConfiguration()) {
                            modelManager.applyConfiguration(currentConfig);
                            appendMessage("Applied configuration to model");
                        } else {
                            appendMessage("Selected profile model will be loaded on next prompt.");
                        }
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
        final String configName = resolveDirectInputConfigName();
        final boolean wasBusy = modelManager.isBusy();
        cancelDirectGenerationSessions();
        if (!isViewingLog) {
            outputView.setText("");
        }
        updateSettingsButtonForBusyState();
        appendMessage(wasBusy
                ? "Stopping current model work and re-initializing profile \"" + configName + "\"..."
                : "Re-initializing model for profile \"" + configName + "\"...");

        if (isServiceRunning(OllamaForegroundService.class)) {
            disconnectApiClients();
            appendMessage("Disconnected API clients before model re-initialization.");
        }

        new Thread(() -> {
            try {
                ModelManager.ForceReinitializeResult result = modelManager.forceReinitializeConfiguration(configName);
                if (result == ModelManager.ForceReinitializeResult.SUCCESS) {
                    appendMessage("Model re-initialized successfully.");
                    showToast("Model re-initialized successfully");
                } else if (result == ModelManager.ForceReinitializeResult.ALREADY_PENDING) {
                    appendMessage("Model re-initialization is already in progress.");
                    showToast("Model re-initialization is already in progress");
                } else {
                    appendMessage("Model re-initialization failed.");
                    showToast("Model re-initialization failed");
                }
            } catch (Throwable t) {
                appendException("Model re-initialization error", t);
                showToast("Model re-initialization error: " + (t.getMessage() != null ? t.getMessage() : "unknown"));
            } finally {
                runOnUiThread(this::updateSettingsButtonForBusyState);
            }
        }).start();
    }
    
    private void viewLogFile() {
        // Deprecated: use toggleViewLog which manages state
        toggleViewLog();
    }

    private void toggleViewLog() {
        if (!isViewingLog) {
            // 処理状況エリア(logView)を、保存しておいて全ログファイル表示に切り替える
            savedProcessingText = logView.getText() != null ? logView.getText().toString() : "";
            isViewingLog = true;
            viewLogButton.setText(localizedText("状況に戻る", "Show Status"));
            if (clearLogButton != null) clearLogButton.setText(localizedText("ログ消去", "Clear Log"));
            updateLogButton.setEnabled(true);
            updateLogButton.setVisibility(Button.VISIBLE);
            downloadLogButton.setEnabled(true);
            refreshLogView();
        } else {
            exitLogViewToStatus();
        }
    }

    /** Switch the processing area (logView) back from the log file to the live status text. */
    private void exitLogViewToStatus() {
        if (!isViewingLog) {
            return;
        }
        isViewingLog = false;
        displayedLogContent = null;
        viewLogButton.setText(localizedText("ログ表示", "View Log"));
        if (clearLogButton != null) clearLogButton.setText(localizedText("消去", "Clear"));
        updateLogButton.setEnabled(false);
        updateLogButton.setVisibility(Button.GONE);
        if (logView != null && savedProcessingText != null) {
            logView.setText(savedProcessingText);
            scrollTextViewToBottom(logView);
        }
    }

    private void refreshLogView() {
        File appFilesDir = getAppFilesBaseDir();
        File logFile = new File(appFilesDir, "ollama.log");
        if (!logFile.exists()) {
            showToast("Log file does not exist");
            return;
        }

        new Thread(() -> {
            try {
                final String logContent = readDisplayLogContent(appFilesDir);
                runOnUiThread(() -> {
                    displayedLogContent = logContent;
                    logView.setText(logContent);
                    scrollTextViewToBottom(logView);
                    showToast(isMaxDebugEnabled()
                            ? "Displaying MAX DEBUG log"
                            : "Displaying latest " + LOG_DISPLAY_MAX_LINES + " log lines");
                });
            } catch (IOException e) {
                Log.e(TAG, "Failed to read log file", e);
                showToast("Failed to read log file: " + e.getMessage());
            }
        }).start();
    }

    private File getAppFilesBaseDir() {
        File externalDir = getExternalFilesDir(null);
        return externalDir != null ? externalDir : getFilesDir();
    }

    private String readDisplayLogContent(File appFilesDir) throws IOException {
        File logFile = new File(appFilesDir, "ollama.log");
        return readLatestLogLines(
                logFile,
                isMaxDebugEnabled() ? LOG_DISPLAY_MAX_DEBUG_LINES : LOG_DISPLAY_MAX_LINES);
    }

    private String readLatestLogLines(File logFile, int maxLines) throws IOException {
        ArrayDeque<String> tail = new ArrayDeque<>(maxLines);
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == maxLines) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : tail) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
    
    /**
     * Returns a performance metrics suffix "(in:N out:M Xs 温度:T°C)" if showPerfMetrics is enabled,
     * otherwise returns an empty string.
     */
    private String buildPerfMetricsSuffix() {
        SharedPreferences prefs = getSharedPreferences("ollama_prefs", MODE_PRIVATE);
        if (!prefs.getBoolean("show_perf_metrics", false)) return "";
        LlamaNative llama = (modelManager != null) ? modelManager.getLlama() : null;
        if (llama == null) return "";
        int nIn   = llama.getLastNPromptTokens();
        int nOut  = llama.getLastNEvalTokens();
        double ms = llama.getLastTotalTimeMs();
        double sec = ms / 1000.0;
        String timeStr = (sec < 60.0)
                ? String.format(java.util.Locale.US, "%.1fs", sec)
                : String.format(java.util.Locale.US, "%dm%.1fs", (int)(sec / 60), sec % 60);
        String tpsStr = (sec > 0.0)
                ? String.format(java.util.Locale.US, "%.1ftok/s", nOut / sec)
                : "-";
        String tempStr = getDeviceTempString(this);
        return String.format(java.util.Locale.US,
                "\n(%s: %d  %s: %d  %s: %s  %s  %s: %s)",
                localizedText("入力", "in"), nIn,
                localizedText("出力", "out"), nOut,
                localizedText("時間", "time"), timeStr,
                tpsStr,
                localizedText("温度", "temp"), tempStr);
    }

    private static String getDeviceTempString(android.content.Context ctx) {
        try {
            Intent intent = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return "?°C";
            int tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenths == Integer.MIN_VALUE) return "?°C";
            return String.format(java.util.Locale.US, "%.1f°C", tenths / 10.0f);
        } catch (Exception e) {
            return "?°C";
        }
    }

    private void clearLogFile() {
        if (isViewingLog) {
            DiagnosticsLogger.clearLogFiles(this);
            displayedLogContent = null;
            logView.setText("");
            savedProcessingText = "";
            showToast(localizedText("ログを消去しました", "Logs cleared"));
        } else {
            logView.setText("");
            savedProcessingText = "";
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

    private int beginDirectGenerationSession() {
        return directGenerationSessionId.incrementAndGet();
    }

    private void cancelDirectGenerationSessions() {
        directGenerationSessionId.incrementAndGet();
    }

    private boolean isDirectGenerationSessionActive(int generationSessionId) {
        return directGenerationSessionId.get() == generationSessionId;
    }

    private boolean shouldContinueDirectGeneration(int generationSessionId) {
        return isDirectGenerationSessionActive(generationSessionId)
                && modelManager != null
                && !modelManager.isResetPendingOrInProgress();
    }

    private void runIfDirectGenerationSessionActive(int generationSessionId, Runnable action) {
        runOnUiThread(() -> {
            if (isDirectGenerationSessionActive(generationSessionId)) {
                action.run();
            }
        });
    }

    private boolean shouldLoadConfigurationModelForDirectInput() {
        return !modelManager.isModelLoaded() || !isLoadedModelMatchingCurrentConfiguration();
    }

    private boolean isLoadedModelMatchingCurrentConfiguration() {
        return modelManager.isLoadedConfigurationMatching(currentConfig);
    }
    
    private boolean hasSharedToolConfig() {
        return McpSettingsHelper.hasSharedToolConfigForNonWebUi(this);
    }

    private String applyPromptTemplate(String userInput) {
        // Use PromptTemplateManager for direct input
        String ggufChatTemplate = modelManager.getLlama().getChatTemplate();
        String customTemplate = (currentConfig != null) ? currentConfig.customChatTemplate : null;
        String settingsSystemPrompt = (currentConfig != null) ? currentConfig.systemPrompt : null;
        boolean enableThinking = currentConfig == null || currentConfig.enableThinking;
        String modelPath = modelManager.getCurrentModelPath();

        PromptTemplateManager.PromptBuildResult result =
                PromptTemplateManager.buildPromptForDirectInputWithSelection(
                        userInput,
                        customTemplate,
                        ggufChatTemplate,
                        settingsSystemPrompt,
                        modelPath,
                        enableThinking);
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

    private boolean isMaxDebugEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int defaultLogLevel = LOG_LEVEL_INFO;
        return prefs.getInt(PREF_LOG_LEVEL, defaultLogLevel) == LOG_LEVEL_MAX_DEBUG;
    }

    private void logMaxDebugPayload(String label, String payload) {
        if (!isMaxDebugEnabled()) {
            return;
        }
        String safe = payload == null ? "null" : payload;
        if (safe.isEmpty()) {
            String msg = "[MAX_DEBUG] " + label + " (empty)";
            Log.d(TAG, msg);
            appendMessage(msg);
            return;
        }
        final int chunkSize = 1800;
        int index = 0;
        for (int start = 0; start < safe.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, safe.length());
            String msg = "[MAX_DEBUG] " + label + " part=" + index + " \"" + safe.substring(start, end) + "\"";
            Log.d(TAG, msg);
            appendMessage(msg);
            index++;
        }
    }

    private void configureScrollableTextView(TextView textView) {
        if (textView == null) {
            return;
        }
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        textView.setMaxHeight(Math.max(1, screenHeight / 4));
        textView.setVerticalScrollBarEnabled(true);
        textView.setScrollbarFadingEnabled(false);
        textView.setMovementMethod(new ScrollingMovementMethod());
        textView.setOnTouchListener((v, event) -> {
            if (v.getParent() == null) {
                return false;
            }
            boolean canScroll = v.canScrollVertically(-1) || v.canScrollVertically(1);
            int action = event.getActionMasked();
            if (canScroll && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL
                    || !canScroll) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
    }

    private void scrollTextViewToTop(TextView textView) {
        if (textView == null) {
            return;
        }
        final android.view.ViewParent parent = textView.getParent();
        if (parent instanceof ScrollView) {
            final ScrollView sv = (ScrollView) parent;
            sv.post(() -> sv.smoothScrollTo(0, 0));
            return;
        }
        textView.post(() -> textView.scrollTo(0, 0));
    }

    private void scrollTextViewToBottom(TextView textView) {
        if (textView == null) {
            return;
        }
        final android.view.ViewParent parent = textView.getParent();
        if (parent instanceof ScrollView) {
            final ScrollView sv = (ScrollView) parent;
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            return;
        }
        textView.post(() -> {
            if (textView.getLayout() == null) {
                return;
            }
            int scrollAmount = textView.getLayout().getLineTop(textView.getLineCount()) - textView.getHeight();
            textView.scrollTo(0, Math.max(scrollAmount, 0));
        });
    }

    /** Update the stats line with the last generation speed (tok/s) and device temperature. */
    private void updateStatsView() {
        double tps = 0.0;
        String backend = "--";
        try {
            if (modelManager != null && modelManager.getLlama() != null) {
                tps = modelManager.getLlama().getLastGenerationSpeed();
                String b = modelManager.getLlama().getActiveBackend();
                if (b != null && !b.isEmpty()) {
                    backend = b;
                }
            }
        } catch (Throwable t) {
            // native getters unavailable — keep defaults
        }
        float tempC = readBatteryTempC();
        String speedStr = (tps > 0.0) ? String.format(Locale.US, "%.1f tok/s", tps) : "-- tok/s";
        String tempStr  = (!Float.isNaN(tempC)) ? String.format(Locale.US, "%.1f°C", tempC) : "--°C";
        // 結果エリア下の表示 (速度+温度)
        if (statsView != null) {
            statsView.setText("⚡ " + speedStr + "    🌡 " + tempStr);
        }
        // 最上部ヘッダ — ダイレクト/API 問わず「最後に実行された」モデル/速度/温度/バックエンド。
        // 定期更新 (statsUpdater) で API 実行時も自動反映される。
        if (headerStatsView != null) {
            String model = "—";
            try {
                if (modelManager != null) {
                    String mp = modelManager.getCurrentModelPath();
                    if (mp != null && !mp.isEmpty()) {
                        model = new File(mp).getName().replaceFirst("(?i)\\.gguf$", "");
                    }
                }
            } catch (Throwable t) {
                // ignore
            }
            headerStatsView.setText("📦 " + model
                    + "\n⚡ " + speedStr + "   🌡 " + tempStr + "   🧠 " + backend);
        }
    }

    // ---- Web AI Chat: a button that opens the Web UI in the browser ----
    private void setupWebUiUrl() {
        if (openWebUiButton == null) {
            return;
        }
        if (webChatTitle != null) {
            webChatTitle.setText(localizedText("Web AI チャット", "Web AI Chat"));
        }
        openWebUiButton.setText(localizedText("ブラウザで開く", "Open in browser"));
        openWebUiButton.setOnClickListener(v -> {
            // The WebUI downloads the model on the first chat message; warn up front if the
            // default model is not on the device yet, then just open the browser.
            if (!isCurrentModelDownloaded()) {
                confirmFirstModelDownloadThen(this::openWebUiInBrowser);
            } else {
                openWebUiInBrowser();
            }
        });
        updateWebUiUrl();
    }

    private void updateWebUiUrl() {
        if (openWebUiButton == null) {
            return;
        }
        // The Web UI is only reachable while the server is running.
        openWebUiButton.setEnabled(isServiceRunning);
    }

    private String buildWebUiUrl() {
        final String ip = getActiveWifiIpv4Address();
        // Match SettingsActivity.buildServerUrl for a consistent URL across screens: the loopback is
        // shown as 127.0.0.1 (not "localhost") and there is no trailing slash, so the copied value is
        // a clean base URL usable for both the Web UI (browser adds "/") and API clients.
        final String host = (ip != null && !ip.isEmpty()) ? ip : "127.0.0.1";
        return "http://" + host + ":" + apiPort;
    }

    private String getActiveWifiIpv4Address() {
        if (connectivityManager == null) {
            return null;
        }
        try {
            Network active = connectivityManager.getActiveNetwork();
            if (active == null) {
                return null;
            }
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(active);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null;
            }
            LinkProperties lp = connectivityManager.getLinkProperties(active);
            if (lp == null) {
                return null;
            }
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    return a.getHostAddress();
                }
            }
        } catch (SecurityException ignored) {
            // best-effort
        }
        return null;
    }

    /** Collapsible direct-run section header toggle. */
    private void setupDirectSectionToggle() {
        if (directSectionHeader == null || directSectionContent == null) {
            return;
        }
        directSectionHeader.setOnClickListener(v -> {
            boolean expanded = directSectionContent.getVisibility() == View.VISIBLE;
            directSectionContent.setVisibility(expanded ? View.GONE : View.VISIBLE);
            directSectionHeader.setText(expanded
                    ? localizedText("▶ ダイレクト実行（タップで展開）", "▶ Direct Run (tap to expand)")
                    : localizedText("▼ ダイレクト実行", "▼ Direct Run"));
        });
    }

    /** Collapsible processing-status/log section header toggle (expanded by default). */
    private void setupProcessingSectionToggle() {
        if (processingSectionLabel == null || processingSectionContent == null) {
            return;
        }
        processingSectionLabel.setOnClickListener(v -> {
            boolean expanded = processingSectionContent.getVisibility() == View.VISIBLE;
            processingSectionContent.setVisibility(expanded ? View.GONE : View.VISIBLE);
            processingSectionLabel.setText(expanded
                    ? localizedText("▶ 処理状況/ログ", "▶ Processing Status/Logs")
                    : localizedText("▼ 処理状況/ログ", "▼ Processing Status/Logs"));
        });
    }

    /** Populate the profile (configuration) selector and switch currentConfig on selection. */
    private void setupProfileSpinner() {
        if (profileSpinner == null) {
            return;
        }
        java.util.List<String> names = configManager.listConfigurations();
        if (names == null || names.isEmpty()) {
            names = new java.util.ArrayList<>();
            names.add("default");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        if (currentConfig != null && currentConfig.name != null) {
            int idx = names.indexOf(currentConfig.name);
            if (idx >= 0) {
                profileSpinner.setSelection(idx, false);
            }
        }
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String name = (String) parent.getItemAtPosition(position);
                if (name == null || (currentConfig != null && name.equals(currentConfig.name))) {
                    return;  // programmatic / no-op selection
                }
                try {
                    ConfigurationManager.Configuration loaded = configManager.loadConfiguration(name);
                    if (loaded != null) {
                        currentConfig = loaded;
                        modelManager.notifySettingsChanged();
                        appendMessage(localizedText("プロファイル選択: ", "Profile selected: ") + name
                                + localizedText("（次回プロンプトで適用）", " (applied on next prompt)"));
                    }
                } catch (Exception e) {
                    appendMessage(localizedText("プロファイル読込失敗: ", "Failed to load profile: ") + name);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** Battery temperature in degrees Celsius (proxy for device temperature), NaN if unavailable. */
    private float readBatteryTempC() {
        try {
            Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus != null) {
                int tenthsC = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (tenthsC != Integer.MIN_VALUE) {
                    return tenthsC / 10.0f;
                }
            }
        } catch (Throwable t) {
            // ignore — temperature is best-effort
        }
        return Float.NaN;
    }

    private void appendMessage(final String msg) {
        runOnUiThread(() -> {
            if (logView == null) {
                return;
            }
            String timestamp = timestampFormat.format(new Date());
            String nextLine = "[" + timestamp + "] " + msg + "\n";
            if (isViewingLog) {
                // ログファイル表示中は live ステータスをバッファに溜めて表示は触らない
                savedProcessingText = appendAndTrimProcessingLog(savedProcessingText, nextLine);
                return;
            }
            String currentText = logView.getText() != null ? logView.getText().toString() : "";
            logView.setText(appendAndTrimProcessingLog(currentText, nextLine));
            scrollTextViewToBottom(logView);
            if (mainScrollView != null) {
                mainScrollView.post(() -> mainScrollView.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    private String appendAndTrimProcessingLog(String currentText, String appendedLine) {
        String base = currentText != null ? currentText : "";
        String next = appendedLine != null ? appendedLine : "";
        StringBuilder builder = new StringBuilder(base.length() + next.length());
        builder.append(base);
        builder.append(next);
        if (builder.length() <= PROCESSING_LOG_MAX_CHARS) {
            return builder.toString();
        }

        int trimStart = Math.max(0, builder.length() - PROCESSING_LOG_TRIM_TARGET_CHARS);
        int nextLineStart = builder.indexOf("\n", trimStart);
        if (nextLineStart >= 0 && nextLineStart + 1 < builder.length()) {
            trimStart = nextLineStart + 1;
        }
        return builder.substring(trimStart);
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

    private String localizedText(String ja, String en) {
        return Translations.get(this, ja, en);
    }

    private void applyLocalizedUiText() {
        if (promptLabel != null) {
            promptLabel.setText(localizedText("プロンプト入力:", "Enter Prompt:"));
        }
        if (outputSectionLabel != null) {
            outputSectionLabel.setText(localizedText("モデル出力:", "Model Output:"));
        }
        if (processingSectionLabel != null) {
            boolean procExpanded = processingSectionContent == null
                    || processingSectionContent.getVisibility() == View.VISIBLE;
            processingSectionLabel.setText(procExpanded
                    ? localizedText("▼ 処理状況/ログ", "▼ Processing Status/Logs")
                    : localizedText("▶ 処理状況/ログ", "▶ Processing Status/Logs"));
        }

        if (promptInput != null) {
            promptInput.setHint(localizedText("ここにプロンプトを入力", "Enter your prompt here"));
        }
        if (sendButton != null) {
            sendButton.setText(localizedText("送信", "Send"));
        }
        if (settingsButton != null) {
            settingsButton.setText(localizedText("設定", "Settings"));
        }
        if (initModelButton != null) {
            initModelButton.setText(localizedText("リセット", "Reset"));
        }
        if (viewLogButton != null) {
            viewLogButton.setText(localizedText(isViewingLog ? "ログを隠す" : "ログ表示", isViewingLog ? "Hide Log" : "View Log"));
        }
        if (clearLogButton != null) {
            clearLogButton.setText(isViewingLog
                    ? localizedText("ログ消去", "Clear Log")
                    : localizedText("消去", "Clear"));
        }
        if (downloadLogButton != null) {
            downloadLogButton.setText(localizedText("保存", "Download"));
        }
        if (updateLogButton != null) {
            updateLogButton.setText(localizedText("更新", "Update"));
        }
        // Direct Run セクションヘッダ (現在の開閉状態に合わせてローカライズ表示)
        if (directSectionHeader != null && directSectionContent != null) {
            boolean expanded = directSectionContent.getVisibility() == View.VISIBLE;
            directSectionHeader.setText(expanded
                    ? localizedText("▼ ダイレクト実行", "▼ Direct Run")
                    : localizedText("▶ ダイレクト実行（タップで展開）", "▶ Direct Run (tap to expand)"));
        }
        if (outputView != null) {
            String currentText = outputView.getText() != null ? outputView.getText().toString() : "";
            if (currentText.isEmpty() || "Output will appear here".equals(currentText) || "出力がここに表示されます".equals(currentText)) {
                outputView.setText(localizedText("出力がここに表示されます", "Output will appear here"));
            }
        }
        if (logView != null) {
            String currentText = logView.getText() != null ? logView.getText().toString() : "";
            if ("Starting...\n".equals(currentText) || "起動中...\n".equals(currentText)) {
                logView.setText(localizedText("起動中...\n", "Starting...\n"));
            }
        }
    }

    private boolean shouldShowStartupInstructions(SharedPreferences prefs) {
        if (prefs.contains(PREF_SHOW_STARTUP_INSTRUCTIONS)) {
            return prefs.getBoolean(PREF_SHOW_STARTUP_INSTRUCTIONS, true);
        }
        for (String legacyKey : LEGACY_PREF_SHOW_STARTUP_INSTRUCTIONS_KEYS) {
            if (prefs.contains(legacyKey)) {
                boolean show = prefs.getBoolean(legacyKey, true);
                prefs.edit().putBoolean(PREF_SHOW_STARTUP_INSTRUCTIONS, show).apply();
                return show;
            }
        }
        return true;
    }

    private void showStartupInstructionsIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!shouldShowStartupInstructions(prefs)) {
            return;
        }

        TextView messageView = new TextView(this);
        messageView.setText(Translations.quickStart(this));
        messageView.setTextSize(14f);
        messageView.setLineSpacing(0f, 1.1f);

        CheckBox doNotShowAgainCheckBox = new CheckBox(this);
        doNotShowAgainCheckBox.setText(localizedText("次回以降は表示しない", "Don't show next time"));
        doNotShowAgainCheckBox.setPadding(0, 24, 0, 0);

        LinearLayout dialogContentLayout = new LinearLayout(this);
        dialogContentLayout.setOrientation(LinearLayout.VERTICAL);
        dialogContentLayout.setPadding(48, 32, 48, 16);
        dialogContentLayout.addView(messageView);
        dialogContentLayout.addView(doNotShowAgainCheckBox);

        ScrollView dialogScrollView = new ScrollView(this);
        dialogScrollView.setFillViewport(true);
        dialogScrollView.addView(dialogContentLayout);

        new AlertDialog.Builder(this)
                .setTitle(localizedText("クイックスタート", "Quick Start"))
                .setView(dialogScrollView)
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, which) -> {
                    if (doNotShowAgainCheckBox.isChecked()) {
                        prefs.edit().putBoolean(PREF_SHOW_STARTUP_INSTRUCTIONS, false).apply();
                    }
                })
                .show();
    }

    private void showStartupDialogs() {
        showApiEnablePromptIfNeeded();
    }

    private boolean shouldShowApiEnablePrompt(SharedPreferences prefs) {
        if (prefs.contains(PREF_SHOW_API_ENABLE_PROMPT)) {
            return prefs.getBoolean(PREF_SHOW_API_ENABLE_PROMPT, true);
        }
        return true;
    }

    private void showApiEnablePromptIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!shouldShowApiEnablePrompt(prefs)) {
            continueStartupDialogsAfterApiPrompt();
            return;
        }

        if (isServiceRunning) {
            continueStartupDialogsAfterApiPrompt();
            return;
        }

        String message = localizedText(
                "ローカルAPI/WebUIサーバーを有効化しますか？\n\n有効化すると、この端末や同一ローカルネットワークから API と WebUI を利用できます。WebUIはブラウザで http://<端末IP>:",
                "Enable the local API/WebUI server?\n\nIf enabled, the API and WebUI can be used from this device or the same local network. The WebUI is available in a browser at http://<device-ip>:")
                + apiPort
                + localizedText(
                "/ から開けます。後からメイン画面でも切り替えられます。",
                "/ . You can also change this later from the main screen.");

        TextView messageView = new TextView(this);
        messageView.setText(message);
        messageView.setTextSize(14f);
        messageView.setLineSpacing(0f, 1.1f);

        CheckBox doNotShowAgainCheckBox = new CheckBox(this);
        doNotShowAgainCheckBox.setText(localizedText("次回以降は表示しない", "Don't show next time"));
        doNotShowAgainCheckBox.setPadding(0, 24, 0, 0);

        LinearLayout dialogContentLayout = new LinearLayout(this);
        dialogContentLayout.setOrientation(LinearLayout.VERTICAL);
        dialogContentLayout.setPadding(48, 32, 48, 16);
        dialogContentLayout.addView(messageView);
        dialogContentLayout.addView(doNotShowAgainCheckBox);

        ScrollView dialogScrollView = new ScrollView(this);
        dialogScrollView.setFillViewport(true);
        dialogScrollView.addView(dialogContentLayout);

        new AlertDialog.Builder(this)
                .setTitle(localizedText("API/WebUI有効化", "Enable API/WebUI"))
                .setView(dialogScrollView)
                .setCancelable(false)
                .setPositiveButton(localizedText("有効化", "Enable"), (dialog, which) -> {
                    if (doNotShowAgainCheckBox.isChecked()) {
                        prefs.edit().putBoolean(PREF_SHOW_API_ENABLE_PROMPT, false).apply();
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pendingApiStartAfterPermission = true;
                        continueStartupDialogsAfterPermission = true;
                        requestNotificationPermission();
                        return;
                    }
                    startApiService();
                    continueStartupDialogsAfterApiPrompt();
                })
                .setNegativeButton(localizedText("今回は無効", "Not now"), (dialog, which) -> {
                    if (doNotShowAgainCheckBox.isChecked()) {
                        prefs.edit().putBoolean(PREF_SHOW_API_ENABLE_PROMPT, false).apply();
                    }
                    continueStartupDialogsAfterApiPrompt();
                })
                .show();
    }

    private void continueStartupDialogsAfterApiPrompt() {
        if (!showPendingLoadRecoveryIfNeeded()) {
            showStartupInstructionsIfNeeded();
        }
    }

    private boolean showPendingLoadRecoveryIfNeeded() {
        final PendingModelLoadStore.PendingLoad pendingLoad;
        try {
            pendingLoad = PendingModelLoadStore.readPendingLoad(this);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to read pending model load marker", e);
            appendMessage("Failed to read pending model load marker: " + e.getMessage());
            clearPendingModelLoadMarker();
            showUnreadablePendingLoadDialog();
            return true;
        }

        if (pendingLoad == null) {
            return false;
        }

        String profileName = pendingLoad.getConfigName().trim().isEmpty()
                ? localizedText("不明", "Unknown")
                : pendingLoad.getConfigName().trim();
        String modelName = pendingLoad.getDisplayModelName().isEmpty()
                ? localizedText("不明", "Unknown")
                : pendingLoad.getDisplayModelName();

        clearPendingModelLoadMarker();

        String message = describeInterruptedLoadCause(DiagnosticsLogger.getLastExitSummary(this))
                + localizedText("\n\nプロファイル: ", "\n\nProfile: ")
                + profileName
                + localizedText("\nモデル: ", "\nModel: ")
                + modelName
                + localizedText(
                "\n\n再試行は行いません。必要な場合は Settings から改めて Load Model を実行してください。",
                "\n\nNo automatic retry was performed. If needed, load the model again from Settings.");

        new AlertDialog.Builder(this)
                .setTitle(localizedText("中断されたモデルロード", "Interrupted Model Load"))
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, which) -> showStartupInstructionsIfNeeded())
                .show();
        return true;
    }

    private void showUnreadablePendingLoadDialog() {
        new AlertDialog.Builder(this)
                .setTitle(localizedText("中断されたモデルロード", "Interrupted Model Load"))
                .setMessage(localizedText(
                        "前回のモデルロード記録を読み取れませんでした。必要な場合は Settings から改めて Load Model を実行してください。",
                        "The previous model load record could not be read. If needed, load the model again from Settings."))
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, which) -> showStartupInstructionsIfNeeded())
                .show();
    }

    private void clearPendingModelLoadMarker() {
        try {
            PendingModelLoadStore.deletePendingLoad(this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to delete pending model load marker", e);
            appendMessage("Failed to delete pending model load marker: " + e.getMessage());
        }
    }

    /**
     * Composes a cause-specific sentence for the "Interrupted Model Load" dialog from the previous
     * process exit reason. It no longer blames "address-space reservation failure" unconditionally:
     * an OOM/kernel SIGKILL, an app crash, and a benign user/system stop each get their own wording,
     * and an unknown cause avoids asserting mmap as the reason.
     */
    private String describeInterruptedLoadCause(DiagnosticsLogger.ExitSummary exit) {
        int reason = exit.reason;
        // SIGNALED with a fault/abort signal (SIGABRT=6, SIGSEGV=11, SIGILL=4, SIGBUS=7) is a crash,
        // not an OOM kill. SIGKILL(9) and REASON_LOW_MEMORY/EXCESSIVE_RESOURCE_USAGE are memory kills.
        boolean signaledCrash = reason == ApplicationExitInfo.REASON_SIGNALED
                && (exit.status == 6 || exit.status == 11 || exit.status == 4 || exit.status == 7);
        if (signaledCrash
                || reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            return localizedText(
                    "前回のモデルロードは、アプリの異常終了（クラッシュ）により中断されました。",
                    "The previous model load was interrupted by an app crash.");
        }
        boolean memoryKill = reason == ApplicationExitInfo.REASON_LOW_MEMORY
                || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                || reason == ApplicationExitInfo.REASON_SIGNALED; // SIGKILL(9) etc: kernel/cgroup OOM
        if (memoryKill) {
            String base = localizedText(
                    "前回のモデルロードは、メモリ不足でプロセスが強制終了されて中断された可能性が高いです。これは mmap／アドレス空間の問題ではありません。GPUオフロード層数やコンテキスト長 (n_ctx) を下げると安定します。",
                    "The previous model load likely stopped because the process ran out of memory and was killed. This is not an mmap/address-space problem — lowering the GPU offload layers or the context length (n_ctx) usually helps.");
            if (exit.rssBytes > 0) {
                base = base + " (RSS≈" + formatMb(exit.rssBytes) + ")";
            }
            return base;
        }
        if (reason == ApplicationExitInfo.REASON_USER_REQUESTED
                || reason == ApplicationExitInfo.REASON_USER_STOPPED
                || reason == ApplicationExitInfo.REASON_EXIT_SELF
                || reason == ApplicationExitInfo.REASON_OTHER) {
            return localizedText(
                    "前回のモデルロードは、ユーザ操作またはシステムによる終了で中断されました。",
                    "The previous model load was stopped by a user action or by the system.");
        }
        // Unknown / unavailable (e.g. API < 30): do not assert mmap as the cause.
        return localizedText(
                "前回のモデルロードが完了前に中断されました（原因は特定できませんでした）。大きなモデルではメモリ不足が主因のことが多く、GPUオフロード層数や n_ctx を下げると安定します。",
                "The previous model load did not finish (cause unknown). For large models this is most often due to running out of memory — lowering the GPU offload layers or n_ctx usually helps.");
    }

    private String formatMb(long bytes) {
        return (bytes / (1024L * 1024L)) + "MB";
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        showToast(label + localizedText("をクリップボードにコピーしました", " copied to clipboard"));
    }

    private void downloadDisplayedLog() {
        File appFilesDir = getAppFilesBaseDir();
        File logFile = new File(appFilesDir, "ollama.log");
        String content = null;
        String suggestedFilename = "ollama-log.txt";

        if (!isViewingLog) {
            // Prefer current model response when not viewing logs
            String response = outputView.getText().toString();
            if (response != null && !response.trim().isEmpty()) {
                content = response;
                suggestedFilename = "llm-response.txt";
            } else if (logFile.exists() || isMaxDebugEnabled()) {
                try {
                    content = readDisplayLogContent(appFilesDir);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read log file", e);
                    showToast("Failed to read log file: " + e.getMessage());
                    return;
                }
            }
        } else {
            if (displayedLogContent != null && !displayedLogContent.trim().isEmpty()) {
                content = displayedLogContent;
            } else if (logFile.exists() || isMaxDebugEnabled()) {
                try {
                    content = readDisplayLogContent(appFilesDir);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read log file", e);
                    showToast("Failed to read log file: " + e.getMessage());
                    return;
                }
            } else {
                String response = outputView.getText().toString();
                if (response != null && !response.trim().isEmpty()) {
                    content = response;
                    suggestedFilename = "llm-response.txt";
                }
            }
        }

        if (content == null || content.trim().isEmpty()) {
            showToast("No log or response content to download");
            return;
        }

        pendingLogDownloadContent = content;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, suggestedFilename);
        startActivityForResult(intent, REQUEST_CREATE_LOG_FILE);
    }

    private void handleLogDownloadResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            pendingLogDownloadContent = null;
            return;
        }
        Uri destinationUri = data.getData();
        if (destinationUri == null) {
            pendingLogDownloadContent = null;
            showToast("No destination selected");
            return;
        }
        String content = pendingLogDownloadContent;
        pendingLogDownloadContent = null;
        if (content == null || content.isEmpty()) {
            showToast("No log content to save");
            return;
        }
        try (OutputStream outputStream = getContentResolver().openOutputStream(destinationUri)) {
            if (outputStream == null) {
                showToast("Failed to open destination");
                return;
            }
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            showToast("Log saved");
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "Failed to save log file", e);
            showToast("Failed to save log file: " + e.getMessage());
        }
    }
    
    private void initApiServer() {
        // Load saved port from preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        apiPort = prefs.getInt(PREF_API_PORT, OllamaApiServer.DEFAULT_PORT);
        
        appendMessage("API/WebUI server initialized (port: " + apiPort + ")");
    }
    
    private void updateApiServerUI() {
        if (isServiceRunning) {
            apiServerButton.setText(localizedText("API/WebUI停止", "Stop API/WebUI"));
            apiServerStatusMain.setText(localizedText("API/WebUI: ポート ", "API/WebUI: Running on port ")
                    + apiPort
                    + localizedText(" で稼働中 (WebUI: /)", " (WebUI: /)"));
        } else {
            apiServerButton.setText(localizedText("API/WebUI開始", "Start API/WebUI"));
            apiServerStatusMain.setText(localizedText("API/WebUI: 停止中", "API/WebUI: Stopped"));
        }
        updateWebUiUrl();
    }

    private void updateSettingsButtonForBusyState() {
        boolean isBusy = modelManager != null && modelManager.isBusy();
        if (settingsButton != null) {
            settingsButton.setEnabled(!isBusy);
        }
        if (initModelButton != null) {
            initModelButton.setEnabled(true);
        }
        // Direct-run Send is disabled while an inference/generation is running.
        if (sendButton != null) {
            sendButton.setEnabled(!isBusy);
        }
    }
    
    private void toggleApiServer() {
        if (isServiceRunning) {
            stopApiService();
        } else {
            // Check notification permission before starting service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingApiStartAfterPermission = true;
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
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                appendMessage("Notification permission granted");
                if (pendingApiStartAfterPermission && !isServiceRunning) {
                    startApiService();
                }
            } else {
                appendMessage("Notification permission denied - background service may not show notifications");
            }
            pendingApiStartAfterPermission = false;
            if (continueStartupDialogsAfterPermission) {
                continueStartupDialogsAfterPermission = false;
                continueStartupDialogsAfterApiPrompt();
            }
        }
    }
    
    private void startApiService() {
        // Persist the enable intent immediately so it survives even if the service start is delayed.
        OllamaForegroundService.setApiEnabled(this, true);
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
        appendMessage("Starting API/WebUI server service on port " + apiPort + " (WebUI: /)");
    }
    
    private void stopApiService() {
        // Explicit user disable: clear the persisted intent so it does not auto-restart.
        OllamaForegroundService.setApiEnabled(this, false);
        Intent serviceIntent = new Intent(this, OllamaForegroundService.class);
        serviceIntent.setAction(OllamaForegroundService.ACTION_STOP);
        startService(serviceIntent);
        
        isServiceRunning = false;
        updateApiServerUI();
        appendMessage("Stopping API/WebUI server service");
    }

    private void disconnectApiClients() {
        Intent serviceIntent = new Intent(this, OllamaForegroundService.class);
        serviceIntent.setAction(OllamaForegroundService.ACTION_DISCONNECT_ALL);
        startService(serviceIntent);
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
        String latestLanguage = AppLanguageManager.getOrInitDisplayLanguage(this);
        if (currentDisplayLanguage == null) {
            currentDisplayLanguage = latestLanguage;
        } else if (!currentDisplayLanguage.equals(latestLanguage)) {
            currentDisplayLanguage = latestLanguage;
            recreate();
            return;
        }
        applyLocalizedUiText();
        // Register broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(OllamaForegroundService.ACTION_LOG);
        filter.addAction(OllamaForegroundService.ACTION_STATUS_CHANGED);
        registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        
        // Update service status when returning to the activity
        isServiceRunning = isServiceRunning(OllamaForegroundService.class);
        updateApiServerUI();
        updateWebUiUrl();
        updateSettingsButtonForBusyState();
        busyStateHandler.post(busyStateUpdater);
        busyStateHandler.post(statsUpdater);
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
        busyStateHandler.removeCallbacks(busyStateUpdater);
        busyStateHandler.removeCallbacks(statsUpdater);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Service continues running in background - don't stop it here
    }
}
