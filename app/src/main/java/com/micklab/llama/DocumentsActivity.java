package com.micklab.llama;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class DocumentsActivity extends Activity {
    private TextView documentsHeader;
    private TextView selectDocumentLabel;
    private Spinner documentSpinner;
    private TextView documentTitle;
    private TextView documentContent;
    private Button copyButton;
    private Button backButton;

    private static class Document {
        final String title;
        final String content;

        Document(String title, String content) {
            this.title = title;
            this.content = content;
        }
    }

    private Document[] documents;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_documents);

        documentsHeader = findViewById(R.id.documentsHeader);
        selectDocumentLabel = findViewById(R.id.selectDocumentLabel);
        documentSpinner = findViewById(R.id.documentSpinner);
        documentTitle = findViewById(R.id.documentTitle);
        documentContent = findViewById(R.id.documentContent);
        copyButton = findViewById(R.id.copyDocumentButton);
        backButton = findViewById(R.id.backButton);
        applyLocalizedUiText();

        documents = buildDocuments();

        String[] titles = new String[documents.length];
        for (int i = 0; i < documents.length; i++) {
            titles[i] = documents[i].title;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, titles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        documentSpinner.setAdapter(adapter);

        documentSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                showDocument(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No-op
            }
        });

        copyButton.setOnClickListener(v -> copyToClipboard(documentTitle.getText().toString(), documentContent.getText().toString()));
        backButton.setOnClickListener(v -> finish());

        if (documents.length > 0) {
            showDocument(0);
        }
    }

    private String localizedText(String ja, String en) {
        return AppLanguageManager.isJapanese(this) ? ja : en;
    }

    private void applyLocalizedUiText() {
        if (documentsHeader != null) {
            documentsHeader.setText(localizedText("ドキュメント", "Documents"));
        }
        if (selectDocumentLabel != null) {
            selectDocumentLabel.setText(localizedText("表示するドキュメント:", "Select document:"));
        }
        if (copyButton != null) {
            copyButton.setText(localizedText("コピー", "Copy"));
        }
        if (backButton != null) {
            backButton.setText(localizedText("戻る", "Back"));
        }
    }

    private void showDocument(int index) {
        if (index < 0 || index >= documents.length) {
            return;
        }
        Document doc = documents[index];
        documentTitle.setText(doc.title);
        documentContent.setText(doc.content);
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        showToast(localizedText("クリップボードにコピーしました", label + " copied to clipboard"));
    }

    private void showToast(final String msg) {
        runOnUiThread(() -> {
            Toast toast = Toast.makeText(DocumentsActivity.this, msg, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        });
    }

    private Document[] buildDocuments() {
        String manualTitle = localizedText("操作マニュアル / User Manual", "User Manual / 操作マニュアル");
        String manualText = buildManualText();

        String gemma4Title = localizedText(
                "Gemma-4 プロンプトテンプレート仕様 / Gemma-4 Prompt Template Specification",
                "Gemma-4 Prompt Template Specification / Gemma-4 プロンプトテンプレート仕様");
        String gemma4Text = buildGemma4PromptTemplateSpecText();

        String privacyTitle = localizedText("プライバシーポリシー / Privacy Policy", "Privacy Policy / プライバシーポリシー");
        String privacyText = buildPrivacyText();

        return new Document[] {
            new Document(manualTitle, manualText),
            new Document(gemma4Title, gemma4Text),
            new Document(privacyTitle, privacyText)
        };
    }

    private String buildManualText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[日本語]\n");
        sb.append("操作マニュアル\n\n");
        sb.append("【重要】モデルのダウンロードには数GB単位の通信が必要になる場合があります。モバイルデータ通信を使用すると高額な通信料が発生する可能性があるため、可能な限りWi-Fi環境でのダウンロードを強く推奨します。\n\n");
        sb.append("1. アプリ概要\n");
        sb.append("アプリ名: LLM tester with llama.cpp\n");
        sb.append("Built with Llama (llama.cpp)\n");
        sb.append("本アプリは端末上でLLMを実行し、プロンプトに対する回答を生成します。\n");
        sb.append("必要に応じてOllama互換APIサーバーを起動できます。\n\n");
        sb.append("2. 初期設定（推奨手順）\n");
        sb.append("0) アプリ起動時にAPI有効化ポップアップが表示された場合は、必要に応じて有効化します。\n");
        sb.append("1) 初回起動時のQuick Startで「次回以降は表示しない」をチェックすると、次回起動以降は表示されません。\n");
        sb.append("2) メイン画面で「Settings」を開きます。\n");
        sb.append("   ※推論中（Busy）はSettingsボタンが無効化されます。処理完了後に自動で再有効化されます。\n");
        sb.append("3) モデルURLを入力し「Load Model」を押してモデルを読み込みます。\n");
        sb.append("   ※任意のHTTP/HTTPS URLを利用できます。HTTPSでは通常のSSL/TLS証明書検証を行います。\n");
        sb.append("4) パラメータを調整する場合は各項目を編集し「Save Config」で保存します。\n");
        sb.append("5) 「SAVE & CLOSE」を押すと設定が保存され、モデルに即座に適用されます。\n\n");
        sb.append("3. メイン画面\n");
        sb.append("- Enter Prompt: プロンプトを入力します。\n");
        sb.append("- Send: 生成を開始します。モデル未ロード時は自動でロードを試みます。\n");
        sb.append("- Settings: 推論中（Busy）は自動的に無効化され、処理完了後に再び有効になります。\n");
        sb.append("- Re-init Model: アプリを終了せず、現在のプロファイルのモデルを再初期化します。失敗した場合はログを確認するか、Settings から再度 Load Model を実行してください。\n");
        sb.append("- View Log: ログファイルの最新100行を出力欄に表示します。\n");
        sb.append("- Clear Log: ログファイルを空にします。\n");
        sb.append("- Start/Stop API Server: APIサーバーを起動/停止します。\n");
        sb.append("- Copy: 表示中の出力/ログをクリップボードへコピーします。\n");
        sb.append("- Download: View Log表示中のログ（最新100行）を保存先を指定して保存します。\n");
        sb.append("- Processing Status/Logs: タイムスタンプ付きでログが表示されます。\n\n");
        sb.append("4. 設定画面の操作\n");
        sb.append("- Configuration Management: 設定の保存/削除/読み込みを行います。\n");
        sb.append("- Model Selection: モデルURLを指定して読み込みます。任意のHTTP/HTTPS URLを利用でき、HTTPSでは通常のSSL/TLS証明書検証を行います。\n");
        sb.append("- Model Parameters: 生成パラメータを設定します（GPU Offload スイッチを含む）。\n");
        sb.append("- Output Settings: Streaming出力の有効/無効を切り替えます。\n");
        sb.append("- Prompt Template: System Prompt、Think有効/無効（chat-template-kwargs.enable_thinking）、カスタムテンプレートを設定できます。カスタム未設定時はモデルファミリーから自動選択されます。\n");
        sb.append("- Llama API Server: サーバーポートを指定します。APIの起動/停止は起動時ポップアップまたはメイン画面から行います。\n");
        sb.append("- Display Language: 日本語/English の表示言語を切り替えます。初回は端末設定から自動選択され、次回以降は選択が保存されます。\n");
        sb.append("- Log Settings: ログレベルを選択します（初回起動時の既定値: INFO）。\n");
        sb.append("- Show License: ライセンス文面を表示します。\n");
        sb.append("- Documents: 操作マニュアル、Gemma-4プロンプトテンプレート仕様、プライバシーポリシーを確認できます。\n");
        sb.append("- SAVE & CLOSE: 現在の入力値を保存し、モデルに即時適用してメイン画面に戻ります。\n");
        sb.append("- CLOSE: 何も保存せずメイン画面に戻ります。\n\n");
        sb.append("5. モデルパラメータの詳細説明\n\n");
        sb.append("【基本パラメータ】\n");
        sb.append("- Context Size (n_ctx): モデルが一度に処理できるトークン数。大きいほど長い文脈を扱えますが、メモリ消費が増加します。\n");
        sb.append("- Threads (n_threads): 推論に使用するCPUスレッド数。端末のコア数に合わせて調整してください。\n");
        sb.append("- Batch Size (n_batch): 一度に処理するトークン数。大きくすると高速ですがメモリを多く使用します。\n");
        sb.append("- Enable GPU Offload: 有効にすると対応バックエンド利用時にGPUへオフロードを試みます。無効でCPUのみを使用します。\n");
        sb.append("- Temperature (temp): 出力のランダム性を制御。0に近いほど決定的、高いほど多様な出力になります。\n");
        sb.append("- Top-p: 累積確率がこの値に達するまでのトークンから選択します（nucleus sampling）。\n");
        sb.append("- Top-k: 確率上位k個のトークンから選択します。\n\n");
        sb.append("【ペナルティパラメータ】\n");
        sb.append("- Penalty Last N: ペナルティを適用する直近のトークン数。\n");
        sb.append("- Penalty Repeat: 繰り返しトークンへのペナルティ倍率。1.0で無効、高いほど繰り返しを抑制。\n");
        sb.append("- Penalty Frequency: 出現頻度に応じたペナルティ。\n");
        sb.append("- Penalty Presence: 一度出現したトークンへのペナルティ。\n\n");
        sb.append("【Mirostatパラメータ】\n");
        sb.append("- Mirostat: 0=無効、1=Mirostat v1、2=Mirostat v2。出力の一貫性を自動調整するサンプリング手法。\n");
        sb.append("- Mirostat Tau: 目標のサプライズ値（perplexity）。低いとより一貫性のある出力。\n");
        sb.append("- Mirostat Eta: 学習率。Mirostatのフィードバック速度を制御。\n\n");
        sb.append("【追加サンプリングパラメータ】\n");
        sb.append("- Min-p: 最低確率閾値。これ以下の確率のトークンを除外。\n");
        sb.append("- Typical P: 典型的なサンプリングのパラメータ。\n");
        sb.append("- Dynamic Temperature Range: 動的温度調整の範囲。0で無効。\n");
        sb.append("- Dynamic Temperature Exponent: 動的温度の指数。\n");
        sb.append("- XTC Probability: XTCサンプリングの確率。\n");
        sb.append("- XTC Threshold: XTCサンプリングの閾値。\n");
        sb.append("- Top-N-Sigma: シグマベースのサンプリング。-1で無効。\n\n");
        sb.append("【DRYパラメータ】\n");
        sb.append("- DRY Multiplier: Don't Repeat Yourself繰り返し抑制の強度。0で無効。\n");
        sb.append("- DRY Base: DRYペナルティの基数。\n");
        sb.append("- DRY Allowed Length: 繰り返しを許容する最小長。\n");
        sb.append("- DRY Penalty Last N: DRYペナルティを適用するトークン数。-1で全体に適用。\n");
        sb.append("- DRY Sequence Breakers: DRYの区切り文字。\n\n");
        sb.append("【出力設定】\n");
        sb.append("- Enable Streaming: 有効にするとトークンが生成されるたびに出力が更新されます。無効にすると生成完了後に一括表示されます。\n\n");
        sb.append("【Think設定】\n");
        sb.append("- Enable Think: chat-template-kwargs の enable_thinking を切り替えます。無効時はモデルの思考出力を抑制する形式でプロンプトを生成します。\n\n");
        sb.append("7. プロンプトテンプレートの自動選択\n");
        sb.append("カスタムテンプレートが設定されていない場合、モデルファイル名からファミリーを推定しテンプレートを自動選択します。\n");
        sb.append("対応ファミリー: Gemma, Qwen, Mistral, LLaMA, Phi, Zephyr, Hermes。該当なしの場合はChatMLをフォールバックとして使用します。\n");
        sb.append("Gemma系では system / user / model の順序を維持し、Gemma-4 用の正式仕様は Documents 内の専用ドキュメントに掲載しています。\n");
        sb.append("選択結果はProcessing Status/LogsおよびINFOレベルログに記録されます。\n");
        sb.append("/api/chatの会話履歴はモデルファミリー別のマルチターンテンプレートに従って構成されます。\n\n");
        sb.append("8. 停止シーケンス\n");
        sb.append("生成時に一般的なチャットテンプレートの区切り文字を検出すると自動的に生成を停止します。\n\n");
        sb.append("9. APIサーバー（任意）\n");
        sb.append("- アプリ起動時にローカルAPIサーバーを有効化するかどうか確認するポップアップが表示されます。\n");
        sb.append("- 起動すると端末内で /api/chat, /api/generate, /api/tags を提供します。\n");
        sb.append("- 同時生成は1件のみです。ビジー時は最大10件までキューに入り、最大60秒待機します。60秒超過またはキュー満杯時は503を返します。\n");
        sb.append("- Android 13以上では通知権限が必要な場合があります。\n\n");
        sb.append("[English]\n");
        sb.append("User Manual\n\n");
        sb.append("IMPORTANT: Downloading models may require gigabytes of data. Using mobile/cellular data may incur significant charges; downloading over Wi-Fi is strongly recommended.\n\n");
        sb.append("1. Overview\n");
        sb.append("App name: LLM tester with llama.cpp\n");
        sb.append("Built with Llama (llama.cpp).\n");
        sb.append("This app runs an LLM on your device and generates responses to prompts.\n");
        sb.append("An Ollama-compatible API server can be started if needed.\n\n");
        sb.append("2. Recommended Setup\n");
        sb.append("0) If the API enablement popup appears at launch, enable it when needed.\n");
        sb.append("1) On first launch, if you check \"Don't show next time\" in Quick Start, it will not be shown on subsequent launches.\n");
        sb.append("2) Open \"Settings\" from the main screen.\n");
        sb.append("   * During inference (Busy), the Settings button is disabled and is re-enabled automatically when processing completes.\n");
        sb.append("3) Enter the model URL and tap \"Load Model\".\n");
        sb.append("   * Any reachable HTTP/HTTPS URL can be used. HTTPS uses normal SSL/TLS certificate verification.\n");
        sb.append("4) Edit parameters if needed and tap \"Save Config\".\n");
        sb.append("5) Tap \"SAVE & CLOSE\" to save settings and apply them to the model immediately.\n\n");
        sb.append("3. Main Screen\n");
        sb.append("- Enter Prompt: Type your prompt.\n");
        sb.append("- Send: Start generation. If the model is not loaded, it will be loaded automatically.\n");
        sb.append("- Settings: Automatically disabled while inference is busy, and re-enabled when busy is cleared.\n");
        sb.append("- Re-init Model: Reinitialize the currently selected profile without terminating the app. If it fails, check the log or load the model again from Settings.\n");
        sb.append("- View Log: Show the latest 100 lines from the log file in the output area.\n");
        sb.append("- Clear Log: Clear the log file.\n");
        sb.append("- Start/Stop API Server: Toggle the API server.\n");
        sb.append("- Copy: Copy displayed output/log to clipboard.\n");
        sb.append("- Download: While View Log is active, save the displayed log (latest 100 lines) to a location you choose.\n");
        sb.append("- Processing Status/Logs: Logs are displayed with timestamps.\n\n");
        sb.append("4. Settings Screen\n");
        sb.append("- Configuration Management: Save/delete/load configurations.\n");
        sb.append("- Model Selection: Set the model URL and load it. Any reachable HTTP/HTTPS URL can be used, and HTTPS uses normal SSL/TLS certificate verification.\n");
        sb.append("- Model Parameters: Set generation parameters (including the GPU Offload switch).\n");
        sb.append("- Output Settings: Toggle streaming output on/off.\n");
        sb.append("- Prompt Template: Set System Prompt, Think on/off (chat-template-kwargs.enable_thinking), and custom chat template. When no custom template is set, one is auto-selected based on model family.\n");
        sb.append("- Llama API Server: Set the server port. Start/stop is handled from the startup popup or the main screen.\n");
        sb.append("- Display Language: Switch UI language between Japanese and English. On first launch it follows your device locale, and your choice is saved for later launches.\n");
        sb.append("- Log Settings: Select log level (default on first launch: INFO).\n");
        sb.append("- Show License: Display license text.\n");
        sb.append("- Documents: View the manual, the Gemma-4 prompt template specification, and the privacy policy.\n");
        sb.append("- SAVE & CLOSE: Save current settings and apply them to the model immediately.\n");
        sb.append("- CLOSE: Return to main screen without saving any changes.\n\n");
        sb.append("5. Model Parameter Details\n\n");
        sb.append("[Basic Parameters]\n");
        sb.append("- Context Size (n_ctx): Number of tokens the model can process at once. Larger values handle longer contexts but use more memory.\n");
        sb.append("- Threads (n_threads): Number of CPU threads for inference. Adjust based on your device's core count.\n");
        sb.append("- Batch Size (n_batch): Number of tokens processed at once. Larger is faster but uses more memory.\n");
        sb.append("- Enable GPU Offload: When enabled, the app tries to offload layers to GPU on supported backends. Disable to run CPU-only.\n");
        sb.append("- Temperature (temp): Controls output randomness. Lower is more deterministic, higher is more diverse.\n");
        sb.append("- Top-p: Select from tokens until cumulative probability reaches this value (nucleus sampling).\n");
        sb.append("- Top-k: Select from top k probability tokens.\n\n");
        sb.append("[Penalty Parameters]\n");
        sb.append("- Penalty Last N: Number of recent tokens to apply penalties to.\n");
        sb.append("- Penalty Repeat: Multiplier for repeat token penalty. 1.0 disables, higher suppresses repetition.\n");
        sb.append("- Penalty Frequency: Penalty based on token frequency.\n");
        sb.append("- Penalty Presence: Penalty for tokens that appeared before.\n\n");
        sb.append("[Mirostat Parameters]\n");
        sb.append("- Mirostat: 0=disabled, 1=Mirostat v1, 2=Mirostat v2. Auto-adjusts output consistency.\n");
        sb.append("- Mirostat Tau: Target surprise value (perplexity). Lower for more consistent output.\n");
        sb.append("- Mirostat Eta: Learning rate for Mirostat feedback.\n\n");
        sb.append("[Additional Sampling Parameters]\n");
        sb.append("- Min-p: Minimum probability threshold. Excludes tokens below this probability.\n");
        sb.append("- Typical P: Parameter for typical sampling.\n");
        sb.append("- Dynamic Temperature Range: Range for dynamic temperature adjustment. 0 disables.\n");
        sb.append("- Dynamic Temperature Exponent: Exponent for dynamic temperature.\n");
        sb.append("- XTC Probability: Probability for XTC sampling.\n");
        sb.append("- XTC Threshold: Threshold for XTC sampling.\n");
        sb.append("- Top-N-Sigma: Sigma-based sampling. -1 disables.\n\n");
        sb.append("[DRY Parameters]\n");
        sb.append("- DRY Multiplier: Don't Repeat Yourself penalty strength. 0 disables.\n");
        sb.append("- DRY Base: Base value for DRY penalty.\n");
        sb.append("- DRY Allowed Length: Minimum length for allowed repetitions.\n");
        sb.append("- DRY Penalty Last N: Number of tokens for DRY penalty. -1 applies to all.\n");
        sb.append("- DRY Sequence Breakers: Characters that break DRY sequences.\n\n");
        sb.append("[Output Settings]\n");
        sb.append("- Enable Streaming: When enabled, output updates as tokens are generated. When disabled, output shows all at once after generation completes.\n\n");
        sb.append("[Think Settings]\n");
        sb.append("- Enable Think: Toggles chat-template-kwargs enable_thinking. When disabled, prompts are formatted to suppress visible thinking output.\n\n");
        sb.append("7. Prompt Template Auto-Selection\n");
        sb.append("When no custom template is set, the app estimates the model family from the filename and auto-selects an appropriate template.\n");
        sb.append("Supported families: Gemma, Qwen, Mistral, LLaMA, Phi, Zephyr, Hermes. Falls back to ChatML if unrecognized.\n");
        sb.append("For the Gemma family, the app keeps the system / user / model order, and the dedicated Gemma-4 specification is available in Documents.\n");
        sb.append("Selection results are logged to Processing Status/Logs and INFO-level logs.\n");
        sb.append("Conversation history from /api/chat is formatted using model-family-specific multi-turn templates.\n\n");
        sb.append("8. Stop Sequences\n");
        sb.append("Generation automatically stops when common chat template delimiters are detected in the output.\n\n");
        sb.append("9. API Server (Optional)\n");
        sb.append("- On app launch, a popup asks whether to enable the local API server.\n");
        sb.append("- Provides /api/chat, /api/generate, /api/tags on device.\n");
        sb.append("- Only one generation runs at a time. When busy, requests are queued (up to 10) and wait up to 60 seconds; queue overflow or timeout returns 503.\n");
        sb.append("- Android 13+ may require notification permission.\n");
        sb.append("\n");
        sb.append("[TIPS]\n");
        sb.append("日本語: 大きなモデルのロードは、アドレス空間の確保失敗またはユーザ操作により中断される場合があります。その場合は次回起動時に一時ファイルを削除して通知を表示します。必要に応じて、より小さいモデルを試すか、Settings から再度 Load Model を実行してください。\n\n");
        sb.append("English: Loading a very large model may stop because address-space reservation fails or because the process was interrupted by user action. In that case the app clears temporary load files on the next launch and shows a notice. If needed, try a smaller model or load the model again from Settings.\n");
        return sb.toString();
    }

    private String buildGemma4PromptTemplateSpecText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[日本語]\n");
        sb.append("Gemma-4 用プロンプトテンプレート正式仕様\n\n");
        sb.append("1. 正式仕様\n");
        sb.append("- Gemma-4 では system / user / model の3ロール構造を使用します。\n");
        sb.append("- system ロールは必ず最初に配置します。\n");
        sb.append("- user ロールにはユーザー入力をそのまま格納します。\n");
        sb.append("- model ロールは空で開始し、ここからモデル出力を生成します。\n");
        sb.append("- 追加のメタデータ、補助タグ、変換用ラッパーは付与しません。\n\n");
        sb.append("テンプレート本体:\n");
        sb.append("```\n");
        sb.append("<start_of_turn>system\n");
        sb.append("（システム指示）\n");
        sb.append("<end_of_turn>\n\n");
        sb.append("<start_of_turn>user\n");
        sb.append("（ユーザー入力）\n");
        sb.append("<end_of_turn>\n\n");
        sb.append("<start_of_turn>model\n");
        sb.append("```\n\n");
        sb.append("2. 各ロールの役割\n");
        sb.append("- system: モデルの役割、禁止事項、出力形式、応答方針を記述します。\n");
        sb.append("- user: ユーザーからの指示や質問をそのまま記述します。\n");
        sb.append("- model: モデルが応答を書き始める開始位置です。事前テキストは入れません。\n\n");
        sb.append("3. サンプル\n");
        sb.append("```\n");
        sb.append("<start_of_turn>system\n");
        sb.append("あなたは簡潔な日本語で回答する技術アシスタントです。箇条書きは必要な場合のみ使ってください。\n");
        sb.append("<end_of_turn>\n\n");
        sb.append("<start_of_turn>user\n");
        sb.append("Android でローカル推論を安定化するための注意点を3つ教えてください。\n");
        sb.append("<end_of_turn>\n\n");
        sb.append("<start_of_turn>model\n");
        sb.append("```\n\n");
        sb.append("4. 実装者向けの注意点\n");
        sb.append("- Android アプリ内のローカル推論では、テンプレート文字列はこの最小構成のまま保持し、不要な装飾を足さないでください。\n");
        sb.append("- system 指示が未設定でも、Gemma-4 では先頭の system ターンを保持する実装にするとプロトコルが安定します。本文は空で構いません。\n");
        sb.append("- user 入力は別形式へ変換せず、そのまま user ターンに格納してください。\n");
        sb.append("- model ターン開始後は空のまま推論を呼び出し、生成テキストを後続に連結してください。\n\n");

        sb.append("[English]\n");
        sb.append("Formal Gemma-4 Prompt Template Specification\n\n");
        sb.append("1. Formal specification\n");
        sb.append("- Gemma-4 uses a three-role structure: system / user / model.\n");
        sb.append("- The system role must always appear first.\n");
        sb.append("- The user role stores the user instruction as-is.\n");
        sb.append("- The model role starts empty, and generation begins from that point.\n");
        sb.append("- Do not add extra metadata, helper tags, or wrapper markers.\n\n");
        sb.append("Template body:\n");
        sb.append("```\n");
        sb.append("<start_of_turn>system\n");
        sb.append("(system instruction)\n");
        sb.append("<end_of_turn>\n\n");
        sb.append("<start_of_turn>user\n");
        sb.append("(user input)\n");
        sb.append("<end_of_turn>\n\n");
        sb.append("<start_of_turn>model\n");
        sb.append("```\n\n");
        sb.append("2. Role descriptions\n");
        sb.append("- system: Defines role, constraints, output format, and response policy.\n");
        sb.append("- user: Contains the user request exactly as provided.\n");
        sb.append("- model: Marks the point where model generation begins; leave it empty.\n\n");
        sb.append("3. Sample\n");
        sb.append("```\n");
        sb.append("<start_of_turn>system\n");
        sb.append("You are a concise technical assistant. Use bullet points only when they improve clarity.\n");
        sb.append("<end_of_turn>\n\n");
        sb.append("<start_of_turn>user\n");
        sb.append("Give me three tips for stable local inference on Android.\n");
        sb.append("<end_of_turn>\n\n");
        sb.append("<start_of_turn>model\n");
        sb.append("```\n\n");
        sb.append("4. Implementation notes\n");
        sb.append("- Keep the template lightweight for on-device Android inference and avoid any unnecessary wrappers.\n");
        sb.append("- Even when no explicit system instruction is supplied, keeping the leading system turn with an empty body helps preserve the required protocol shape.\n");
        sb.append("- Write user input directly into the user turn without extra conversion.\n");
        sb.append("- Invoke inference immediately after the empty model turn and append generated text there.\n");
        return sb.toString();
    }

    private String buildPrivacyText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[日本語]\n");
        sb.append("プライバシーポリシー（日本語版）\n\n");

        sb.append("1. 収集する情報\n");
        sb.append("本アプリは、個人情報を外部サーバーへ送信しません。以下の情報を端末内に保存します。\n\n");
        sb.append("- 設定情報（モデルURL、各種パラメータ、APIポート、ログレベル）\n");
        sb.append("- 構成ファイル（configs/*.json）\n");
        sb.append("- ダウンロードしたモデルファイル\n");
        sb.append("- ログファイル（ollama.log、Javaクラッシュ時は last_crash.txt、ネイティブクラッシュ時は native_crash.txt）\n\n");

        sb.append("また、アプリの生成機能を利用する際、ユーザーが入力したテキスト（会話内容）は、端末内または同一ローカルネットワーク内で動作するローカル API に送信されます。このデータは外部サーバーへ送信されず、保存も行いません。\n\n");

        sb.append("---\n\n");

        sb.append("2. 通信\n");
        sb.append("- モデルのダウンロード時に、ユーザーが入力した URL へ通信します。\n");
        sb.append("- HTTPS URL を使用する場合は、通常のSSL/TLS証明書検証を行います。\n");
        sb.append("- API サーバーを有効にした場合、端末内またはローカルネットワーク内のポート（0.0.0.0）でリクエストを受け付けます。\n");
        sb.append("- ユーザーの入力内容（会話全文）はローカル API に送信されますが、インターネットを経由して外部サーバーへ送信されることはありません。\n");
        sb.append("- ローカルネットワーク内からアクセス可能ですが、外部ネットワークからのアクセスは意図されておらず、アプリは外部公開を行いません。\n");
        sb.append("- ローカル通信は暗号化されていない場合がありますが、通信は端末内または同一ネットワーク内に限定されます。\n\n");

        sb.append("3. 利用目的\n");
        sb.append("上記データは、アプリの動作、生成機能、表示のために使用します。ユーザーの入力内容は生成処理のためにのみ利用され、外部送信・第三者提供・保存は行いません。\n\n");

        sb.append("4. ログ\n");
        sb.append("ログにはアプリの動作状況や API リクエスト情報が記録される場合があります。ログは端末内にのみ保存され、外部へ送信されません。\n\n");

        sb.append("5. 保存期間\n");
        sb.append("設定・モデル・ログは、ユーザーが削除するかアプリのデータを消去するまで保持されます。ログはメイン画面の「Clear Log」で削除できます。\n\n");

        sb.append("6. お問い合わせ\n");
        sb.append("ご不明点がある場合は、以下のメールアドレスまでお問い合わせください。\n");
        sb.append("micklab2026@gmail.com\n\n");

        sb.append("© Mick Lab — 生成AIアプリケーション研究\n\n");

        sb.append("[English]\n");
        sb.append("Privacy Policy (English Version)\n\n");

        sb.append("1. Information Collected\n");
        sb.append("This application does not transmit any personal information to external servers. The following data is stored locally on the device:\n\n");
        sb.append("- Configuration data (model URL, parameters, API port, log level)\n");
        sb.append("- Configuration files (configs/*.json)\n");
        sb.append("- Downloaded model files\n");
        sb.append("- Log files (ollama.log, last_crash.txt for Java crashes, and native_crash.txt for native crashes)\n\n");

        sb.append("When using the generation features, the text entered by the user (conversation content) is sent to a local API running on the device or within the same local network. This data is not transmitted to external servers and is not stored.\n\n");

        sb.append("2. Communication\n");
        sb.append("- When downloading models, the application communicates with the URL entered by the user.\n");
        sb.append("- For HTTPS URLs, standard SSL/TLS certificate verification is used.\n");
        sb.append("- When the API server is enabled, it listens on a port (0.0.0.0) accessible within the device or the local network.\n");
        sb.append("- User input (full conversation text) is sent to the local API, but it is never transmitted over the internet or to any external server.\n");
        sb.append("- The API may be accessible from devices within the same local network, but it is not intended for external network access, and the application does not expose the API to the internet.\n");
        sb.append("- Local communication may be unencrypted, but all communication is restricted to the device or the same local network.\n\n");

        sb.append("3. Purpose of Use\n");
        sb.append("The collected data is used solely for application functionality, generation processing, and display. User input is used only for generation and is not transmitted externally, shared with third parties, or stored.\n\n");

        sb.append("4. Logs\n");
        sb.append("Logs may contain operational information or API request details. Logs are stored only on the device and are not transmitted externally.\n\n");

        sb.append("5. Retention Period\n");
        sb.append("Settings, models, and logs are retained until the user deletes them or clears the application data. Logs can be deleted using the \"Clear Log\" option on the main screen.\n\n");

        sb.append("6. Contact\n");
        sb.append("If you have any questions, please contact:\n");
        sb.append("micklab2026@gmail.com\n\n");

        sb.append("© Mick Lab — Generative AI Application Research\n");
        return sb.toString();
    }
}
