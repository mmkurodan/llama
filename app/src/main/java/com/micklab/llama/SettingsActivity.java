package com.micklab.llama;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.micklab.llama.ConfigurationManager.Configuration.DEFAULT_DRY_SEQUENCE_BREAKERS;

public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";
    
    public static final String EXTRA_CONFIG_NAME = "config_name";
    public static final String EXTRA_MODEL_PATH = "model_path";
    public static final String EXTRA_MODEL_LOADED = "model_loaded";
    public static final String EXTRA_API_PORT = "api_port";
    public static final String EXTRA_DISPLAY_LANGUAGE = "display_language";
    
    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_API_PORT = "api_port";
    private static final String PREF_LOG_LEVEL = "log_level";
    
    private ConfigurationManager configManager;
    private ModelManager modelManager;
    
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
    private TextView autoSelectedTemplateView;
    private TextView modelFileInfo;
    private ProgressBar modelProgressBar;
    private Button loadModelButton;
    private Button maintainModelButton;
    
    // Penalty parameter inputs
    private EditText penaltyLastNInput;
    private EditText penaltyRepeatInput;
    private EditText penaltyFreqInput;
    private EditText penaltyPresentInput;
    
    // Mirostat parameter inputs
    private EditText mirostatInput;
    private EditText mirostatTauInput;
    private EditText mirostatEtaInput;
    
    // Additional sampling parameter inputs
    private EditText minPInput;
    private EditText typicalPInput;
    private EditText dynatempRangeInput;
    private EditText dynatempExponentInput;
    private EditText xtcProbabilityInput;
    private EditText xtcThresholdInput;
    private EditText topNSigmaInput;
    
    // DRY parameter inputs
    private EditText dryMultiplierInput;
    private EditText dryBaseInput;
    private EditText dryAllowedLengthInput;
    private EditText dryPenaltyLastNInput;
    private EditText drySequenceBreakersInput;
    
    // Runtime switches
    private Switch streamingSwitch;
    private SeekBar gpuLayersSeekBar;
    private TextView gpuLayersValue;
    private Switch enableThinkingSwitch;
    
    // New prompt settings
    private EditText systemPromptInput;
    private EditText customChatTemplateInput;
    
    // API Server settings
    private EditText apiPortInput;
    private TextView languageLabel;
    private Spinner languageSpinner;
    private Spinner logLevelSpinner;
    private Button licenseButton;
    private Button documentsButton;
    private Button saveConfigButton;
    private Button loadConfigButton;
    private Button deleteConfigButton;
    private Button backButton;
    private Button cancelButton;
    
    private ConfigurationManager.Configuration currentConfig;
    private ArrayAdapter<String> configAdapter;
    private String loadedModelPath = null;
    private boolean modelLoadedSuccessfully = false;
    
    private volatile int lastDownloadProgress = 0;
    private final Handler busyStateHandler = new Handler(Looper.getMainLooper());
    private final Runnable busyStateUpdater = new Runnable() {
        @Override
        public void run() {
            updateActionButtonStateForBusy();
            busyStateHandler.postDelayed(this, 200);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        configManager = new ConfigurationManager(this);
        
        // Get ModelManager singleton
        modelManager = ModelManager.getInstance(this);
        
        initViews();

        // Register download progress listener after views are initialized to avoid NPE if native
        // code emits progress immediately.
        modelManager.getLlama().setDownloadProgressListener(percent -> {
            if (percent == lastDownloadProgress) {
                return;
            }
            lastDownloadProgress = percent;
            runOnUiThread(() -> {
                modelProgressBar.setProgress(percent);
                modelFileInfo.setText("Downloading model... " + percent + "%");
            });
        });
        
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
        autoSelectedTemplateView = findViewById(R.id.autoSelectedTemplateView);
        modelFileInfo = findViewById(R.id.modelFileInfo);
        modelProgressBar = findViewById(R.id.modelProgressBar);
        loadModelButton = findViewById(R.id.loadModelButton);
        maintainModelButton = findViewById(R.id.maintainModelButton);
        
        // Penalty parameter inputs
        penaltyLastNInput = findViewById(R.id.penaltyLastNInput);
        penaltyRepeatInput = findViewById(R.id.penaltyRepeatInput);
        penaltyFreqInput = findViewById(R.id.penaltyFreqInput);
        penaltyPresentInput = findViewById(R.id.penaltyPresentInput);
        
        // Mirostat parameter inputs
        mirostatInput = findViewById(R.id.mirostatInput);
        mirostatTauInput = findViewById(R.id.mirostatTauInput);
        mirostatEtaInput = findViewById(R.id.mirostatEtaInput);
        
        // Additional sampling parameter inputs
        minPInput = findViewById(R.id.minPInput);
        typicalPInput = findViewById(R.id.typicalPInput);
        dynatempRangeInput = findViewById(R.id.dynatempRangeInput);
        dynatempExponentInput = findViewById(R.id.dynatempExponentInput);
        xtcProbabilityInput = findViewById(R.id.xtcProbabilityInput);
        xtcThresholdInput = findViewById(R.id.xtcThresholdInput);
        topNSigmaInput = findViewById(R.id.topNSigmaInput);
        
        // DRY parameter inputs
        dryMultiplierInput = findViewById(R.id.dryMultiplierInput);
        dryBaseInput = findViewById(R.id.dryBaseInput);
        dryAllowedLengthInput = findViewById(R.id.dryAllowedLengthInput);
        dryPenaltyLastNInput = findViewById(R.id.dryPenaltyLastNInput);
        drySequenceBreakersInput = findViewById(R.id.drySequenceBreakersInput);
        
        // Streaming switch
        streamingSwitch = findViewById(R.id.streamingSwitch);
        gpuLayersSeekBar = findViewById(R.id.gpuLayersSeekBar);
        gpuLayersValue = findViewById(R.id.gpuLayersValue);
        gpuLayersSeekBar.setMax(40);
        gpuLayersSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gpuLayersValue.setText(String.valueOf(progress > 39 ? -1 : progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        enableThinkingSwitch = findViewById(R.id.enableThinkingSwitch);
        
        // New prompt settings
        systemPromptInput = findViewById(R.id.systemPromptInput);
        customChatTemplateInput = findViewById(R.id.customChatTemplateInput);
        
        // API Server settings
        apiPortInput = findViewById(R.id.apiPortInput);
        languageLabel = findViewById(R.id.languageLabel);
        languageSpinner = findViewById(R.id.languageSpinner);
        logLevelSpinner = findViewById(R.id.logLevelSpinner);
        licenseButton = findViewById(R.id.licenseButton);
        documentsButton = findViewById(R.id.documentsButton);
        
        // Load saved API port
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedPort = prefs.getInt(PREF_API_PORT, OllamaApiServer.DEFAULT_PORT);
        apiPortInput.setText(String.valueOf(savedPort));
        int defaultLogLevel = 2;
        int savedLogLevel = prefs.contains(PREF_LOG_LEVEL)
                ? prefs.getInt(PREF_LOG_LEVEL, defaultLogLevel)
                : defaultLogLevel;
        setupLanguageSpinner();
        setupLogLevelSpinner(savedLogLevel);
        
        saveConfigButton = findViewById(R.id.saveConfigButton);
        loadConfigButton = findViewById(R.id.loadConfigButton);
        deleteConfigButton = findViewById(R.id.deleteConfigButton);
        backButton = findViewById(R.id.backButton);
        cancelButton = findViewById(R.id.cancelButton);
        
        saveConfigButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            saveCurrentConfiguration();
        });
        loadConfigButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            loadSelectedConfiguration();
        });
        deleteConfigButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            deleteSelectedConfiguration();
        });
        loadModelButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            loadModel();
        });
        maintainModelButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            showModelMaintenanceDialog();
        });
        backButton.setOnClickListener(v -> finish());
        cancelButton.setOnClickListener(v -> cancelAndReturn());
        licenseButton.setOnClickListener(v -> showLicenseDialog());
        documentsButton.setOnClickListener(v -> openDocuments());
        applyLocalizedUiText();
        updateActionButtonStateForBusy();
    }

    private void openDocuments() {
        Intent intent = new Intent(this, DocumentsActivity.class);
        startActivity(intent);
    }

    private boolean isBusyActionBlocked() {
        if (modelManager != null && modelManager.isBusy()) {
            updateActionButtonStateForBusy();
            showToast("Model is busy processing another request");
            return true;
        }
        return false;
    }

    private void updateActionButtonStateForBusy() {
        boolean isBusy = modelManager != null && modelManager.isBusy();

        if (saveConfigButton != null) saveConfigButton.setEnabled(!isBusy);
        if (loadConfigButton != null) loadConfigButton.setEnabled(!isBusy);
        if (deleteConfigButton != null) deleteConfigButton.setEnabled(!isBusy);
        if (loadModelButton != null) loadModelButton.setEnabled(!isBusy);
        if (maintainModelButton != null) maintainModelButton.setEnabled(!isBusy);

        if (backButton != null) backButton.setEnabled(true);
        if (cancelButton != null) cancelButton.setEnabled(true);
        if (licenseButton != null) licenseButton.setEnabled(true);
        if (documentsButton != null) documentsButton.setEnabled(true);
    }

    private String localizedText(String ja, String en) {
        return AppLanguageManager.isJapanese(this) ? ja : en;
    }

    private void applyLocalizedUiText() {
        setTitle(localizedText("設定", "Settings"));
        View root = findViewById(android.R.id.content);
        if (root != null) {
            localizeViewTree(root);
        }
    }

    private void localizeViewTree(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            CharSequence currentText = textView.getText();
            if (currentText != null) {
                String translatedText = translateSettingsText(currentText.toString());
                if (!translatedText.equals(currentText.toString())) {
                    textView.setText(translatedText);
                }
            }
            CharSequence currentHint = textView.getHint();
            if (currentHint != null) {
                String translatedHint = translateSettingsHint(currentHint.toString());
                if (!translatedHint.equals(currentHint.toString())) {
                    textView.setHint(translatedHint);
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                localizeViewTree(group.getChildAt(i));
            }
        }
    }

    private String translateSettingsHint(String hint) {
        if (AppLanguageManager.isJapanese(this)) {
            if ("Enter configuration name".equals(hint)) return "設定名を入力";
            if ("Enter system prompt (optional)".equals(hint)) return "システムプロンプトを入力（任意）";
            if ("Enter custom chat template (optional)".equals(hint)) return "カスタムチャットテンプレートを入力（任意）";
            if (hint.startsWith("Default: ")) return "既定値: " + hint.substring("Default: ".length());
        } else {
            if ("設定名を入力".equals(hint)) return "Enter configuration name";
            if ("システムプロンプトを入力（任意）".equals(hint)) return "Enter system prompt (optional)";
            if ("カスタムチャットテンプレートを入力（任意）".equals(hint)) return "Enter custom chat template (optional)";
            if (hint.startsWith("既定値: ")) return "Default: " + hint.substring("既定値: ".length());
        }
        return hint;
    }

    private String translateSettingsText(String text) {
        if (AppLanguageManager.isJapanese(this)) {
            switch (text) {
                case "Configuration Management": return "設定管理";
                case "Display Language / 表示言語": return "表示言語";
                case "Configuration Name:": return "設定名:";
                case "Save Config": return "設定を保存";
                case "Delete Config": return "設定を削除";
                case "Load Configuration:": return "設定を読み込み:";
                case "Load Selected Config": return "選択した設定を読み込む";
                case "Model Selection": return "モデル選択";
                case "Model Download URL:": return "モデルダウンロードURL:";
                case "Load Model": return "モデルを読み込む";
                case "MAINTAIN MODEL": return "モデル管理";
                case "Model file: (none)": return "モデルファイル: （なし）";
                case "Model Parameters": return "モデルパラメータ";
                case "Context Size (n_ctx):": return "コンテキストサイズ (n_ctx):";
                case "Threads (n_threads):": return "スレッド数 (n_threads):";
                case "Batch Size (n_batch):": return "バッチサイズ (n_batch):";
                case "GPU Offload Layers:": return "GPUオフロード層:";
                case "Temperature (temp):": return "温度 (temp):";
                case "Penalty Parameters": return "ペナルティ設定";
                case "Penalty Last N:": return "ペナルティ対象直近N:";
                case "Penalty Repeat:": return "反復ペナルティ:";
                case "Penalty Frequency:": return "頻度ペナルティ:";
                case "Penalty Presence:": return "出現ペナルティ:";
                case "Mirostat Parameters": return "Mirostat 設定";
                case "Additional Sampling Parameters": return "追加サンプリング設定";
                case "Dynamic Temperature Range:": return "動的温度レンジ:";
                case "Dynamic Temperature Exponent:": return "動的温度指数:";
                case "DRY (Don't Repeat Yourself) Parameters": return "DRY (重複抑制) 設定";
                case "DRY Allowed Length:": return "DRY 許容長:";
                case "DRY Penalty Last N:": return "DRY ペナルティ直近N:";
                case "DRY Sequence Breakers:": return "DRY シーケンス区切り:";
                case "Output Settings": return "出力設定";
                case "Enable Streaming:": return "ストリーミングを有効化:";
                case "Prompt Template": return "プロンプトテンプレート";
                case "System Prompt:": return "システムプロンプト:";
                case "Used when API doesn't provide a system message.": return "API が system メッセージを渡さない場合に使用します。";
                case "Enable Think (chat-template-kwargs.enable_thinking):": return "Thinkを有効化 (chat-template-kwargs.enable_thinking):";
                case "Custom Chat Template:": return "カスタムチャットテンプレート:";
                case "Overrides auto-detection. Use {SYSTEM} and {USER} placeholders.": return "自動判定を上書きします。{SYSTEM} と {USER} プレースホルダーを使用します。";
                case "Auto-selected Prompt Template:": return "自動選択されたプロンプトテンプレート:";
                case "Based on custom template or model family detection.": return "カスタムテンプレートまたはモデル種別判定に基づきます。";
                case "(auto-selected template will appear here)": return "（自動選択されたテンプレートがここに表示されます）";
                case "Llama API Server": return "Llama APIサーバー";
                case "Server Port (default: 11434):": return "サーバーポート (既定: 11434):";
                case "Log Settings": return "ログ設定";
                case "Log Level:": return "ログレベル:";
                case "Show License": return "ライセンス表示";
                case "Documents": return "ドキュメント";
                case "SAVE & CLOSE": return "保存して閉じる";
                case "CLOSE": return "閉じる";
                default: return text;
            }
        } else {
            switch (text) {
                case "表示言語": return "Display Language";
                default: return text;
            }
        }
    }

    private void setupLanguageSpinner() {
        if (languageSpinner == null) {
            return;
        }
        if (languageLabel != null) {
            languageLabel.setText(localizedText("表示言語", "Display Language"));
        }
        String[] languages = new String[] { "日本語", "English" };
        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, languages);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);

        String currentLanguage = AppLanguageManager.getOrInitDisplayLanguage(this);
        languageSpinner.setSelection(AppLanguageManager.LANGUAGE_JA.equals(currentLanguage) ? 0 : 1, false);

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                String selectedLanguage = (position == 0)
                        ? AppLanguageManager.LANGUAGE_JA
                        : AppLanguageManager.LANGUAGE_EN;
                String existingLanguage = AppLanguageManager.getOrInitDisplayLanguage(SettingsActivity.this);
                if (!selectedLanguage.equals(existingLanguage)) {
                    AppLanguageManager.saveDisplayLanguage(SettingsActivity.this, selectedLanguage);
                    showToast(localizedText("表示言語を変更しました", "Display language updated"));
                    recreate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No-op
            }
        });
    }

    private void setupLogLevelSpinner(int savedLogLevel) {
        String[] levels = new String[] { "MAX_DEBUG", "DEBUG", "INFO", "WARN", "ERROR" };
        ArrayAdapter<String> logAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, levels);
        logAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        logLevelSpinner.setAdapter(logAdapter);
        int selection = Math.max(0, Math.min(levels.length - 1, savedLogLevel));
        logLevelSpinner.setSelection(selection);
        modelManager.getLlama().setLogLevel(selection);
        logLevelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                int level = position;
                modelManager.getLlama().setLogLevel(level);
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putInt(PREF_LOG_LEVEL, level).apply();
                showToast("Log level set to " + levels[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No-op
            }
        });
    }

    private void showLicenseDialog() {
        TextView textView = new TextView(this);
        textView.setText(getLicenseText());
        textView.setTextIsSelectable(true);
        textView.setPadding(32, 24, 32, 24);
        textView.setTextSize(12);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(textView);

        new AlertDialog.Builder(this)
            .setTitle("LLM tester with llama.cpp License")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show();
    }

    private String getLicenseText() {
        return "LLM tester with llama.cpp\n"
            + "Built with Llama (llama.cpp)\n\n"
            + "Copyright (c) 2026 Mitsuo Kuroda\n\n"
            + "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION\n\n"
            + "1. Grant of Rights\n"
            + "Subject to the terms and conditions of this License, the Author hereby grants to you a worldwide, royalty-free, non-exclusive license to reproduce, modify, and distribute this software in source or binary form.\n\n"
            + "2. Commercial Use Restriction\n"
            + "Commercial use of this software is strictly prohibited without prior written permission from the Author. \"Commercial use\" includes, but is not limited to, selling the software, using the software to provide paid services, or integrating the software into a commercial product. For commercial inquiries, please contact: micklab2026@gmail.com\n\n"
            + "3. Attribution Requirement\n"
            + "The above copyright notice and this permission notice (including the commercial use restriction) shall be included in all copies or substantial portions of the Software.\n\n"
            + "4. Disclaimer of Warranty\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.\n\n"
            + "MIT License\n\n"
            + "Copyright (c) 2023-2024 The ggml authors\n\n"
            + "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\n"
            + "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.\n\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.\n";
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
        
        // Penalty parameters
        penaltyLastNInput.setText(String.valueOf(config.penaltyLastN));
        penaltyRepeatInput.setText(String.valueOf(config.penaltyRepeat));
        penaltyFreqInput.setText(String.valueOf(config.penaltyFreq));
        penaltyPresentInput.setText(String.valueOf(config.penaltyPresent));
        
        // Mirostat parameters
        mirostatInput.setText(String.valueOf(config.mirostat));
        mirostatTauInput.setText(String.valueOf(config.mirostatTau));
        mirostatEtaInput.setText(String.valueOf(config.mirostatEta));
        
        // Additional sampling parameters
        minPInput.setText(String.valueOf(config.minP));
        typicalPInput.setText(String.valueOf(config.typicalP));
        dynatempRangeInput.setText(String.valueOf(config.dynatempRange));
        dynatempExponentInput.setText(String.valueOf(config.dynatempExponent));
        xtcProbabilityInput.setText(String.valueOf(config.xtcProbability));
        xtcThresholdInput.setText(String.valueOf(config.xtcThreshold));
        topNSigmaInput.setText(String.valueOf(config.topNSigma));
        
        // DRY parameters
        dryMultiplierInput.setText(String.valueOf(config.dryMultiplier));
        dryBaseInput.setText(String.valueOf(config.dryBase));
        dryAllowedLengthInput.setText(String.valueOf(config.dryAllowedLength));
        dryPenaltyLastNInput.setText(String.valueOf(config.dryPenaltyLastN));
        drySequenceBreakersInput.setText(config.drySequenceBreakers);
        
        // Streaming
        streamingSwitch.setChecked(config.streaming);
        int layers = config.gpuOffloadLayers;
        int displayLayers = (layers < 0) ? 40 : layers;
        gpuLayersSeekBar.setProgress(displayLayers);
        gpuLayersValue.setText(String.valueOf(displayLayers > 39 ? -1 : displayLayers));
        enableThinkingSwitch.setChecked(config.enableThinking);
        
        // New prompt settings
        systemPromptInput.setText(config.systemPrompt != null ? config.systemPrompt : "");
        customChatTemplateInput.setText(config.customChatTemplate != null ? config.customChatTemplate : "");
        updateAutoTemplatePreview(config);
    }

    private void updateAutoTemplatePreview(ConfigurationManager.Configuration config) {
        if (autoSelectedTemplateView == null || config == null) {
            return;
        }
        String ggufChatTemplate = "";
        if (modelManager != null && modelManager.isModelLoaded()) {
            ggufChatTemplate = modelManager.getLlama().getChatTemplate();
        }
        String modelPath = loadedModelPath != null ? loadedModelPath : config.modelUrl;
        String settingsSystemPrompt = config.systemPrompt;
        boolean hasSystem = settingsSystemPrompt != null && !settingsSystemPrompt.isEmpty();
        String systemSource = hasSystem ? "settings" : "none";
        PromptTemplateManager.TemplateSelectionResult selection =
                PromptTemplateManager.selectTemplateWithReason(
                        config.customChatTemplate,
                        ggufChatTemplate,
                        modelPath,
                        hasSystem,
                        systemSource);
        autoSelectedTemplateView.setText(selection.template != null ? selection.template : "");
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
        
        if (currentConfig != null && currentConfig.promptTemplate != null && !currentConfig.promptTemplate.isEmpty()) {
            config.promptTemplate = currentConfig.promptTemplate;
        }
        
        // Penalty parameters
        try {
            config.penaltyLastN = Integer.parseInt(penaltyLastNInput.getText().toString());
        } catch (NumberFormatException e) {
            config.penaltyLastN = 64;
        }
        
        try {
            config.penaltyRepeat = Double.parseDouble(penaltyRepeatInput.getText().toString());
        } catch (NumberFormatException e) {
            config.penaltyRepeat = 1.0;
        }
        
        try {
            config.penaltyFreq = Double.parseDouble(penaltyFreqInput.getText().toString());
        } catch (NumberFormatException e) {
            config.penaltyFreq = 0.0;
        }
        
        try {
            config.penaltyPresent = Double.parseDouble(penaltyPresentInput.getText().toString());
        } catch (NumberFormatException e) {
            config.penaltyPresent = 0.0;
        }
        
        // Mirostat parameters
        try {
            config.mirostat = Integer.parseInt(mirostatInput.getText().toString());
        } catch (NumberFormatException e) {
            config.mirostat = 0;
        }
        
        try {
            config.mirostatTau = Double.parseDouble(mirostatTauInput.getText().toString());
        } catch (NumberFormatException e) {
            config.mirostatTau = 5.0;
        }
        
        try {
            config.mirostatEta = Double.parseDouble(mirostatEtaInput.getText().toString());
        } catch (NumberFormatException e) {
            config.mirostatEta = 0.1;
        }
        
        // Additional sampling parameters
        try {
            config.minP = Double.parseDouble(minPInput.getText().toString());
        } catch (NumberFormatException e) {
            config.minP = 0.05;
        }
        
        try {
            config.typicalP = Double.parseDouble(typicalPInput.getText().toString());
        } catch (NumberFormatException e) {
            config.typicalP = 1.0;
        }
        
        try {
            config.dynatempRange = Double.parseDouble(dynatempRangeInput.getText().toString());
        } catch (NumberFormatException e) {
            config.dynatempRange = 0.0;
        }
        
        try {
            config.dynatempExponent = Double.parseDouble(dynatempExponentInput.getText().toString());
        } catch (NumberFormatException e) {
            config.dynatempExponent = 1.0;
        }
        
        try {
            config.xtcProbability = Double.parseDouble(xtcProbabilityInput.getText().toString());
        } catch (NumberFormatException e) {
            config.xtcProbability = 0.0;
        }
        
        try {
            config.xtcThreshold = Double.parseDouble(xtcThresholdInput.getText().toString());
        } catch (NumberFormatException e) {
            config.xtcThreshold = 0.1;
        }
        
        try {
            config.topNSigma = Double.parseDouble(topNSigmaInput.getText().toString());
        } catch (NumberFormatException e) {
            config.topNSigma = -1.0;
        }
        
        // DRY parameters
        try {
            config.dryMultiplier = Double.parseDouble(dryMultiplierInput.getText().toString());
        } catch (NumberFormatException e) {
            config.dryMultiplier = 0.0;
        }
        
        try {
            config.dryBase = Double.parseDouble(dryBaseInput.getText().toString());
        } catch (NumberFormatException e) {
            config.dryBase = 1.75;
        }
        
        try {
            config.dryAllowedLength = Integer.parseInt(dryAllowedLengthInput.getText().toString());
        } catch (NumberFormatException e) {
            config.dryAllowedLength = 2;
        }
        
        try {
            config.dryPenaltyLastN = Integer.parseInt(dryPenaltyLastNInput.getText().toString());
        } catch (NumberFormatException e) {
            config.dryPenaltyLastN = -1;
        }
        
        config.drySequenceBreakers = drySequenceBreakersInput.getText().toString();
        if (config.drySequenceBreakers.isEmpty()) {
            config.drySequenceBreakers = DEFAULT_DRY_SEQUENCE_BREAKERS;
        }
        
        // Streaming
        config.streaming = streamingSwitch.isChecked();
        int progress = gpuLayersSeekBar.getProgress();
        config.gpuOffloadLayers = (progress > 39) ? -1 : progress;
        config.enableThinking = enableThinkingSwitch.isChecked();
        
        // New prompt settings
        config.systemPrompt = systemPromptInput.getText().toString();
        config.customChatTemplate = customChatTemplateInput.getText().toString();
        
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

    private void showModelMaintenanceDialog() {
        final File[] modelFiles = getDownloadedModelFiles();
        if (modelFiles.length == 0) {
            showToast("No downloaded model files found");
            return;
        }

        String[] fileItems = new String[modelFiles.length];
        for (int i = 0; i < modelFiles.length; i++) {
            fileItems[i] = modelFiles[i].getName() + " (" + modelFiles[i].length() + " bytes)";
        }

        final int[] selectedIndex = {0};
        new AlertDialog.Builder(this)
            .setTitle("Model Maintenance")
            .setSingleChoiceItems(fileItems, 0, (dialog, which) -> selectedIndex[0] = which)
            .setPositiveButton("Delete Selected", (dialog, which) -> confirmDeleteModelFile(modelFiles[selectedIndex[0]]))
            .setNegativeButton("Close", null)
            .show();
    }

    private File[] getDownloadedModelFiles() {
        List<File> modelFiles = new ArrayList<>();
        File modelDir = getModelStorageDir();
        File[] existingFiles = modelDir.listFiles();
        if (existingFiles != null) {
            for (File file : existingFiles) {
                if (file.isFile()
                        && file.length() > 0
                        && !"ollama.log".equals(file.getName())
                        && !"last_crash.txt".equals(file.getName())
                        && !"native_crash.txt".equals(file.getName())
                        && !PendingModelLoadStore.isMarkerFile(file.getName())
                        && !containsFile(modelFiles, file)) {
                    modelFiles.add(file);
                }
            }
        }

        if (loadedModelPath != null && !loadedModelPath.isEmpty()) {
            File loadedFile = new File(loadedModelPath);
            if (loadedFile.isFile() && loadedFile.length() > 0 && !containsFile(modelFiles, loadedFile)) {
                modelFiles.add(loadedFile);
            }
        }

        modelFiles.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return modelFiles.toArray(new File[0]);
    }

    private boolean containsFile(List<File> files, File target) {
        for (File file : files) {
            if (file.getAbsolutePath().equals(target.getAbsolutePath())) {
                return true;
            }
        }
        return false;
    }

    private void confirmDeleteModelFile(File modelFile) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Model File")
            .setMessage("Delete " + modelFile.getName() + "?")
            .setPositiveButton("Delete", (dialog, which) -> deleteModelFile(modelFile))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteModelFile(File modelFile) {
        if (modelManager.isBusy()) {
            showToast("Model is busy processing another request");
            return;
        }

        boolean removedLoadedModel = false;
        String currentModelPath = modelManager.getCurrentModelPath();
        if (currentModelPath != null && currentModelPath.equals(modelFile.getAbsolutePath())) {
            modelManager.free();
            removedLoadedModel = true;
        }

        boolean deleted = modelFile.delete();
        if (deleted) {
            if (loadedModelPath != null && loadedModelPath.equals(modelFile.getAbsolutePath())) {
                loadedModelPath = null;
                modelLoadedSuccessfully = false;
                removedLoadedModel = true;
            }
            if (removedLoadedModel) {
                modelFileInfo.setText("Model file: (none)");
                modelProgressBar.setProgress(0);
                lastDownloadProgress = 0;
            }
            showToast("Deleted model file: " + modelFile.getName());
        } else {
            showToast("Failed to delete model file: " + modelFile.getName());
        }
    }
    
    private void loadModel() {
        // Persist current UI config so ModelManager can load by name
        final ConfigurationManager.Configuration config = getConfigFromUI();
        try {
            configManager.saveConfiguration(config);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to save configuration before loading model", e);
            showToast("Failed to save configuration: " + e.getMessage());
            return;
        }
        
        // Show quick file info from URL (if present)
        final String filename = extractFilenameFromUrl(config.modelUrl);
        if (filename != null && !filename.isEmpty()) {
            File destFile = new File(getModelStorageDir(), filename);
            modelFileInfo.setText("Model file: " + filename + (destFile.exists() ? " (" + destFile.length() + " bytes)" : " (checking...)"));
        } else {
            modelFileInfo.setText("Model file: (unknown)");
        }
        modelProgressBar.setProgress(0);
        lastDownloadProgress = 0;
        
        if (modelManager.isBusy()) {
            showToast("Model is busy processing another request");
            return;
        }
        
        new Thread(() -> {
            // Acquire busy lock for load
            if (!modelManager.tryAcquire()) {
                runOnUiThread(() -> showToast("Model is busy"));
                return;
            }
            
            try {
                boolean success = modelManager.loadConfiguration(config.name);
                runOnUiThread(() -> {
                    loadedModelPath = modelManager.getCurrentModelPath();
                    modelLoadedSuccessfully = success;
                    modelFileInfo.setText(success
                        ? "Model loaded: " + (loadedModelPath == null ? config.name : new File(loadedModelPath).getName())
                        : "Model load failed");
                    modelProgressBar.setProgress(success ? 100 : 0);
                    lastDownloadProgress = success ? 100 : 0;
                    loadModelButton.setEnabled(true);
                    showToast(success ? "Model initialized successfully" : "Model initialization failed");
                    updateAutoTemplatePreview(config);
                });
            } catch (Throwable t) {
                Log.e(TAG, "Model load error", t);
                runOnUiThread(() -> {
                    showToast("Model load error: " + t.getMessage());
                    modelFileInfo.setText("Model init failed");
                    modelProgressBar.setProgress(0);
                    lastDownloadProgress = 0;
                    loadModelButton.setEnabled(true);
                });
            } finally {
                modelManager.release();
            }
        }).start();
    }
    
    private void initModelInBackground(final String modelPath) {
        runOnUiThread(() -> {
            modelFileInfo.setText("Initializing model...");
            modelProgressBar.setProgress(0);
            loadModelButton.setEnabled(false);
        });
        
        new Thread(() -> {
            // Try to acquire the lock
            if (!modelManager.tryAcquire()) {
                runOnUiThread(() -> {
                    showToast("Model is busy");
                    loadModelButton.setEnabled(true);
                });
                return;
            }
            
            try {
                LlamaNative llama = modelManager.getLlama();
                String initResult = llama.init(modelPath);
                
                if (!"ok".equals(initResult)) {
                    runOnUiThread(() -> {
                        showToast("Model init failed: " + initResult);
                        modelFileInfo.setText("Model init failed: " + initResult);
                        loadModelButton.setEnabled(true);
                    });
                    return;
                }
                
                // Set parameters after successful model initialization
                ConfigurationManager.Configuration config = getConfigFromUI();
                modelManager.applyConfiguration(config);
                
                runOnUiThread(() -> {
                    loadedModelPath = modelPath;
                    modelLoadedSuccessfully = true;
                    modelFileInfo.setText("Model loaded: " + (new File(modelPath).getName()));
                    loadModelButton.setEnabled(true);
                    modelProgressBar.setProgress(100);
                    showToast("Model initialized successfully");
                    updateAutoTemplatePreview(config);
                });
            } catch (Throwable t) {
                Log.e(TAG, "Model init error", t);
                runOnUiThread(() -> {
                    showToast("Model init error: " + t.getMessage());
                    modelFileInfo.setText("Model init failed");
                    loadModelButton.setEnabled(true);
                });
            } finally {
                modelManager.release();
            }
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

    private File getModelStorageDir() {
        File externalDir = getExternalFilesDir(null);
        return externalDir != null ? externalDir : getFilesDir();
    }
    
    private void showToast(final String msg) {
        runOnUiThread(() -> {
            Toast toast = Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        });
    }
    
    /** Cancel without saving — simply close the activity. */
    private void cancelAndReturn() {
        setResult(RESULT_CANCELED);
        super.finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateActionButtonStateForBusy();
        busyStateHandler.post(busyStateUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        busyStateHandler.removeCallbacks(busyStateUpdater);
    }

    @Override
    public void finish() {
        // Save API port to preferences
        int apiPort = OllamaApiServer.DEFAULT_PORT;
        try {
            apiPort = Integer.parseInt(apiPortInput.getText().toString());
        } catch (NumberFormatException e) {
            // Use default
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(PREF_API_PORT, apiPort).apply();
        
        // Save current UI configuration before returning
        ConfigurationManager.Configuration config = getConfigFromUI();
        try {
            configManager.saveConfiguration(config);
            currentConfig = config;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to save configuration on finish", e);
        }
        
        // Return the current configuration name and model info to MainActivity
        Intent resultIntent = new Intent();
        if (currentConfig != null) {
            resultIntent.putExtra(EXTRA_CONFIG_NAME, currentConfig.name);
        }
        if (loadedModelPath != null) {
            resultIntent.putExtra(EXTRA_MODEL_PATH, loadedModelPath);
            resultIntent.putExtra(EXTRA_MODEL_LOADED, modelLoadedSuccessfully);
        }
        resultIntent.putExtra(EXTRA_API_PORT, apiPort);
        resultIntent.putExtra(EXTRA_DISPLAY_LANGUAGE, AppLanguageManager.getOrInitDisplayLanguage(this));
        setResult(RESULT_OK, resultIntent);
        super.finish();
    }
}
