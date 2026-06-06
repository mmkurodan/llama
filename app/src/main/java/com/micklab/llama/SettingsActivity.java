package com.micklab.llama;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private static final int REQUEST_IMPORT_MODEL_LOCAL_DEVICE = 1001;
    private static final int MODEL_COPY_BUFFER_SIZE = 1024 * 1024;
    private static final String IMPORT_TEMP_SUFFIX = ".import.tmp";
    
    private ConfigurationManager configManager;
    private ModelManager modelManager;
    
    // UI elements
    private EditText configNameInput;
    private Spinner configSpinner;
    private EditText modelUrlInput;
    private TextView multimodalProjectorInfo;
    private Button selectProjectorButton;
    private Button clearProjectorButton;
    private Button searchGgufButton;
    private EditText nCtxInput;
    private EditText nThreadsInput;
    private EditText nBatchInput;
    private EditText tempInput;
    private EditText topPInput;
    private EditText topKInput;
    private TextView autoSelectedTemplateView;
    private TextView modelFileInfo;
    private ProgressBar modelProgressBar;
    private Button importModelButton;
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
    // Compute backend
    private Spinner backendTypeSpinner;
    private Switch  npuEnabledSwitch;

    // New prompt settings
    private EditText systemPromptInput;
    private EditText customChatTemplateInput;
    
    // API Server settings
    private EditText apiPortInput;
    private TextView apiLoopbackUrlView;
    private LinearLayout apiWifiUrlContainer;
    private TextView apiWifiUrlView;
    private TextView apiWifiUrlHintView;
    private EditText mcpConfigJsonInput;
    private EditText functionDefinitionsJsonInput;
    private Switch sharedMcpEnabledSwitch;
    private Switch sharedFunctionCallingEnabledSwitch;
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
    private LinearLayout settingsContentContainer;
    
    private ConfigurationManager.Configuration currentConfig;
    private ArrayAdapter<String> configAdapter;
    private String loadedModelPath = null;
    private String selectedProjectorReference = "";
    private boolean selectedProjectorManualSelection = false;
    private boolean modelLoadedSuccessfully = false;
    private volatile boolean importInProgress = false;
    private volatile boolean huggingFaceSearchInProgress = false;
    private final List<CollapsibleSectionController> collapsibleSections = new ArrayList<>();
    private final ModelManager.BusyStateListener busyStateListener =
            busy -> runOnUiThread(this::updateActionButtonStateForBusy);
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            runOnUiThread(SettingsActivity.this::updateApiServerUrlViews);
        }

        @Override
        public void onLost(Network network) {
            runOnUiThread(SettingsActivity.this::updateApiServerUrlViews);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
            runOnUiThread(SettingsActivity.this::updateApiServerUrlViews);
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
            runOnUiThread(SettingsActivity.this::updateApiServerUrlViews);
        }
    };
    private volatile int lastDownloadProgress = 0;
    private ConnectivityManager connectivityManager;
    private boolean networkCallbackRegistered = false;

    private static final class ImportedModelCandidate {
        private final String displayName;
        private final long sizeBytes;

        private ImportedModelCandidate(String displayName, long sizeBytes) {
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
        }
    }

    private interface SelectionHandler {
        void onSelected(int selectedIndex);
    }

    private static final class CollapsibleSectionController {
        private final TextView indicatorView;
        private final View contentView;
        private boolean expanded;

        private CollapsibleSectionController(TextView indicatorView, View contentView) {
            this.indicatorView = indicatorView;
            this.contentView = contentView;
        }
    }

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
        modelManager.addBusyStateListener(busyStateListener);

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
        multimodalProjectorInfo = findViewById(R.id.multimodalProjectorInfo);
        selectProjectorButton = findViewById(R.id.selectProjectorButton);
        clearProjectorButton = findViewById(R.id.clearProjectorButton);
        searchGgufButton = findViewById(R.id.searchGgufButton);
        nCtxInput = findViewById(R.id.nCtxInput);
        nThreadsInput = findViewById(R.id.nThreadsInput);
        nBatchInput = findViewById(R.id.nBatchInput);
        tempInput = findViewById(R.id.tempInput);
        topPInput = findViewById(R.id.topPInput);
        topKInput = findViewById(R.id.topKInput);
        autoSelectedTemplateView = findViewById(R.id.autoSelectedTemplateView);
        modelFileInfo = findViewById(R.id.modelFileInfo);
        modelProgressBar = findViewById(R.id.modelProgressBar);
        importModelButton = findViewById(R.id.importModelButton);
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

        // Compute backend spinner
        backendTypeSpinner = findViewById(R.id.backendTypeSpinner);
        if (backendTypeSpinner != null) {
            String[] backendLabels = {
                localizedText("CPU (推論専用)", "CPU (Inference only)"),
                localizedText("GPU (OpenCL/Vulkan)", "GPU (OpenCL/Vulkan)"),
                localizedText("NPU / Hexagon HTP", "NPU / Hexagon HTP"),
                localizedText("GPU + NPU (ハイブリッド)", "GPU + NPU (Hybrid)")
            };
            ArrayAdapter<String> backendAdapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, backendLabels);
            backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            backendTypeSpinner.setAdapter(backendAdapter);
            backendTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                           int position, long id) {
                    updateBackendDependentUi(position);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // NPU quick-toggle switch
        npuEnabledSwitch = findViewById(R.id.npuEnabledSwitch);

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
        apiLoopbackUrlView = findViewById(R.id.apiLoopbackUrlView);
        apiWifiUrlContainer = findViewById(R.id.apiWifiUrlContainer);
        apiWifiUrlView = findViewById(R.id.apiWifiUrlView);
        apiWifiUrlHintView = findViewById(R.id.apiWifiUrlHintView);
        mcpConfigJsonInput = findViewById(R.id.mcpConfigJsonInput);
        functionDefinitionsJsonInput = findViewById(R.id.functionDefinitionsJsonInput);
        sharedMcpEnabledSwitch = findViewById(R.id.sharedMcpEnabledSwitch);
        sharedFunctionCallingEnabledSwitch = findViewById(R.id.sharedFunctionCallingEnabledSwitch);
        languageLabel = findViewById(R.id.languageLabel);
        languageSpinner = findViewById(R.id.languageSpinner);
        logLevelSpinner = findViewById(R.id.logLevelSpinner);
        settingsContentContainer = findViewById(R.id.settingsContentContainer);
        licenseButton = findViewById(R.id.licenseButton);
        documentsButton = findViewById(R.id.documentsButton);
        connectivityManager = getSystemService(ConnectivityManager.class);
        
        // Load saved API port
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedPort = prefs.getInt(PREF_API_PORT, OllamaApiServer.DEFAULT_PORT);
        apiPortInput.setText(String.valueOf(savedPort));
        apiPortInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                apiPortInput.setError(null);
                updateApiServerUrlViews();
            }
        });
        mcpConfigJsonInput.setText(McpSettingsHelper.getSharedMcpServersJson(this));
        mcpConfigJsonInput.setHint(McpSettingsHelper.getSharedMcpServersHint());
        functionDefinitionsJsonInput.setText(McpSettingsHelper.getSharedFunctionDefinitionsJson(this));
        functionDefinitionsJsonInput.setHint(McpSettingsHelper.getSharedFunctionDefinitionsHint());
        sharedMcpEnabledSwitch.setChecked(McpSettingsHelper.isSharedMcpEnabledOutsideWebUi(this));
        sharedFunctionCallingEnabledSwitch.setChecked(
                McpSettingsHelper.isSharedFunctionDefinitionsEnabledOutsideWebUi(this));
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
            startModelAction(true, null);
        });
        importModelButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            launchLocalGgufPicker();
        });
        searchGgufButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            showHuggingFaceSearchDialog();
        });
        maintainModelButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            showModelMaintenanceDialog();
        });
        selectProjectorButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            showStoredProjectorSelectionDialog();
        });
        clearProjectorButton.setOnClickListener(v -> {
            if (isBusyActionBlocked()) {
                return;
            }
            setSelectedProjectorReference("", false);
        });
        modelUrlInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clearIncompatibleProjectorSelection();
                updateMultimodalProjectorInfo();
            }
        });
        backButton.setOnClickListener(v -> finish());
        cancelButton.setOnClickListener(v -> cancelAndReturn());
        licenseButton.setOnClickListener(v -> showLicenseDialog());
        documentsButton.setOnClickListener(v -> openDocuments());
        setupApiUrlCopyTarget(apiLoopbackUrlView, localizedText("ローカルURL", "Local URL"));
        setupApiUrlCopyTarget(apiWifiUrlView, localizedText("LAN URL", "LAN URL"));
        setupCollapsibleSections();
        applyLocalizedUiText();
        updateApiServerUrlViews();
        updateActionButtonStateForBusy();
    }

    /** バックエンド選択に応じて GPU Layers / NPU switch の有効・無効を切り替える */
    private void updateBackendDependentUi(int backendPosition) {
        // 0=CPU: GPU layers 無効、NPU switch 無効
        // 1=GPU: GPU layers 有効、NPU switch 無効
        // 2=NPU: GPU layers 有効 (オフロード率として使用)、NPU switch 有効
        // 3=GPU+NPU: GPU layers 有効、NPU switch 有効
        final boolean gpuActive = (backendPosition != ConfigurationManager.Configuration.BACKEND_CPU);
        final boolean npuActive = (backendPosition == ConfigurationManager.Configuration.BACKEND_NPU_HTP
                                || backendPosition == ConfigurationManager.Configuration.BACKEND_GPU_NPU);

        if (gpuLayersSeekBar != null) gpuLayersSeekBar.setEnabled(gpuActive);
        if (gpuLayersValue   != null) gpuLayersValue.setEnabled(gpuActive);
        if (npuEnabledSwitch != null) {
            npuEnabledSwitch.setEnabled(npuActive);
            if (!npuActive) {
                npuEnabledSwitch.setChecked(false);
            }
        }
    }

    private void openDocuments() {
        Intent intent = new Intent(this, DocumentsActivity.class);
        startActivity(intent);
    }

    private boolean isBusyActionBlocked() {
        if (importInProgress) {
            updateActionButtonStateForBusy();
            showToast(localizedText("モデル取込を実行中です", "Model import is already running"));
            return true;
        }
        if (huggingFaceSearchInProgress) {
            updateActionButtonStateForBusy();
            showToast(localizedText("Hugging Face の検索を実行中です", "Hugging Face search is already running"));
            return true;
        }
        if (modelManager != null && modelManager.isBusy()) {
            updateActionButtonStateForBusy();
            showToast("Model is busy processing another request");
            return true;
        }
        return false;
    }

    private void updateActionButtonStateForBusy() {
        boolean isBusy = importInProgress
                || huggingFaceSearchInProgress
                || (modelManager != null && modelManager.isBusy());

        if (saveConfigButton != null) saveConfigButton.setEnabled(!isBusy);
        if (loadConfigButton != null) loadConfigButton.setEnabled(!isBusy);
        if (deleteConfigButton != null) deleteConfigButton.setEnabled(!isBusy);
        if (importModelButton != null) importModelButton.setEnabled(!isBusy);
        if (searchGgufButton != null) searchGgufButton.setEnabled(!isBusy);
        if (loadModelButton != null) loadModelButton.setEnabled(!isBusy);
        if (maintainModelButton != null) maintainModelButton.setEnabled(!isBusy);
        if (selectProjectorButton != null) selectProjectorButton.setEnabled(!isBusy);
        if (clearProjectorButton != null) {
            clearProjectorButton.setEnabled(!isBusy && !selectedProjectorReference.isEmpty());
        }

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
            if ("https://... or filename.gguf".equals(hint)) return "https://... または filename.gguf";
            if ("Enter system prompt (optional)".equals(hint)) return "システムプロンプトを入力（任意）";
            if ("Enter custom chat template (optional)".equals(hint)) return "カスタムチャットテンプレートを入力（任意）";
            if (hint.startsWith("Default: ")) return "既定値: " + hint.substring("Default: ".length());
        } else {
            if ("設定名を入力".equals(hint)) return "Enter configuration name";
            if ("https://... または filename.gguf".equals(hint)) return "https://... or filename.gguf";
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
                case "Model URL / Imported File:": return "モデルURL / 取込済みファイル:";
                case "Multimodal Projector (mmproj):": return "マルチモーダル Projector (mmproj):";
                case "No multimodal projector selected": return "mmproj は未選択です";
                case "Select mmproj": return "mmproj を選択";
                case "Clear mmproj": return "mmproj を解除";
                case "Search GGUF on Hugging Face": return "Hugging FaceでGGUFを検索";
                case "gguf import from local device": return "ローカル端末からggufを取り込む";
                case "Load Model": return "モデルを読み込む";
                case "MAINTAIN MODEL": return "モデル管理";
                case "Model file: (none)": return "モデルファイル: （なし）";
                case "Model Parameters": return "モデルパラメータ";
                case "Context Size (n_ctx):": return "コンテキストサイズ (n_ctx):";
                case "Threads (n_threads):": return "スレッド数 (n_threads):";
                case "Batch Size (n_batch):": return "バッチサイズ (n_batch):";
                case "Compute Backend:": return "計算バックエンド:";
                case "NPU (Hexagon HTP) Enabled:": return "NPU (Hexagon HTP) 有効:";
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
                case "Local URL (tap to copy):": return "ローカルURL（タップでコピー）:";
                case "LAN URL (tap to copy):": return "LAN URL（タップでコピー）:";
                case "Connect to Wi-Fi to show the LAN URL.": return "Wi-Fi接続時にLAN URLを表示します。";
                case "MCP Settings": return "MCP設定";
                case "Enable MCP outside Web UI:": return "Web UI 以外でMCPを有効化:";
                case "Enable Function Calling outside Web UI:": return "Web UI 以外でFunction Callingを有効化:";
                case "Available only in Web UI when disabled.": return "無効時は Web UI でのみ利用されます。";
                case "MCP Config JSON (shared):": return "MCPコンフィグJSON（共通）:";
                case "Function Definitions JSON (shared):": return "Function Definitions JSON（共通）:";
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

    private void setupCollapsibleSections() {
        if (settingsContentContainer == null) {
            return;
        }

        int[] headerIds = new int[]{
                R.id.configurationManagementHeader,
                R.id.modelSelectionHeader,
                R.id.modelParametersHeader,
                R.id.penaltyParametersHeader,
                R.id.mirostatParametersHeader,
                R.id.additionalSamplingParametersHeader,
                R.id.dryParametersHeader,
                R.id.outputSettingsHeader,
                R.id.promptTemplateHeader,
                R.id.apiServerHeader,
                R.id.mcpSettingsHeader,
                R.id.logSettingsHeader
        };

        List<View> originalChildren = new ArrayList<>();
        for (int i = 0; i < settingsContentContainer.getChildCount(); i++) {
            originalChildren.add(settingsContentContainer.getChildAt(i));
        }

        int footerStartIndex = findChildIndexById(originalChildren, R.id.licenseButton);
        if (footerStartIndex < 0) {
            footerStartIndex = originalChildren.size();
        }

        int[] headerIndices = new int[headerIds.length];
        for (int i = 0; i < headerIds.length; i++) {
            headerIndices[i] = findChildIndexById(originalChildren, headerIds[i]);
            if (headerIndices[i] < 0) {
                return;
            }
        }

        settingsContentContainer.removeAllViews();
        collapsibleSections.clear();

        for (int i = 0; i < headerIds.length; i++) {
            View headerCandidate = originalChildren.get(headerIndices[i]);
            if (!(headerCandidate instanceof TextView)) {
                continue;
            }
            TextView headerView = (TextView) headerCandidate;

            int contentStart = headerIndices[i] + 1;
            int contentEnd = (i + 1 < headerIds.length) ? headerIndices[i + 1] : footerStartIndex;

            LinearLayout sectionLayout = new LinearLayout(this);
            sectionLayout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) {
                sectionParams.topMargin = dpToPx(8);
            }
            sectionLayout.setLayoutParams(sectionParams);

            LinearLayout headerRow = new LinearLayout(this);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            headerRow.setClickable(true);
            headerRow.setFocusable(true);
            headerRow.setPadding(0, dpToPx(4), 0, dpToPx(4));
            headerRow.setMinimumHeight(dpToPx(40));

            headerView.setLayoutParams(new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            ));

            TextView indicatorView = new TextView(this);
            indicatorView.setLayoutParams(new LinearLayout.LayoutParams(
                    dpToPx(28),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            indicatorView.setGravity(Gravity.CENTER);
            indicatorView.setTextSize(20f);
            indicatorView.setTypeface(Typeface.DEFAULT_BOLD);
            indicatorView.setPadding(0, 0, dpToPx(8), 0);

            headerRow.addView(indicatorView);
            headerRow.addView(headerView);

            LinearLayout contentLayout = new LinearLayout(this);
            contentLayout.setOrientation(LinearLayout.VERTICAL);
            contentLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            for (int childIndex = contentStart; childIndex < contentEnd; childIndex++) {
                contentLayout.addView(originalChildren.get(childIndex));
            }

            CollapsibleSectionController controller = new CollapsibleSectionController(indicatorView, contentLayout);
            headerRow.setOnClickListener(v -> setSectionExpanded(controller, !controller.expanded));
            setSectionExpanded(controller, !shouldDefaultCollapse(headerIds[i]));

            sectionLayout.addView(headerRow);
            sectionLayout.addView(contentLayout);
            settingsContentContainer.addView(sectionLayout);
            collapsibleSections.add(controller);
        }

        for (int i = footerStartIndex; i < originalChildren.size(); i++) {
            settingsContentContainer.addView(originalChildren.get(i));
        }
    }

    private void setSectionExpanded(CollapsibleSectionController controller, boolean expanded) {
        controller.expanded = expanded;
        controller.contentView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        controller.indicatorView.setText(expanded ? "▼" : "▶");
    }

    private boolean shouldDefaultCollapse(int headerId) {
        return headerId == R.id.modelParametersHeader
                || headerId == R.id.penaltyParametersHeader
                || headerId == R.id.mirostatParametersHeader
                || headerId == R.id.additionalSamplingParametersHeader
                || headerId == R.id.dryParametersHeader
                || headerId == R.id.mcpSettingsHeader;
    }

    private int findChildIndexById(List<View> children, int id) {
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
        selectedProjectorReference = normalizeReference(config.multimodalProjectorUrl);
        selectedProjectorManualSelection = config.multimodalProjectorManualSelection;
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

        // Compute backend
        if (backendTypeSpinner != null) {
            int sel = Math.max(0, Math.min(3, config.backendType));
            backendTypeSpinner.setSelection(sel, false);
            updateBackendDependentUi(sel);
        }
        if (npuEnabledSwitch != null) {
            npuEnabledSwitch.setChecked(config.npuEnabled);
        }
        
        // New prompt settings
        systemPromptInput.setText(config.systemPrompt != null ? config.systemPrompt : "");
        customChatTemplateInput.setText(config.customChatTemplate != null ? config.customChatTemplate : "");
        clearIncompatibleProjectorSelection();
        updateMultimodalProjectorInfo();
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
            config.nBatch = ConfigurationManager.Configuration.DEFAULT_N_BATCH;
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
        config.multimodalProjectorUrl = normalizeReference(selectedProjectorReference);
        config.multimodalProjectorManualSelection =
                !config.multimodalProjectorUrl.isEmpty() && selectedProjectorManualSelection;
        
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

        // Compute backend
        config.backendType = (backendTypeSpinner != null)
                ? Math.max(0, Math.min(3, backendTypeSpinner.getSelectedItemPosition()))
                : ConfigurationManager.Configuration.BACKEND_CPU;
        config.npuEnabled = (npuEnabledSwitch != null) && npuEnabledSwitch.isChecked();

        // New prompt settings
        config.systemPrompt = systemPromptInput.getText().toString();
        config.customChatTemplate = customChatTemplateInput.getText().toString();
        
        return config;
    }

    private int resolveApiPortFromUi() {
        Integer apiPort = parseApiPortFromUi();
        return apiPort != null ? apiPort : OllamaApiServer.DEFAULT_PORT;
    }

    private Integer parseApiPortFromUi() {
        if (apiPortInput == null) {
            return null;
        }
        String rawPort = apiPortInput.getText().toString().trim();
        if (rawPort.isEmpty()) {
            return null;
        }
        int apiPort;
        try {
            apiPort = Integer.parseInt(rawPort);
        } catch (NumberFormatException e) {
            return null;
        }
        return (apiPort >= 1 && apiPort <= 65535) ? apiPort : null;
    }

    private boolean validateApiPortInput() {
        Integer apiPort = parseApiPortFromUi();
        if (apiPort != null) {
            apiPortInput.setError(null);
            return true;
        }

        String message = localizedText(
                "1〜65535 のポート番号を入力してください",
                "Enter a port number between 1 and 65535");
        apiPortInput.setError(message);
        apiPortInput.requestFocus();
        showToast(message);
        return false;
    }

    private boolean saveSharedSettings() {
        if (!validateApiPortInput()) {
            return false;
        }
        String rawMcpConfigJson = mcpConfigJsonInput != null
                ? mcpConfigJsonInput.getText().toString()
                : "";
        if (!McpSettingsHelper.isSharedMcpServersJsonValid(rawMcpConfigJson)) {
            showToast(localizedText(
                    "MCPコンフィグJSONはJSON配列で入力してください",
                    "MCP config JSON must be a JSON array"));
            return false;
        }
        String rawFunctionDefinitionsJson = functionDefinitionsJsonInput != null
                ? functionDefinitionsJsonInput.getText().toString()
                : "";
        if (!McpSettingsHelper.isSharedFunctionDefinitionsJsonValid(rawFunctionDefinitionsJson)) {
            showToast(localizedText(
                    "Function Definitions JSONはJSON配列で入力し、各要素に name を含めてください",
                    "Function Definitions JSON must be a JSON array whose items include a name"));
            return false;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(PREF_API_PORT, resolveApiPortFromUi()).apply();
        McpSettingsHelper.saveSharedMcpServersJson(this, rawMcpConfigJson);
        McpSettingsHelper.saveSharedFunctionDefinitionsJson(this, rawFunctionDefinitionsJson);
        McpSettingsHelper.saveSharedMcpEnabledOutsideWebUi(
                this,
                sharedMcpEnabledSwitch != null && sharedMcpEnabledSwitch.isChecked());
        McpSettingsHelper.saveSharedFunctionDefinitionsEnabledOutsideWebUi(
                this,
                sharedFunctionCallingEnabledSwitch != null && sharedFunctionCallingEnabledSwitch.isChecked());
        return true;
    }

    private void setupApiUrlCopyTarget(TextView targetView, String label) {
        if (targetView == null) {
            return;
        }
        targetView.setOnClickListener(v -> copyUrlToClipboard(targetView, label));
    }

    private void copyUrlToClipboard(TextView sourceView, String label) {
        if (sourceView == null) {
            return;
        }
        Object tag = sourceView.getTag();
        if (!(tag instanceof String)) {
            return;
        }
        String url = (String) tag;
        if (url.isEmpty()) {
            return;
        }
        ClipboardManager clipboardManager = getSystemService(ClipboardManager.class);
        if (clipboardManager == null) {
            showToast(localizedText("クリップボードを利用できません", "Clipboard is unavailable"));
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, url));
        showToast(localizedText("コピーしました: ", "Copied: ") + url);
    }

    private void updateApiServerUrlViews() {
        if (apiLoopbackUrlView == null || apiWifiUrlContainer == null || apiWifiUrlHintView == null) {
            return;
        }

        Integer apiPort = parseApiPortFromUi();
        if (apiPort == null) {
            setCopyableUrl(apiLoopbackUrlView, localizedText(
                    "有効なポート番号を入力するとURLを表示します",
                    "Enter a valid port to show the server URL"), false);
            apiWifiUrlContainer.setVisibility(View.GONE);
            apiWifiUrlHintView.setVisibility(View.VISIBLE);
            apiWifiUrlHintView.setText(localizedText(
                    "有効なポート番号を入力するとLAN URLを表示します",
                    "Enter a valid port to show the LAN URL"));
            return;
        }

        setCopyableUrl(apiLoopbackUrlView, buildServerUrl("localhost", apiPort), true);
        String wifiIpv4Address = getActiveWifiIpv4Address();
        if (wifiIpv4Address == null || wifiIpv4Address.isEmpty()) {
            apiWifiUrlContainer.setVisibility(View.GONE);
            apiWifiUrlHintView.setVisibility(View.VISIBLE);
            apiWifiUrlHintView.setText(localizedText(
                    "Wi-Fi接続時にLAN URLを表示します",
                    "Connect to Wi-Fi to show the LAN URL."));
            return;
        }

        apiWifiUrlContainer.setVisibility(View.VISIBLE);
        apiWifiUrlHintView.setVisibility(View.GONE);
        setCopyableUrl(apiWifiUrlView, buildServerUrl(wifiIpv4Address, apiPort), true);
    }

    private void setCopyableUrl(TextView targetView, String text, boolean copyEnabled) {
        if (targetView == null) {
            return;
        }
        targetView.setText(text);
        targetView.setTag(copyEnabled ? text : null);
        targetView.setEnabled(copyEnabled);
        targetView.setAlpha(copyEnabled ? 1f : 0.7f);
    }

    private String buildServerUrl(String host, int port) {
        return "http://" + host + ":" + port;
    }

    private String getActiveWifiIpv4Address() {
        if (connectivityManager == null) {
            return null;
        }
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return null;
            }
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null;
            }
            LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
            if (linkProperties == null) {
                return null;
            }
            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to inspect Wi-Fi network state", e);
        }
        return null;
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null || networkCallbackRegistered) {
            return;
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to register network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager == null || !networkCallbackRegistered) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to unregister network callback", e);
        } finally {
            networkCallbackRegistered = false;
        }
    }
    
    private void saveCurrentConfiguration() {
        if (!saveSharedSettings()) {
            return;
        }
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

        showScrollableSingleChoiceDialog(
                localizedText("モデル管理", "Model Maintenance"),
                fileItems,
                0,
                localizedText("選択モデルを使用", "Use Selected Model"),
                selectedIndex -> switchCurrentProfileToDownloadedModel(modelFiles[selectedIndex]),
                localizedText("選択モデルを削除", "Delete Selected"),
                selectedIndex -> confirmDeleteModelFile(modelFiles[selectedIndex]));
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
                        && !ModelFileHelper.isLikelyProjectorFilename(file.getName())
                        && !file.getName().endsWith(IMPORT_TEMP_SUFFIX)
                        && !PendingModelLoadStore.isMarkerFile(file.getName())
                        && !containsFile(modelFiles, file)) {
                    modelFiles.add(file);
                }
            }
        }

        if (loadedModelPath != null && !loadedModelPath.isEmpty()) {
            File loadedFile = new File(loadedModelPath);
            if (loadedFile.isFile()
                    && loadedFile.length() > 0
                    && !ModelFileHelper.isLikelyProjectorFilename(loadedFile.getName())
                    && !containsFile(modelFiles, loadedFile)) {
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

    private void switchCurrentProfileToDownloadedModel(File modelFile) {
        if (modelFile == null || !modelFile.isFile() || modelFile.length() <= 0) {
            showToast(localizedText("選択したモデルファイルを利用できません", "The selected model file is unavailable"));
            return;
        }
        if (importInProgress || modelManager.isBusy()) {
            showToast(localizedText(
                    "モデル処理中はプロファイルを変更できません",
                    "Cannot change the profile while a model operation is running"));
            return;
        }

        modelUrlInput.setText(modelFile.getName());
        ConfigurationManager.Configuration config = getConfigFromUI();
        try {
            configManager.saveConfiguration(config);
            currentConfig = config;
            loadConfigList();

            int position = configAdapter != null ? configAdapter.getPosition(config.name) : -1;
            if (position >= 0) {
                configSpinner.setSelection(position);
            }

            loadedModelPath = null;
            modelLoadedSuccessfully = false;
            modelFileInfo.setText(localizedText(
                    "現在のプロファイルを変更しました: " + modelFile.getName(),
                    "Current profile now uses: " + modelFile.getName()));
            modelProgressBar.setProgress(0);
            lastDownloadProgress = 0;
            updateAutoTemplatePreview(config);
            showToast(localizedText(
                    "現在のプロファイルを更新しました: " + config.name,
                    "Updated current profile: " + config.name));
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to switch current profile to downloaded model", e);
            showToast(localizedText(
                    "プロファイルの更新に失敗しました: ",
                    "Failed to update profile: ") + e.getMessage());
        }
    }

    private void deleteModelFile(File modelFile) {
        if (importInProgress || modelManager.isBusy()) {
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

    private void showHuggingFaceSearchDialog() {
        EditText queryInput = new EditText(this);
        queryInput.setSingleLine(true);
        queryInput.setHint(localizedText("モデル名またはキーワード（部分一致）", "Model name or keyword (partial match)"));

        new AlertDialog.Builder(this)
                .setTitle(localizedText("Hugging Face で GGUF を検索", "Search GGUF on Hugging Face"))
                .setView(queryInput)
                .setPositiveButton(localizedText("検索", "Search"),
                        (dialog, which) -> {
                            String query = queryInput.getText().toString().trim();
                            if (query.isEmpty()) {
                                showToast(localizedText("キーワードを入力してください", "Enter a keyword"));
                                return;
                            }
                            searchHuggingFaceRepositories(query);
                        })
                .setNegativeButton(localizedText("キャンセル", "Cancel"), null)
                .show();
    }

    private void searchHuggingFaceRepositories(String rawQuery) {
        final String query = rawQuery != null ? rawQuery.trim() : "";
        setHuggingFaceSearchBusy(true, localizedText(
                "Hugging Face を検索中... " + query,
                "Searching Hugging Face... " + query));

        new Thread(() -> {
            try {
                List<HuggingFaceApiClient.ModelSearchResult> results =
                        HuggingFaceApiClient.searchGgufModels(query);
                runOnUiThread(() -> {
                    setHuggingFaceSearchBusy(false, null);
                    if (results.isEmpty()) {
                        showToast(localizedText(
                                "該当するGGUFリポジトリが見つかりませんでした",
                                "No matching GGUF repositories were found"));
                        return;
                    }
                    showHuggingFaceRepositoryDialog(results);
                });
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Failed to search Hugging Face GGUF repositories", e);
                runOnUiThread(() -> {
                    setHuggingFaceSearchBusy(false, null);
                    showToast(localizedText(
                            "Hugging Face の検索に失敗しました: ",
                            "Hugging Face search failed: ") + e.getMessage());
                });
            }
        }, "hf-gguf-search").start();
    }

    private void showHuggingFaceRepositoryDialog(List<HuggingFaceApiClient.ModelSearchResult> results) {
        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) {
            HuggingFaceApiClient.ModelSearchResult result = results.get(i);
            StringBuilder label = new StringBuilder(result.getRepoId());
            String meta = buildHuggingFaceRepositoryMeta(result);
            if (!meta.isEmpty()) {
                label.append("  (").append(meta).append(')');
            }
            labels[i] = label.toString();
        }

        new AlertDialog.Builder(this)
                .setTitle(localizedText("ダウンロード元を選択", "Select a repository"))
                .setItems(labels, (dialog, which) -> fetchHuggingFaceRepositoryFiles(results.get(which)))
                .setNegativeButton(localizedText("閉じる", "Close"), null)
                .show();
    }

    private void fetchHuggingFaceRepositoryFiles(HuggingFaceApiClient.ModelSearchResult result) {
        setHuggingFaceSearchBusy(true, localizedText(
                "GGUF 一覧を取得中... " + result.getRepoId(),
                "Loading GGUF files... " + result.getRepoId()));

        new Thread(() -> {
            try {
                HuggingFaceApiClient.RepositoryFiles repositoryFiles =
                        HuggingFaceApiClient.getRepositoryGgufFiles(result.getRepoId());
                runOnUiThread(() -> {
                    setHuggingFaceSearchBusy(false, null);
                    if (repositoryFiles.getFiles().isEmpty()) {
                        showToast(localizedText(
                                "ダウンロード可能なGGUFファイルが見つかりませんでした",
                                "No downloadable GGUF files were found"));
                        return;
                    }
                    showHuggingFaceFileDialog(repositoryFiles);
                });
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Failed to load Hugging Face GGUF file list", e);
                runOnUiThread(() -> {
                    setHuggingFaceSearchBusy(false, null);
                    showToast(localizedText(
                            "GGUF 一覧の取得に失敗しました: ",
                            "Failed to load GGUF files: ") + e.getMessage());
                });
            }
        }, "hf-gguf-files").start();
    }

    private void showHuggingFaceFileDialog(HuggingFaceApiClient.RepositoryFiles repositoryFiles) {
        List<HuggingFaceApiClient.GgufFileInfo> files = repositoryFiles.getFiles();
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            labels[i] = files.get(i).getFilename();
        }

        new AlertDialog.Builder(this)
                .setTitle(localizedText("ダウンロード対象を選択", "Select a GGUF file"))
                .setItems(labels, (dialog, which) -> applyHuggingFaceFileSelection(repositoryFiles, files.get(which)))
                .setNegativeButton(localizedText("閉じる", "Close"), null)
                .show();
    }

    private void applyHuggingFaceFileSelection(
            HuggingFaceApiClient.RepositoryFiles repositoryFiles,
            HuggingFaceApiClient.GgufFileInfo selectedFile) {
        String downloadUrl = selectedFile.getDownloadUrl();
        String previousModelReference = modelUrlInput.getText().toString().trim();
        String previousProjectorReference = selectedProjectorReference;
        boolean previousProjectorManualSelection = selectedProjectorManualSelection;
        modelUrlInput.setText(downloadUrl);
        HuggingFaceApiClient.GgufFileInfo suggestedProjectorInfo =
                repositoryFiles.findMatchingProjector(selectedFile, false, false);
        if (suggestedProjectorInfo != null) {
            setSelectedProjectorReference(suggestedProjectorInfo.getDownloadUrl(), false);
        } else {
            setSelectedProjectorReference("", false);
        }
        currentConfig = getConfigFromUI();
        updateAutoTemplatePreview(currentConfig);

        modelFileInfo.setText(localizedText(
                "選択したモデル: " + selectedFile.getFilename(),
                "Selected model: " + selectedFile.getFilename()));
        startModelAction(
                true,
                () -> {
                    modelUrlInput.setText(previousModelReference);
                    setSelectedProjectorReference(previousProjectorReference, previousProjectorManualSelection);
                    currentConfig = getConfigFromUI();
                    updateAutoTemplatePreview(currentConfig);
                });
    }

    private void setHuggingFaceSearchBusy(boolean busy, String statusMessage) {
        huggingFaceSearchInProgress = busy;
        if (busy) {
            modelProgressBar.setIndeterminate(true);
            modelProgressBar.setProgress(0);
            lastDownloadProgress = 0;
            if (statusMessage != null && !statusMessage.isEmpty()) {
                modelFileInfo.setText(statusMessage);
            }
        } else {
            modelProgressBar.setIndeterminate(false);
            if (modelProgressBar.getProgress() == 0) {
                lastDownloadProgress = 0;
            }
        }
        updateActionButtonStateForBusy();
    }

    private String buildHuggingFaceRepositoryMeta(HuggingFaceApiClient.ModelSearchResult result) {
        List<String> items = new ArrayList<>();
        if (result.getDownloads() > 0) {
            items.add("DL " + formatCompactCount(result.getDownloads()));
        }
        if (result.getLikes() > 0) {
            items.add(localizedText("いいね ", "Likes ") + formatCompactCount(result.getLikes()));
        }
        if (result.getPipelineTag() != null && !result.getPipelineTag().isEmpty()) {
            items.add(result.getPipelineTag());
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                builder.append(" / ");
            }
            builder.append(items.get(i));
        }
        return builder.toString();
    }

    private String formatCompactCount(long value) {
        if (value >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1fB", value / 1_000_000_000.0d);
        }
        if (value >= 1_000_000L) {
            return String.format(Locale.US, "%.1fM", value / 1_000_000.0d);
        }
        if (value >= 1_000L) {
            return String.format(Locale.US, "%.1fK", value / 1_000.0d);
        }
        return String.valueOf(value);
    }

    private void launchLocalGgufPicker() {
        launchGgufPicker(REQUEST_IMPORT_MODEL_LOCAL_DEVICE, buildDefaultImportUri());
    }

    private void launchGgufPicker(int requestCode, Uri initialUri) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No document picker available for GGUF import", e);
            showToast(localizedText("ファイル選択画面を開けませんでした", "Could not open the file picker"));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to launch GGUF picker", e);
            showToast(localizedText("ファイル選択画面を開けませんでした", "Could not open the file picker"));
        }
    }

    private Uri buildDefaultImportUri() {
        String documentId = "primary:Download";
        try {
            return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to build initial picker URI for " + documentId, e);
            return null;
        }
    }

    private void importModelFromUri(Uri sourceUri) {
        ImportedModelCandidate candidate = resolveImportedModelCandidate(sourceUri);
        String displayName = candidate.displayName;
        if (displayName == null || displayName.isEmpty()) {
            showToast(localizedText("選択したファイル名を取得できません", "Could not determine the selected file name"));
            return;
        }
        if (!ModelFileHelper.isGgufFilename(displayName)) {
            showToast(localizedText(".gguf ファイルを選択してください", "Please select a .gguf file"));
            return;
        }

        File destFile = new File(getModelStorageDir(), displayName);
        String currentModelPath = modelManager.getCurrentModelPath();
        if (currentModelPath != null && currentModelPath.equals(destFile.getAbsolutePath())) {
            showToast(localizedText(
                    "読み込み中のモデルは上書きできません。先に別モデルへ切り替えるか削除してください",
                    "The currently loaded model cannot be replaced. Switch models or delete it first"));
            return;
        }

        importInProgress = true;
        updateActionButtonStateForBusy();
        modelProgressBar.setIndeterminate(candidate.sizeBytes <= 0);
        modelProgressBar.setProgress(0);
        lastDownloadProgress = 0;
        modelFileInfo.setText(localizedText("モデルを取り込み中... " + displayName, "Importing model... " + displayName));

        new Thread(() -> {
            String error = copyImportedModelToStorage(sourceUri, destFile, candidate.sizeBytes, displayName);
            runOnUiThread(() -> {
                importInProgress = false;
                modelProgressBar.setIndeterminate(false);
                updateActionButtonStateForBusy();

                if (error != null) {
                    modelProgressBar.setProgress(0);
                    modelFileInfo.setText(localizedText("モデル取込に失敗しました", "Model import failed"));
                    showToast(error);
                    return;
                }

                loadedModelPath = null;
                modelLoadedSuccessfully = false;
                boolean importedProjector = ModelFileHelper.isLikelyProjectorFilename(displayName);
                if (importedProjector) {
                    modelFileInfo.setText(localizedText(
                            "mmproj を取り込みました: " + displayName + " (" + destFile.length() + " bytes)",
                            "Imported mmproj: " + displayName + " (" + destFile.length() + " bytes)"));
                    String modelReference = modelUrlInput.getText().toString().trim();
                    if (!modelReference.isEmpty()
                            && ModelFileHelper.canAutoApplyProjectorReference(modelReference, displayName)) {
                        setSelectedProjectorReference(displayName, false);
                    }
                } else {
                    modelUrlInput.setText(displayName);
                    modelFileInfo.setText("Model file: " + displayName + " (" + destFile.length() + " bytes)");
                }
                modelProgressBar.setProgress(0);
                currentConfig = getConfigFromUI();
                showToast(importedProjector
                        ? localizedText("mmproj を取り込みました: " + displayName, "Imported mmproj: " + displayName)
                        : localizedText("モデルファイルを取り込みました: " + displayName, "Imported model file: " + displayName));
                updateAutoTemplatePreview(currentConfig);
            });
        }).start();
    }

    private void showStoredProjectorSelectionDialog() {
        String modelReference = modelUrlInput.getText().toString().trim();
        if (modelReference.isEmpty()) {
            showToast(localizedText("先にモデルを選択してください", "Select a model first"));
            return;
        }

        File[] projectorFiles = getDownloadedProjectorFiles();
        if (projectorFiles.length == 0) {
            showToast(localizedText("利用可能な GGUF ファイルが見つかりません", "No stored GGUF files were found"));
            return;
        }

        String[] labels = new String[projectorFiles.length];
        String currentSelection = extractFilenameFromUrl(normalizeReference(selectedProjectorReference));
        int selectedIndex = 0;
        for (int i = 0; i < projectorFiles.length; i++) {
            File projectorFile = projectorFiles[i];
            boolean recommended = ModelFileHelper.canAutoApplyProjectorReference(modelReference, projectorFile.getName());
            labels[i] = projectorFile.getName()
                    + (recommended ? localizedText("  [推奨]", "  [Recommended]") : "")
                    + " (" + projectorFile.length() + " bytes)";
            if (projectorFile.getName().equalsIgnoreCase(currentSelection)) {
                selectedIndex = i;
            }
        }

        showScrollableSingleChoiceDialog(
                localizedText("mmproj を選択", "Select mmproj"),
                labels,
                selectedIndex,
                localizedText("選択", "Select"),
                index -> setSelectedProjectorReference(projectorFiles[index].getName(), true),
                localizedText("解除", "Clear"),
                index -> setSelectedProjectorReference("", false));
    }

    private void showScrollableSingleChoiceDialog(
            String title,
            String[] items,
            int initialSelection,
            String positiveLabel,
            SelectionHandler onPositive,
            String neutralLabel,
            SelectionHandler onNeutral) {
        showScrollableSingleChoiceDialog(
                title,
                items,
                initialSelection,
                positiveLabel,
                onPositive,
                neutralLabel,
                onNeutral,
                null);
    }

    private void showScrollableSingleChoiceDialog(
            String title,
            String[] items,
            int initialSelection,
            String positiveLabel,
            SelectionHandler onPositive,
            String neutralLabel,
            SelectionHandler onNeutral,
            Runnable onCancel) {
        if (items == null || items.length == 0) {
            return;
        }

        final int[] selectedIndex = {Math.max(0, Math.min(initialSelection, items.length - 1))};
        ListView listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, items));
        listView.setVerticalScrollBarEnabled(true);
        listView.setFastScrollEnabled(true);
        listView.setItemChecked(selectedIndex[0], true);
        listView.setOnItemClickListener((parent, view, position, id) -> selectedIndex[0] = position);

        int rowHeight = (int) (56 * getResources().getDisplayMetrics().density);
        int minHeight = (int) (180 * getResources().getDisplayMetrics().density);
        int maxHeight = (getResources().getDisplayMetrics().heightPixels * 3) / 5;
        int listHeight = Math.min(maxHeight, Math.max(minHeight, items.length * rowHeight));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, 0, padding, 0);
        container.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeight));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(positiveLabel, (dialogInterface, which) -> onPositive.onSelected(selectedIndex[0]))
                .setNegativeButton(localizedText("閉じる", "Close"), (dialogInterface, which) -> {
                    if (onCancel != null) {
                        onCancel.run();
                    }
                })
                .create();
        if (neutralLabel != null && onNeutral != null) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, neutralLabel,
                    (dialogInterface, which) -> onNeutral.onSelected(selectedIndex[0]));
        }
        dialog.setOnCancelListener(dialogInterface -> {
            if (onCancel != null) {
                onCancel.run();
            }
        });
        dialog.show();
    }

    private void clearIncompatibleProjectorSelection() {
        String modelReference = modelUrlInput != null ? modelUrlInput.getText().toString().trim() : "";
        if (selectedProjectorReference.isEmpty() || modelReference.isEmpty()) {
            return;
        }
        if (!selectedProjectorManualSelection
                && !ModelFileHelper.canAutoApplyProjectorReference(modelReference, selectedProjectorReference)) {
            selectedProjectorReference = "";
            selectedProjectorManualSelection = false;
        }
    }

    private void setSelectedProjectorReference(String projectorReference) {
        setSelectedProjectorReference(projectorReference, false);
    }

    private void setSelectedProjectorReference(String projectorReference, boolean manualSelection) {
        selectedProjectorReference = normalizeReference(projectorReference);
        selectedProjectorManualSelection = !selectedProjectorReference.isEmpty() && manualSelection;
        updateMultimodalProjectorInfo();
    }

    private void updateMultimodalProjectorInfo() {
        if (multimodalProjectorInfo == null) {
            return;
        }
        String modelReference = modelUrlInput != null ? modelUrlInput.getText().toString().trim() : "";
        boolean projectorAvailable = !selectedProjectorReference.isEmpty()
                || findAutoDetectedProjectorFile(modelReference) != null;
        boolean likelyMultimodalModel =
                !modelReference.isEmpty() && ModelFileHelper.isLikelyMultimodalModelReference(modelReference);

        if (likelyMultimodalModel) {
            // For gguf models that can accept an mmproj, show availability and the configured mmproj model name below
            String availableText = localizedText("利用可能", "Available");
            String mmprojModelName = "";
            if (!selectedProjectorReference.isEmpty()) {
                mmprojModelName = extractFilenameFromUrl(selectedProjectorReference);
            } else {
                File autoDetected = findAutoDetectedProjectorFile(modelReference);
                if (autoDetected != null) {
                    mmprojModelName = autoDetected.getName();
                }
            }

            if (!mmprojModelName.isEmpty()) {
                multimodalProjectorInfo.setText(availableText + "\n" + mmprojModelName);
            } else if (projectorAvailable) {
                multimodalProjectorInfo.setText(availableText);
            } else {
                multimodalProjectorInfo.setText(localizedText(
                        "Projector: 未選択",
                        "Projector: not selected"));
            }
        } else if (selectedProjectorReference.isEmpty()) {
            multimodalProjectorInfo.setText(localizedText(
                    "Projector: 未選択",
                    "Projector: not selected"));
        } else {
            multimodalProjectorInfo.setText(localizedText(
                    "Projector: " + extractFilenameFromUrl(selectedProjectorReference),
                    "Projector: " + extractFilenameFromUrl(selectedProjectorReference)));
        }
        updateActionButtonStateForBusy();
    }

    private File[] getDownloadedProjectorFiles() {
        List<File> projectorFiles = new ArrayList<>();
        File modelDir = getModelStorageDir();
        File[] existingFiles = modelDir.listFiles();
        if (existingFiles != null) {
            for (File file : existingFiles) {
                if (file.isFile()
                        && file.length() > 0
                        && ModelFileHelper.isGgufFilename(file.getName())
                        && !containsFile(projectorFiles, file)) {
                    projectorFiles.add(file);
                }
            }
        }
        projectorFiles.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String modelReference = modelUrlInput != null ? modelUrlInput.getText().toString().trim() : "";
        projectorFiles.sort((a, b) -> {
            boolean aRecommended = ModelFileHelper.canAutoApplyProjectorReference(modelReference, a.getName());
            boolean bRecommended = ModelFileHelper.canAutoApplyProjectorReference(modelReference, b.getName());
            if (aRecommended != bRecommended) {
                return aRecommended ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return projectorFiles.toArray(new File[0]);
    }

    private String normalizeReference(String reference) {
        return reference == null ? "" : reference.trim();
    }

    private ImportedModelCandidate resolveImportedModelCandidate(Uri sourceUri) {
        String displayName = null;
        long sizeBytes = -1L;

        if ("content".equalsIgnoreCase(sourceUri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(
                    sourceUri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null,
                    null,
                    null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex);
                    }
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex);
                    }
                }
            } catch (SecurityException | IllegalArgumentException e) {
                Log.w(TAG, "Failed to query import source metadata", e);
            }
        }

        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = sourceUri.getLastPathSegment();
        }

        displayName = ModelFileHelper.extractFilename(displayName);
        return new ImportedModelCandidate(displayName, sizeBytes);
    }

    private String copyImportedModelToStorage(Uri sourceUri, File destFile, long sizeBytes, String displayName) {
        File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs() && !parentDir.isDirectory()) {
            return "Failed to create model storage directory: " + parentDir.getAbsolutePath();
        }

        File tempFile = new File(
                parentDir != null ? parentDir : getModelStorageDir(),
                destFile.getName() + IMPORT_TEMP_SUFFIX);

        if (tempFile.exists() && !tempFile.delete()) {
            return "Failed to clear temporary import file: " + tempFile.getName();
        }

        try (InputStream inputStream = getContentResolver().openInputStream(sourceUri)) {
            if (inputStream == null) {
                return "Could not open selected file: " + displayName;
            }

            try (OutputStream outputStream = new FileOutputStream(tempFile, false)) {
                byte[] buffer = new byte[MODEL_COPY_BUFFER_SIZE];
                long copiedBytes = 0L;
                int lastProgress = -1;
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                    copiedBytes += read;

                    if (sizeBytes > 0) {
                        int progress = (int) Math.min(100L, (copiedBytes * 100L) / sizeBytes);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            int progressValue = progress;
                            runOnUiThread(() -> {
                                modelProgressBar.setIndeterminate(false);
                                modelProgressBar.setProgress(progressValue);
                                modelFileInfo.setText(localizedText(
                                        "モデルを取り込み中... " + displayName + " (" + progressValue + "%)",
                                        "Importing model... " + displayName + " (" + progressValue + "%)"));
                            });
                        }
                    }
                }
                outputStream.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to import model file", e);
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w(TAG, "Failed to delete temporary import file: " + tempFile.getAbsolutePath());
            }
            return "Failed to import model file: " + e.getMessage();
        }

        if (!tempFile.exists() || tempFile.length() <= 0) {
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w(TAG, "Failed to delete empty import file: " + tempFile.getAbsolutePath());
            }
            return "Imported model file is empty: " + displayName;
        }

        if (destFile.exists() && !destFile.delete()) {
            if (!tempFile.delete()) {
                Log.w(TAG, "Failed to delete temporary import file after replace failure: " + tempFile.getAbsolutePath());
            }
            return "Failed to replace existing model file: " + destFile.getName();
        }

        if (!tempFile.renameTo(destFile)) {
            if (!tempFile.delete()) {
                Log.w(TAG, "Failed to delete temporary import file after rename failure: " + tempFile.getAbsolutePath());
            }
            return "Failed to finalize imported model file: " + destFile.getName();
        }

        return null;
    }
    
    private void loadModel() {
        final ConfigurationManager.Configuration config = saveConfigurationForModelAction();
        if (config == null) {
            return;
        }

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

    private void startModelAction(boolean loadAfterDownload, Runnable onCancel) {
        ConfigurationManager.Configuration config = getConfigFromUI();
        if (requiresRemoteDownload(config)) {
            showRemoteDownloadDecisionDialog(loadAfterDownload, onCancel);
            return;
        }

        if (loadAfterDownload) {
            loadModel();
        } else {
            downloadModelFilesOnly();
        }
    }

    private void showRemoteDownloadDecisionDialog(boolean loadAfterDownload, Runnable onCancel) {
        ConfigurationManager.Configuration config = getConfigFromUI();
        String modelFilename = extractFilenameFromUrl(config.modelUrl);
        boolean projectorDownload = ModelFileHelper.isLikelyProjectorFilename(modelFilename);
        boolean hasRemoteProjector = referenceNeedsRemoteDownload(config.multimodalProjectorUrl);

        String title = localizedText(
                projectorDownload ? "Projector をダウンロード" : "モデルをダウンロード",
                projectorDownload ? "Download projector" : "Download model");
        String message;
        if (projectorDownload) {
            message = localizedText(
                    "この GGUF は mmproj / Projector の可能性があります。ダウンロードのみを推奨します。ダウンロード後にそのままロードしますか？",
                    "This GGUF looks like an mmproj / projector. Download-only is recommended. Load it immediately after the download?");
        } else if (hasRemoteProjector) {
            message = localizedText(
                    "この操作ではモデル本体と利用可能な Projector をダウンロードします。ダウンロード後にモデルをロードしますか？",
                    "This will download the model and an available projector. Load the model immediately after the download?");
        } else {
            message = localizedText(
                    "この操作では Web からモデルをダウンロードします。ダウンロード後にすぐロードしますか？",
                    "This will download the model from the web. Load it immediately after the download?");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(localizedText("キャンセル", "Cancel"), (dialog, which) -> {
                    if (onCancel != null) {
                        onCancel.run();
                    }
                });

        if (projectorDownload) {
            builder.setPositiveButton(
                    localizedText("ダウンロードのみ（推奨）", "Download only (Recommended)"),
                    (dialog, which) -> downloadModelFilesOnly());
            builder.setNeutralButton(
                    localizedText("ダウンロードしてロード", "Download and load"),
                    (dialog, which) -> loadModel());
        } else if (loadAfterDownload) {
            builder.setPositiveButton(
                    localizedText("ダウンロードしてロード", "Download and load"),
                    (dialog, which) -> loadModel());
            builder.setNeutralButton(
                    localizedText("ダウンロードのみ", "Download only"),
                    (dialog, which) -> downloadModelFilesOnly());
        } else {
            builder.setPositiveButton(
                    localizedText("ダウンロードのみ", "Download only"),
                    (dialog, which) -> downloadModelFilesOnly());
            builder.setNeutralButton(
                    localizedText("ダウンロードしてロード", "Download and load"),
                    (dialog, which) -> loadModel());
        }

        AlertDialog dialog = builder.create();
        dialog.setOnCancelListener(ignored -> {
            if (onCancel != null) {
                onCancel.run();
            }
        });
        dialog.show();
    }

    private ConfigurationManager.Configuration saveConfigurationForModelAction() {
        final ConfigurationManager.Configuration config = getConfigFromUI();
        try {
            configManager.saveConfiguration(config);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to save configuration before model action", e);
            showToast("Failed to save configuration: " + e.getMessage());
            return null;
        }

        final String filename = extractFilenameFromUrl(config.modelUrl);
        if (filename != null && !filename.isEmpty()) {
            File destFile = new File(getModelStorageDir(), filename);
            modelFileInfo.setText("Model file: " + filename + (destFile.exists()
                    ? " (" + destFile.length() + " bytes)"
                    : " (checking...)"));
        } else {
            modelFileInfo.setText("Model file: (unknown)");
        }
        modelProgressBar.setProgress(0);
        lastDownloadProgress = 0;
        return config;
    }

    private void downloadModelFilesOnly() {
        final ConfigurationManager.Configuration config = saveConfigurationForModelAction();
        if (config == null) {
            return;
        }

        if (modelManager.isBusy()) {
            showToast("Model is busy processing another request");
            return;
        }

        new Thread(() -> {
            if (!modelManager.tryAcquire()) {
                runOnUiThread(() -> showToast("Model is busy"));
                return;
            }

            try {
                boolean success = modelManager.downloadConfigurationAssets(config.name);
                runOnUiThread(() -> {
                    String filename = extractFilenameFromUrl(config.modelUrl);
                    modelFileInfo.setText(success
                            ? localizedText(
                                    "ダウンロード完了: " + (filename == null ? config.name : filename),
                                    "Download complete: " + (filename == null ? config.name : filename))
                            : localizedText("ダウンロードに失敗しました", "Download failed"));
                    modelProgressBar.setProgress(success ? 100 : 0);
                    lastDownloadProgress = success ? 100 : 0;
                    updateAutoTemplatePreview(config);
                    showToast(success
                            ? localizedText("ダウンロードが完了しました", "Download completed")
                            : localizedText("ダウンロードに失敗しました", "Download failed"));
                });
            } catch (Throwable t) {
                Log.e(TAG, "Model download error", t);
                runOnUiThread(() -> {
                    showToast("Model download error: " + t.getMessage());
                    modelFileInfo.setText(localizedText("ダウンロードに失敗しました", "Download failed"));
                    modelProgressBar.setProgress(0);
                    lastDownloadProgress = 0;
                });
            } finally {
                modelManager.release();
            }
        }).start();
    }

    private boolean requiresRemoteDownload(ConfigurationManager.Configuration config) {
        return referenceNeedsRemoteDownload(config.modelUrl)
                || referenceNeedsRemoteDownload(config.multimodalProjectorUrl);
    }

    private boolean referenceNeedsRemoteDownload(String reference) {
        String normalizedReference = normalizeReference(reference);
        if (normalizedReference.isEmpty() || !ModelFileHelper.isRemoteModelReference(normalizedReference)) {
            return false;
        }
        File storedFile = ModelFileHelper.resolveStoredModelFile(this, normalizedReference);
        return storedFile == null || !storedFile.isFile() || storedFile.length() <= 0;
    }

    private File findAutoDetectedProjectorFile(String modelReference) {
        String normalizedModelReference = normalizeReference(modelReference);
        if (normalizedModelReference.isEmpty()) {
            return null;
        }
        return ModelFileHelper.findAutoDetectedMultimodalProjectorFile(
                this,
                normalizedModelReference,
                false,
                false);
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
        return ModelFileHelper.extractFilename(url);
    }

    private File getModelStorageDir() {
        return ModelFileHelper.getModelStorageDir(this);
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
        registerNetworkCallback();
        updateActionButtonStateForBusy();
        updateApiServerUrlViews();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterNetworkCallback();
    }

    @Override
    protected void onDestroy() {
        if (modelManager != null) {
            modelManager.removeBusyStateListener(busyStateListener);
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_IMPORT_MODEL_LOCAL_DEVICE || resultCode != RESULT_OK) {
            return;
        }

        Uri selectedUri = data != null ? data.getData() : null;
        if (selectedUri == null) {
            showToast(localizedText("選択したファイルを開けません", "Could not open the selected file"));
            return;
        }

        int grantedFlags = data != null ? data.getFlags() : 0;
        int persistableReadFlags = grantedFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (persistableReadFlags != 0) {
            try {
                getContentResolver().takePersistableUriPermission(selectedUri, persistableReadFlags);
            } catch (SecurityException e) {
                Log.w(TAG, "Persistable read permission unavailable for imported model URI", e);
            }
        }

        importModelFromUri(selectedUri);
    }

    @Override
    public void finish() {
        if (!saveSharedSettings()) {
            return;
        }
        int apiPort = resolveApiPortFromUi();
        
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
