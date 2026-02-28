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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_documents);

        documentSpinner = findViewById(R.id.documentSpinner);
        documentTitle = findViewById(R.id.documentTitle);
        documentContent = findViewById(R.id.documentContent);
        copyButton = findViewById(R.id.copyDocumentButton);
        backButton = findViewById(R.id.backButton);

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
        showToast(label + " copied to clipboard");
    }

    private void showToast(final String msg) {
        runOnUiThread(() -> {
            Toast toast = Toast.makeText(DocumentsActivity.this, msg, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        });
    }

    private Document[] buildDocuments() {
        String manualTitle = "User Manual / 操作マニュアル";
        String manualText = buildManualText();

        String privacyTitle = "Privacy Policy / プライバシーポリシー";
        String privacyText = buildPrivacyText();

        return new Document[] {
            new Document(manualTitle, manualText),
            new Document(privacyTitle, privacyText)
        };
    }

    private String buildManualText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[日本語]\n");
        sb.append("操作マニュアル\n\n");
        sb.append("1. アプリ概要\n");
        sb.append("アプリ名: LLM tester with llama.cpp\n");
        sb.append("Built with Llama (llama.cpp)\n");
        sb.append("本アプリは端末上でLLMを実行し、プロンプトに対する回答を生成します。\n");
        sb.append("必要に応じてOllama互換APIサーバーを起動できます。\n\n");
        sb.append("2. 初期設定（推奨手順）\n");
        sb.append("1) メイン画面で「Settings」を開きます。\n");
        sb.append("   ※推論中（Busy）はSettingsボタンが無効化されます。処理完了後に自動で再有効化されます。\n");
        sb.append("2) モデルURLを入力し「Load Model」を押してモデルを読み込みます。\n");
        sb.append("3) パラメータを調整する場合は各項目を編集し「Save Config」で保存します。\n");
        sb.append("4) 「SAVE & CLOSE」を押すと設定が保存され、モデルに即座に適用されます。\n\n");
        sb.append("3. メイン画面\n");
        sb.append("- Enter Prompt: プロンプトを入力します。\n");
        sb.append("- Send: 生成を開始します。モデル未ロード時は自動でロードを試みます。\n");
        sb.append("- Settings: 推論中（Busy）は自動的に無効化され、処理完了後に再び有効になります。\n");
        sb.append("- Re-init Model: 現在のモデルを解放して再初期化します。\n");
        sb.append("- View Log: ログファイルの最新100行を出力欄に表示します。\n");
        sb.append("- Clear Log: ログファイルを空にします。\n");
        sb.append("- Start/Stop API Server: APIサーバーを起動/停止します。\n");
        sb.append("- Copy: 表示中の出力/ログをクリップボードへコピーします。\n");
        sb.append("- Download: View Log表示中のログ（最新100行）を保存先を指定して保存します。\n");
        sb.append("- Processing Status/Logs: タイムスタンプ付きでログが表示されます。\n\n");
        sb.append("4. 設定画面の操作\n");
        sb.append("- Configuration Management: 設定の保存/削除/読み込みを行います。\n");
        sb.append("- Model Selection: モデルURLを指定し読み込みます。\n");
        sb.append("- Model Parameters: 生成パラメータを設定します。\n");
        sb.append("- Output Settings: Streaming出力の有効/無効を切り替えます。\n");
        sb.append("- Prompt Template: System Promptとカスタムテンプレートを設定できます。カスタム未設定時はモデルファミリーから自動選択されます。\n");
        sb.append("- Llama API Server: サーバーポートを指定します。\n");
        sb.append("- Log Settings: ログレベルを選択します（初回起動時の既定値: INFO）。\n");
        sb.append("- Show License: ライセンス文面を表示します。\n");
        sb.append("- Documents: 操作マニュアル、プライバシーポリシーを確認できます。\n");
        sb.append("- SAVE & CLOSE: 現在の入力値を保存し、モデルに即時適用してメイン画面に戻ります。\n");
        sb.append("- CLOSE: 何も保存せずメイン画面に戻ります。\n\n");
        sb.append("5. モデルパラメータの詳細説明\n\n");
        sb.append("【基本パラメータ】\n");
        sb.append("- Context Size (n_ctx): モデルが一度に処理できるトークン数。大きいほど長い文脈を扱えますが、メモリ消費が増加します。\n");
        sb.append("- Threads (n_threads): 推論に使用するCPUスレッド数。端末のコア数に合わせて調整してください。\n");
        sb.append("- Batch Size (n_batch): 一度に処理するトークン数。大きくすると高速ですがメモリを多く使用します。\n");
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
        sb.append("7. プロンプトテンプレートの自動選択\n");
        sb.append("カスタムテンプレートが設定されていない場合、モデルファイル名からファミリーを推定しテンプレートを自動選択します。\n");
        sb.append("対応ファミリー: Gemma, Qwen, Mistral, LLaMA, Phi, Zephyr, Hermes。該当なしの場合はChatMLをフォールバックとして使用します。\n");
        sb.append("選択結果はProcessing Status/LogsおよびINFOレベルログに記録されます。\n");
        sb.append("/api/chatの会話履歴はモデルファミリー別のマルチターンテンプレートに従って構成されます。\n\n");
        sb.append("8. 停止シーケンス\n");
        sb.append("生成時に一般的なチャットテンプレートの区切り文字を検出すると自動的に生成を停止します。\n\n");
        sb.append("9. APIサーバー（任意）\n");
        sb.append("- 起動すると端末内で /api/chat, /api/generate, /api/tags を提供します。\n");
        sb.append("- 同時生成は1件のみです（ビジー時は503を返します）。\n");
        sb.append("- Android 13以上では通知権限が必要な場合があります。\n\n");
        sb.append("[English]\n");
        sb.append("User Manual\n\n");
        sb.append("1. Overview\n");
        sb.append("App name: LLM tester with llama.cpp\n");
        sb.append("Built with Llama (llama.cpp).\n");
        sb.append("This app runs an LLM on your device and generates responses to prompts.\n");
        sb.append("An Ollama-compatible API server can be started if needed.\n\n");
        sb.append("2. Recommended Setup\n");
        sb.append("1) Open \"Settings\" from the main screen.\n");
        sb.append("   * During inference (Busy), the Settings button is disabled and is re-enabled automatically when processing completes.\n");
        sb.append("2) Enter the model URL and tap \"Load Model\".\n");
        sb.append("3) Edit parameters if needed and tap \"Save Config\".\n");
        sb.append("4) Tap \"SAVE & CLOSE\" to save settings and apply them to the model immediately.\n\n");
        sb.append("3. Main Screen\n");
        sb.append("- Enter Prompt: Type your prompt.\n");
        sb.append("- Send: Start generation. If the model is not loaded, it will be loaded automatically.\n");
        sb.append("- Settings: Automatically disabled while inference is busy, and re-enabled when busy is cleared.\n");
        sb.append("- Re-init Model: Free and re-initialize the current model.\n");
        sb.append("- View Log: Show the latest 100 lines from the log file in the output area.\n");
        sb.append("- Clear Log: Clear the log file.\n");
        sb.append("- Start/Stop API Server: Toggle the API server.\n");
        sb.append("- Copy: Copy displayed output/log to clipboard.\n");
        sb.append("- Download: While View Log is active, save the displayed log (latest 100 lines) to a location you choose.\n");
        sb.append("- Processing Status/Logs: Logs are displayed with timestamps.\n\n");
        sb.append("4. Settings Screen\n");
        sb.append("- Configuration Management: Save/delete/load configurations.\n");
        sb.append("- Model Selection: Set model URL and load it.\n");
        sb.append("- Model Parameters: Set generation parameters.\n");
        sb.append("- Output Settings: Toggle streaming output on/off.\n");
        sb.append("- Prompt Template: Set System Prompt and custom chat template. When no custom template is set, one is auto-selected based on model family.\n");
        sb.append("- Llama API Server: Set server port.\n");
        sb.append("- Log Settings: Select log level (default on first launch: INFO).\n");
        sb.append("- Show License: Display license text.\n");
        sb.append("- Documents: View manual and privacy policy.\n");
        sb.append("- SAVE & CLOSE: Save current settings and apply them to the model immediately.\n");
        sb.append("- CLOSE: Return to main screen without saving any changes.\n\n");
        sb.append("5. Model Parameter Details\n\n");
        sb.append("[Basic Parameters]\n");
        sb.append("- Context Size (n_ctx): Number of tokens the model can process at once. Larger values handle longer contexts but use more memory.\n");
        sb.append("- Threads (n_threads): Number of CPU threads for inference. Adjust based on your device's core count.\n");
        sb.append("- Batch Size (n_batch): Number of tokens processed at once. Larger is faster but uses more memory.\n");
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
        sb.append("7. Prompt Template Auto-Selection\n");
        sb.append("When no custom template is set, the app estimates the model family from the filename and auto-selects an appropriate template.\n");
        sb.append("Supported families: Gemma, Qwen, Mistral, LLaMA, Phi, Zephyr, Hermes. Falls back to ChatML if unrecognized.\n");
        sb.append("Selection results are logged to Processing Status/Logs and INFO-level logs.\n");
        sb.append("Conversation history from /api/chat is formatted using model-family-specific multi-turn templates.\n\n");
        sb.append("8. Stop Sequences\n");
        sb.append("Generation automatically stops when common chat template delimiters are detected in the output.\n\n");
        sb.append("9. API Server (Optional)\n");
        sb.append("- Provides /api/chat, /api/generate, /api/tags on device.\n");
        sb.append("- Only one generation at a time (busy returns 503).\n");
        sb.append("- Android 13+ may require notification permission.\n");
        return sb.toString();
    }

    private String buildPrivacyText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[日本語]\n");
        sb.append("プライバシーポリシー\n\n");
        sb.append("1. 収集する情報\n");
        sb.append("本アプリは、個人情報を外部サーバーへ送信しません。\n");
        sb.append("以下の情報を端末内に保存します。\n");
        sb.append("- 設定情報（モデルURL、各種パラメータ、APIポート、ログレベル）\n");
        sb.append("- 構成ファイル（configs/*.json）\n");
        sb.append("- ダウンロードしたモデルファイル\n");
        sb.append("- ログファイル（ollama.log）\n\n");
        sb.append("2. 通信\n");
        sb.append("- モデルのダウンロード時に、入力されたURLへ通信します。\n");
        sb.append("- APIサーバーを有効にした場合、端末のポートでリクエストを受け付けます。\n");
        sb.append("  入力内容は端末内で処理され、アプリが外部サーバーへ送信することはありません。\n\n");
        sb.append("3. 利用目的\n");
        sb.append("上記データはアプリの動作、生成機能、表示のために使用します。\n\n");
        sb.append("4. ログ\n");
        sb.append("ログにはアプリの動作状況やAPIリクエスト情報が記録される場合があります。\n\n");
        sb.append("5. 保存期間\n");
        sb.append("設定・モデル・ログは、ユーザーが削除するかアプリのデータを消去するまで保持されます。\n");
        sb.append("ログはメイン画面の「Clear Log」で削除できます。\n\n");
        sb.append("6. お問い合わせ\n");
        sb.append("ライセンス記載の連絡先へお問い合わせください。\n\n");
        sb.append("[English]\n");
        sb.append("Privacy Policy\n\n");
        sb.append("1. Data Collected\n");
        sb.append("This app does not send personal data to external servers.\n");
        sb.append("It stores the following on the device:\n");
        sb.append("- Settings (model URL, parameters, API port, log level)\n");
        sb.append("- Configuration files (configs/*.json)\n");
        sb.append("- Downloaded model files\n");
        sb.append("- Log file (ollama.log)\n\n");
        sb.append("2. Network Communication\n");
        sb.append("- The app connects to the URL you provide when downloading models.\n");
        sb.append("- When the API server is enabled, it listens on a device port for requests.\n");
        sb.append("  Inputs are processed on device; the app does not send them to external servers.\n\n");
        sb.append("3. Purpose of Use\n");
        sb.append("These data are used to run the app, generate responses, and display status.\n\n");
        sb.append("4. Logs\n");
        sb.append("Logs may contain operational status and API request information.\n\n");
        sb.append("5. Retention\n");
        sb.append("Settings, models, and logs remain until you delete them or clear app data.\n");
        sb.append("Logs can be cleared from the main screen using \"Clear Log\".\n\n");
        sb.append("6. Contact\n");
        sb.append("Please contact the address listed in the license.\n");
        return sb.toString();
    }
}
