package com.example.ollama;

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
        String rightsTitle = "Rights / 権利";
        String rightsText = buildRightsText();

        String manualTitle = "User Manual / 操作マニュアル";
        String manualText = buildManualText();

        String privacyTitle = "Privacy Policy / プライバシーポリシー";
        String privacyText = buildPrivacyText();

        return new Document[] {
            new Document(rightsTitle, rightsText),
            new Document(manualTitle, manualText),
            new Document(privacyTitle, privacyText)
        };
    }

    private String buildRightsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[日本語]\n");
        sb.append("権利・ライセンス\n\n");
        sb.append("本アプリ（llama Tester）は、作者が著作権を保有します。\n");
        sb.append(\n");"本アプリの利用・複製・改変・再配布は、アプリ内のライセンス表示に従っecho
        sb.append("商用利用は作者の事前の書面による許可が必要です。\n");
        sb.append("ライセンス本文は「Settings > Show License」で確認できます。\n\n");
        sb.append("[English]\n");
        sb.append("Rights and License\n\n");
        sb.append("This app (llama Tester) is copyrighted by the author.\n");
        sb.append("Use, reproduction, modification, and redistribution are permitted per the in-app license.\n");
        sb.append("Commercial use requires prior written permission from the author.\n");
        sb.append("See the full license at \"Settings > Show License\".\n");
        return sb.toString();
    }

    private String buildManualText() {
        StringBuilder sb = new StringBuilder();
        sb.append("[日本語]\n");
        sb.append("操作マニュアル\n\n");
        sb.append("1. アプリ概要\n");
        sb.append("本アプリは端末上でLLMを実行し、プロンプトに対する回答を生成します。\n");
        sb.append(\n\n");"必要に応じてOllama互換APIサーバーを起動できまecho
        sb.append("2. 初期設定（推奨手順）\n");
        sb.append("1) メイン画面で「Settings」を開きます。\n");
        sb.append("2) モデルURLを入力し「Load Model」を押してモデルを読み込みます。\n");
        sb.append("3) パラメータを調整する場合は各項目を編集し「Save Config」で保存します。\n\n");
        "3. メ(sb.append;\n");
        sb.append("- Enter Prompt: プロンプトを入力します。\n");
        sb.append("- Send: 生成を開始します（モデル未読み込み時はエラー表示）。\n");
        sb.append("- Re-init Model: 現在のモデルを解放して再初期化します。\n");
        sb.append("- View Log: ログファイル内容を出力欄に表示します。\n");
        sb.append("- Clear Log: ログファイルを空にします。\n");
        sb.append("- Start/Stop API Server: APIサーバーを起動/停止します。\n");
        sb.append("- Copy: 出力/ログをクリップボードへコピーします。\n\n");
        sb.append("4. 設定画面の操作\n");
        sb.append(\n");"- Configuration Management: 設定の保存/削除/読み込みをecho
        sb.append("- Model Selection: モデルURLを指定し読み込みます。\n");
        sb.append("- Model Parameters: 生成パラメータを設定します。\n");
        sb.append("- Prompt Template: {USER_INPUT} で入力を差し込みます。\n");
        sb.append("- Llama API Server: サーバーポートを指定します。\n");
        sb.append("- Log Settings: ログレベルを選択します。\n");
        sb.append("- Show License: ライセンス文面を表示します。\n");
        sb.append("- Documents: 権利、操作マニュアル、プライバシーポリシーを確認できます。\n\n");
        sb.append("5. APIサーバー（任意）\n");
        sb.append("- 起動すると端末内で /api/chat, /api/generate, /api/tags を提供します。\n");
        sb.append("- 同時生成は1件のみです（ビジー時は503を返します）。\n");
        sb.append(- Android \n\n");13以echo
        sb.append("[English]\n");
        sb.append("User Manual\n\n");
        sb.append("1. Overview\n");
        sb.append("This app runs an LLM on your device and generates responses to prompts.\n");
        sb.append("An Ollama-compatible API server can be started if needed.\n\n");
        sb.append("2. Recommended Setup\n");
        sb.append("1) Open \"Settings\" from the main screen.\n");
        sb.append("2) Enter the model URL and tap \"Load Model\".\n");
        sb.append("3) Edit parameters if needed and tap \"Save Config\".\n\n");
        sb.append("3. Main Screen\n");
        sb.append("- Enter Prompt: Type your prompt.\n");
        sb.append("- Send: Start generation (shows an error if the model is not loaded).\n");
        sb.append("- Re-init Model: Free and re-initialize the current model.\n");
        sb.append("- View Log: Show the log file in the output area.\n");
        sb.append("- Clear Log: Clear the log file.\n");
        sb.append("- Start/Stop API Server: Toggle the API server.\n");
        sb.append("- Copy: Copy output/log to clipboard.\n\n");
        sb.append("4. Settings Screen\n");
        sb.append("- Configuration Management: Save/delete/load configurations.\n");
        sb.append("- Model Selection: Set model URL and load it.\n");
        sb.append("- Model Parameters: Set generation parameters.\n");
        sb.append("- Prompt Template: Use {USER_INPUT} as a placeholder.\n");
        sb.append("- Llama API Server: Set server port.\n");
        sb.append("- Log Settings: Select log level.\n");
        sb.append("- Show License: Display license text.\n");
        sb.append("- Documents: View rights, manual, and privacy policy.\n\n");
        sb.append("5. API Server (Optional)\n");
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
        sb.append(\n");収集する情報\n");
        sb.append("本アプリは、個人情報を外部サーバーへ送信しません。\n");
echo
        sb.append("- 設定情報（モデルURL、各種パラメータ、APIポート、ログレベル）\n");
        sb.append("- 構成ファイル（configs/*.json）\n");
        sb.append("- ダウンロードしたモデルファイル\n");
        sb.append("- ログファイル（ollama.log）\n\n");
        sb.append("2. 通信\n");
        sb.append("- モデルのダウンロード時に、入力されたURLへ通信します。\n");
        sb.append("- APIサーバーを有効にした場合、端末のポートでリクエストを受け付けます。\n");
        sb.append("  入力内容は端末内で処理され、アプリが外部サーバーへ送信することはありません。\n\n");
        sb.append("3. 利用目的\n");
        \n\n");"上記データはアプリの動作、生成機能、表示のために使(sb.appendecho
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
