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
        String manualTitle = localizedText("操作マニュアル", "User Manual");
        String manualText = buildManualText();

        String privacyTitle = localizedText("プライバシーポリシー", "Privacy Policy");
        String privacyText = buildPrivacyText();

        return new Document[] {
            new Document(manualTitle, manualText),
            new Document(privacyTitle, privacyText)
        };
    }

    private String buildManualText() {
        return AppLanguageManager.isJapanese(this)
                ? buildJapaneseManualText()
                : buildEnglishManualText();
    }

    private String buildJapaneseManualText() {
        StringBuilder sb = new StringBuilder();
        sb.append("操作マニュアル\n\n");
        sb.append("【重要】モデルのダウンロードには数GB単位の通信が必要になる場合があります。モバイルデータ通信を使用すると高額な通信料が発生する可能性があるため、可能な限りWi-Fi環境でのダウンロードを強く推奨します。\n\n");
        sb.append("1. アプリ概要\n");
        sb.append("アプリ名: LLM AI Server with llama.cpp\n");
        sb.append("Built with Llama (llama.cpp)\n");
        sb.append("本アプリは端末上でLLMを実行し、プロンプトに対する回答を生成します。\n");
        sb.append("計算バックエンドは CPU / GPU(Adreno OpenCL) を利用でき、対応端末では設定でGPUへオフロードできます。未対応の場合は自動的にCPUへフォールバックします。\n");
        sb.append("必要に応じてOllama互換APIサーバーと llama.cpp 標準WebUIを同じポートで起動でき、メイン画面のWeb UI URLからブラウザで開けます。\n\n");
        sb.append("2. 初期設定（推奨手順）\n");
        sb.append("0) アプリ起動時にAPI/WebUI有効化ポップアップが表示された場合は、必要に応じて有効化するか、「次回以降は表示しない」をチェックすると次回から表示されません。\n");
        sb.append("1) 初回起動時のQuick Startで「次回以降は表示しない」をチェックすると、次回起動以降は表示されません。\n");
        sb.append("2) メイン画面で「設定」を開きます。\n");
        sb.append("   ※推論中（Busy）は設定ボタンが無効化されます。処理完了後に自動で再有効化されます。\n");
        sb.append("3) モデルURLを入力するか、ローカル端末から .gguf ファイルを取り込みます。Web上のURLを使う場合は、ダウンロード後にそのままロードするか確認されます。マルチモーダル対応モデルで利用可能な Projector が見つかる場合、Projector 欄は Available と表示され、通常は手動選択不要です。\n");
        sb.append("   ※ローカル取り込みのファイル選択は Downloads フォルダを既定で開き、必要に応じて端末内の他の場所へ移動して選択できます。HTTP/HTTPS URLを利用でき、HTTPSでは通常のSSL/TLS証明書検証を行います。ローカル取り込み時は設定欄にファイル名のみが保存されます。\n");
        sb.append("4) パラメータを調整する場合は各項目を編集し「設定を保存」で保存します。\n");
        sb.append("5) 「保存して閉じる」を押すと設定が保存され、モデルに即座に適用されます。\n\n");
        sb.append("3. メイン画面\n");
        sb.append("- 最上部ステータス: 直近に実行された推論（ダイレクト/API問わず）のモデル名・生成速度(tok/s)・デバイス温度・実行中の計算バックエンド(CPU/GPU)を表示し、自動更新します。\n");
        sb.append("- 操作ボタン（縦並び）: 「API/WebUI開始・停止」「リセット」「設定」。リセットは進行中の生成を停止し、現在のプロファイルでモデルを再初期化します。設定は推論中（Busy）は自動的に無効化され、完了後に再有効化されます。\n");
        sb.append("- Web UI URL: タップで既定ブラウザを開き、長押しでURLをコピーします（権限不要）。Wi-Fi接続時はLAN IP、未接続時はlocalhostを、現在のポートで表示します。\n");
        sb.append("- 処理状況/ログ（見出しタップで開閉・既定で開）: タイムスタンプ付きの処理ログを表示します。\n");
        sb.append("  ・ログ表示 / 状況に戻る: 処理状況欄に保存済みログファイルの最新行を表示し、もう一度押すと処理状況の表示へ戻ります。\n");
        sb.append("  ・更新: ログ表示中の内容を最新化。消去: ログファイルを空に。保存: 表示中のログ全文を保存します。\n");
        sb.append("- ダイレクト実行（見出しタップで開閉・既定で閉）: その場で試すためのセクションです。\n");
        sb.append("  ・プロファイル選択: 保存済み設定を選んで切り替えます（次回プロンプトで適用）。\n");
        sb.append("  ・プロンプト入力／送信: 入力して送信すると生成します。モデル未ロード時は自動でロードします。\n");
        sb.append("  ・モデル出力／速度・温度: 応答と、その生成の速度・温度を表示します。\n");
        sb.append("- テキスト選択コピー: モデル出力・処理状況/ログの各エリアは長押しでテキストを選択してコピーできます（個別のコピーボタンは廃止）。各エリアは画面縦の最大50%まで広がり、超過分はエリア内でスクロールします。\n\n");
        sb.append("4. 設定画面の操作\n");
        sb.append("- 設定画面: 各項目は種別ごとのセクションに分かれており、見出しをタップすると開閉できます。MCP設定セクションは初期状態で閉じています。\n");
        sb.append("- モデル管理: ダウンロード済みのモデルと mmproj を一覧表示します。ファイルをタップすると「このモデルを使用 / 名前を変更 / 削除」を選べます（mmproj は使用以外）。名前を変更すると、そのファイルを参照する保存済みプロファイルも自動で更新され、不要な再ダウンロードを防ぎます。このセクションの直下に「Hugging FaceでGGUFを検索」ボタンがあります。\n");
        sb.append("- 設定管理: 設定の保存/削除/読み込みを行います。\n");
        sb.append("- モデル選択: モデルURLでの読み込みに加え、ローカル端末から .gguf ファイルを取り込めます。ファイル選択は Downloads フォルダを既定で開き、必要に応じて端末内の他の場所へ移動できます。取り込んだファイルはアプリ内モデル保存先へコピーされ、設定欄にはファイル名のみを表示します。HTTP/HTTPS URL利用時はHTTPSで通常のSSL/TLS証明書検証を行います。Webダウンロード時はロードするか確認され、mmproj / Projector とみられるGGUFではダウンロードのみが推奨されます。マルチモーダルモデルで別途 mmproj のダウンロードが必要な場合は、続行前に確認します（スキップ時はテキスト専用で読み込み）。誤った mmproj を割り当てた場合は、読み込み時に自動で無効化してテキスト専用で読み込み、メッセージを表示します。mmproj の選択/解除ボタンは「モデルを読み込む」の下にあります。\n");
        sb.append("- モデルパラメータ: 生成パラメータを設定します（GPUオフロード層を含む）。\n");
        sb.append("- 出力設定: ストリーミング出力の有効/無効を切り替えます。\n");
        sb.append("- プロンプトテンプレート: システムプロンプト、Think有効/無効（chat-template-kwargs.enable_thinking）、カスタムテンプレートを設定できます。カスタム未設定時はGGUFのchat_templateメタデータを優先し、必要に応じてモデルファミリーから自動選択されます。Bonsai系の既定テンプレートにも対応しています。\n");
        sb.append("- Llama APIサーバー: サーバーポートを指定します。ローカルURLは http://localhost:ポート番号 で表示され、Wi-Fi接続中はLAN URLも表示してタップでコピーできます。起動時ポップアップまたはメイン画面で有効化すると、APIとWebUIが同じポートで利用可能になります。\n");
        sb.append("- MCP設定: モデル設定とは別のアプリ共通設定としてMCPコンフィグJSONとFunction Definitions JSONを保存できます。各スイッチをオフにした場合は WebUI でのみ利用され、メイン画面のプロンプト入力や /api/chat、/api/generate、/v1/chat/completions では未設定として扱われます。オンにした場合のみ、それぞれ共有MCP設定と共有 function calling 定義として利用されます。\n");
        sb.append("- 表示言語: 日本語/English の表示言語を切り替えます。初回は端末設定から自動選択され、次回以降は選択が保存されます。\n");
        sb.append("- ログ設定: ログレベルを選択します（初回起動時の既定値: INFO）。\n");
        sb.append("- ライセンス表示: ライセンス文面（サードパーティ通知を含む）を表示します。\n");
        sb.append("- ドキュメント: 操作マニュアルとプライバシーポリシーを確認できます。\n");
        sb.append("- 保存して閉じる: 現在の入力値を保存し、モデルに即時適用してメイン画面に戻ります。\n");
        sb.append("- 閉じる: 何も保存せずメイン画面に戻ります。\n\n");
        sb.append("5. モデルパラメータの詳細説明\n\n");
        sb.append("【基本パラメータ】\n");
        sb.append("- コンテキストサイズ (n_ctx): モデルが一度に処理できるトークン数です。大きいほど長い文脈を扱えますが、メモリ消費が増加します。\n");
        sb.append("- スレッド数 (n_threads): 推論に使用するCPUスレッド数です。端末のコア数に合わせて調整してください。\n");
        sb.append("- バッチサイズ (n_batch): 一度に処理するトークン数です。大きくすると高速ですがメモリを多く使用します。\n");
        sb.append("- GPUオフロード層: GPUへオフロードする層数です。0は無効、1〜39は指定層数、-1は利用可能な全層を対象にします。\n");
        sb.append("- 温度 (temp): 出力のランダム性を制御します。0に近いほど決定的、高いほど多様な出力になります。\n");
        sb.append("- Top-p: 累積確率がこの値に達するまでのトークンから選択します（nucleus sampling）。\n");
        sb.append("- Top-k: 確率上位k個のトークンから選択します。\n\n");
        sb.append("【ペナルティパラメータ】\n");
        sb.append("- Penalty Last N: ペナルティを適用する直近のトークン数です。\n");
        sb.append("- Penalty Repeat: 繰り返しトークンへのペナルティ倍率です。1.0で無効、高いほど繰り返しを抑制します。\n");
        sb.append("- Penalty Frequency: 出現頻度に応じたペナルティです。\n");
        sb.append("- Penalty Presence: 一度出現したトークンへのペナルティです。\n\n");
        sb.append("【Mirostatパラメータ】\n");
        sb.append("- Mirostat: 0=無効、1=Mirostat v1、2=Mirostat v2。出力の一貫性を自動調整するサンプリング手法です。\n");
        sb.append("- Mirostat Tau: 目標のサプライズ値（perplexity）です。低いとより一貫性のある出力になります。\n");
        sb.append("- Mirostat Eta: 学習率です。Mirostatのフィードバック速度を制御します。\n\n");
        sb.append("【追加サンプリングパラメータ】\n");
        sb.append("- Min-p: 最低確率閾値です。これ以下の確率のトークンを除外します。\n");
        sb.append("- Typical P: 典型的なサンプリングのパラメータです。\n");
        sb.append("- Dynamic Temperature Range: 動的温度調整の範囲です。0で無効になります。\n");
        sb.append("- Dynamic Temperature Exponent: 動的温度の指数です。\n");
        sb.append("- XTC Probability: XTCサンプリングの確率です。\n");
        sb.append("- XTC Threshold: XTCサンプリングの閾値です。\n");
        sb.append("- Top-N-Sigma: シグマベースのサンプリングです。-1で無効になります。\n\n");
        sb.append("【DRYパラメータ】\n");
        sb.append("- DRY Multiplier: Don't Repeat Yourself 繰り返し抑制の強度です。0で無効になります。\n");
        sb.append("- DRY Base: DRYペナルティの基数です。\n");
        sb.append("- DRY Allowed Length: 繰り返しを許容する最小長です。\n");
        sb.append("- DRY Penalty Last N: DRYペナルティを適用するトークン数です。-1で全体に適用します。\n");
        sb.append("- DRY Sequence Breakers: DRYの区切り文字です。\n\n");
        sb.append("【出力設定】\n");
        sb.append("- ストリーミングを有効化: 有効にするとトークンが生成されるたびに出力が更新されます。無効にすると生成完了後に一括表示されます。\n\n");
        sb.append("【Think設定】\n");
        sb.append("- Thinkを有効化 (chat-template-kwargs.enable_thinking): chat-template-kwargs の enable_thinking を切り替えます。無効時はモデルの思考出力を抑制する形式でプロンプトを生成します。\n\n");
        sb.append("6. プロンプトテンプレートの自動選択\n");
        sb.append("カスタムテンプレートが設定されていない場合、まずGGUFのchat_templateメタデータからファミリーを推定し、利用できない場合はモデルファイル名から自動選択します。\n");
        sb.append("対応ファミリー: Gemma, Qwen, Mistral, LLaMA, Phi, Bonsai, Zephyr, Hermes。該当なしの場合はChatMLをフォールバックとして使用します。\n");
        sb.append("Gemma系では system / user / model の順序を維持します。\n");
        sb.append("選択結果は処理状況/ログおよびINFOレベルログに記録されます。\n");
        sb.append("/api/chatの会話履歴はモデルファミリー別のマルチターンテンプレートに従って構成されます。\n\n");
        sb.append("7. 停止シーケンス\n");
        sb.append("生成時に一般的なチャットテンプレートの区切り文字を検出すると自動的に生成を停止します。\n\n");
        sb.append("8. API/WebUIサーバー（任意）\n");
        sb.append("- アプリ起動時にローカルAPI/WebUIサーバーを有効化するかどうか確認するポップアップが表示されます。\n");
        sb.append("- 起動すると端末内で /api/chat, /api/generate, /api/tags, /v1/chat/completions, /v1/models, /props, /slots と WebUI の静的ファイルを提供します。\n");
        sb.append("- WebUIは同じポートの http://<端末IP>:<ポート>/ で利用できます。\n");
        sb.append("- アプリ設定で保存したMCPコンフィグJSONは共通設定として /props 経由でWebUIにも渡され、WebUIのローカルMCP設定と合わせて利用されます。\n");
        sb.append("- MCP の外部利用スイッチをオンにすると、共有MCP設定は WebUI に加えて、メイン画面のプロンプト入力、/api/chat、/api/generate、/v1/chat/completions の内部ツール実行にも利用されます。オフでは WebUI のみで利用されます。\n");
        sb.append("- Function Calling の外部利用スイッチをオンにすると、Function Definitions JSON は共通の function calling 定義としてメイン画面のプロンプト入力、/api/chat、/api/generate、/v1/chat/completions に自動で追加されます。オフでは WebUI のみで利用されます。\n");
        sb.append("- 同時生成は1件のみです。ビジー時は最大10件までキューに入り、最大60秒待機します。60秒超過またはキュー満杯時は503を返します。\n");
        sb.append("- Android 13以上では通知権限が必要な場合があります。\n\n");
        sb.append("9. 🔍 アプリ内でGGUFを検索してダウンロードする（推奨）\n\n");
        sb.append("最も簡単な方法です。ブラウザを使わず、アプリ内だけでモデルを検索して取得できます。\n");
        sb.append("1) 設定画面を開き、「モデル管理」セクション直下の「Hugging FaceでGGUFを検索」ボタンを押します。\n");
        sb.append("2) モデル名やキーワード（部分一致）を入力して検索すると、上位約30件のリポジトリが表示されます。\n");
        sb.append("3) リポジトリを選ぶと、その中の *.gguf ファイル一覧が表示されるので、目的のファイルを選びます。\n");
        sb.append("4) 「ダウンロードのみ」または「ダウンロードしてロード」を選びます。mmproj / projector とみられるGGUFでは「ダウンロードのみ」が推奨されます。\n");
        sb.append("5) マルチモーダル対応モデルで別途 mmproj のダウンロードが必要な場合は、続行前に確認します。スキップした場合はテキスト専用で読み込みます。\n");
        sb.append("- Gemma-4 などで同じリポジトリに対応 mmproj / projector が含まれている場合は、自動的に対応する projector のみを設定し、Projector 欄は Available と表示されます（テキスト専用モデルに無関係な projector は自動適用しません）。\n");
        sb.append("- ダウンロード中は端末がスリープしても中断されにくいよう配慮していますが、安定した Wi-Fi 環境での実行を強く推奨します。\n\n");
        sb.append("10. 🧭 GGUFモデルの探し方・選び方\n\n");
        sb.append("【10-1. GGUF対応モデルを探す】\n");
        sb.append("- Hugging Face のモデル検索で GGUF タグを使う\n");
        sb.append("  → https://huggingface.co/models?library=gguf\n");
        sb.append("- GGUFモデルはリポジトリ名に -GGUF が付いていることが多い\n");
        sb.append("  例：TheBloke/Mixtral-8x7B-Instruct-v0.1-GGUF\n");
        sb.append("- モデルページの Files タブに *.gguf が並んでいる\n\n");
        sb.append("【10-2. 量子化バリエーションの選び方（概要）】\n");
        sb.append("- Q2_K：軽量・低メモリ\n");
        sb.append("- Q4_K_M：バランス型（最初に試すならこれが無難）\n");
        sb.append("- Q8_0：重いが精度高め\n\n");
        sb.append("11. 📥 ブラウザから直接ダウンロードする手順\n\n");
        sb.append("アプリ内検索で見つからない場合や、URLを直接指定したい場合に使います。\n");
        sb.append("【11-1. 手動ダウンロード】\n");
        sb.append("1) モデルページを開く\n");
        sb.append("   例：https://huggingface.co/unsloth/Mistral-Small-3.1-24B-Instruct-2503-GGUF\n");
        sb.append("2) Files タブを開く\n");
        sb.append("3) 目的の *.gguf ファイルをクリック\n");
        sb.append("4) 右上の Download ボタンを押してダウンロード\n\n");
        sb.append("【11-2. GGUFファイルの直接URLを取得してアプリに貼り付ける】\n");
        sb.append("1) Files タブで目的の *.gguf をクリックしてファイルページを開く\n");
        sb.append("2) Download ボタンを右クリック → 「リンクのコピー」\n");
        sb.append("3) 取得した直接URLを、設定画面のモデルURL欄に貼り付けてダウンロードします\n\n");
        sb.append("補足\n");
        sb.append("大きなモデルのロードは、アドレス空間の確保失敗またはユーザ操作により中断される場合があります。その場合は次回起動時に一時ファイルを削除して通知を表示します。必要に応じて、より小さいモデルを試すか、設定から再度モデルを読み込んでください。\n");
        return sb.toString();
    }

    private String buildEnglishManualText() {
        StringBuilder sb = new StringBuilder();
        sb.append("User Manual\n\n");
        sb.append("IMPORTANT: Downloading models may require gigabytes of data. Using mobile/cellular data may incur significant charges; downloading over Wi-Fi is strongly recommended.\n\n");
        sb.append("1. Overview\n");
        sb.append("App name: LLM AI Server with llama.cpp\n");
        sb.append("Built with Llama (llama.cpp).\n");
        sb.append("This app runs an LLM on your device and generates responses to prompts.\n");
        sb.append("Compute backends: CPU / GPU (Adreno OpenCL). On supported devices you can offload to the GPU from Settings; it falls back to CPU automatically when unsupported.\n");
        sb.append("An Ollama-compatible API server and the standard llama.cpp WebUI can be started together on the same port, reachable from the Web UI URL on the main screen.\n\n");
        sb.append("2. Recommended Setup\n");
        sb.append("0) If the API/WebUI enablement popup appears at launch, enable it when needed or check \"Don't show next time\" to skip it on future launches.\n");
        sb.append("1) On first launch, if you check \"Don't show next time\" in Quick Start, it will not be shown on subsequent launches.\n");
        sb.append("2) Open \"Settings\" from the main screen.\n");
        sb.append("   * During inference (Busy), the Settings button is disabled and is re-enabled automatically when processing completes.\n");
        sb.append("3) Enter the model URL or import a .gguf file from the local device. When a web URL is used, the app asks whether to load it immediately after the download. If an available projector is found for a multimodal-capable model, the Projector field shows Available and manual mmproj selection is usually unnecessary.\n");
        sb.append("   * The local import picker opens in Downloads by default, and you can navigate elsewhere on the device as needed. Reachable HTTP/HTTPS URLs can be used. HTTPS uses normal SSL/TLS certificate verification. Imported local files are saved as filenames only in Settings.\n");
        sb.append("4) Edit parameters if needed and tap \"Save Config\".\n");
        sb.append("5) Tap \"SAVE & CLOSE\" to save settings and apply them to the model immediately.\n\n");
        sb.append("3. Main Screen\n");
        sb.append("- Top status: Shows the most recent run (direct OR API) — model name, generation speed (tok/s), device temperature, and the active compute backend (CPU/GPU). It auto-updates.\n");
        sb.append("- Action buttons (stacked): \"Start/Stop API/WebUI\", \"Reset\", \"Settings\". Reset stops the active generation and reinitializes the current profile. Settings is disabled while inference is busy and re-enabled when it completes.\n");
        sb.append("- Web UI URL: Tap to open it in the default browser, long-press to copy the URL (no permission required). Shows the LAN IP on Wi-Fi, otherwise localhost, with the current port.\n");
        sb.append("- Processing Status/Logs (collapsible header, expanded by default): Shows timestamped processing logs.\n");
        sb.append("  - View Log / Show Status: Show the latest lines of the saved log file in the processing area; tap again to return to the live status.\n");
        sb.append("  - Update: refresh the shown log. Clear: empty the log file. Download: save the shown log.\n");
        sb.append("- Direct Run (collapsible header, collapsed by default): A section for quick on-device tries.\n");
        sb.append("  - Profile selector: switch between saved configurations (applied on the next prompt).\n");
        sb.append("  - Prompt / Send: type a prompt and send to generate; the model is loaded automatically if needed.\n");
        sb.append("  - Model Output / speed & temp: shows the response and that run's speed and temperature.\n");
        sb.append("- Selectable copy: the Model Output and Processing/Log areas are selectable (long-press to copy); the separate Copy buttons were removed. Each area grows up to 50% of the screen height and scrolls beyond that.\n\n");
        sb.append("4. Settings Screen\n");
        sb.append("- Settings screen: Controls are grouped into collapsible sections. Tap a section title to expand or collapse it. The MCP Settings section is collapsed by default.\n");
        sb.append("- Model Maintenance: Lists downloaded models and mmproj files. Tap a file to choose \"Use as model / Rename / Delete\" (mmproj files offer everything except Use). Renaming also updates any saved profiles that reference that file to the new local filename, preventing unnecessary re-downloads. The \"Search GGUF on Hugging Face\" button is directly below this section.\n");
        sb.append("- Configuration Management: Save/delete/load configurations.\n");
        sb.append("- Model Selection: Load models from a URL or import .gguf files from the local device. The picker opens in Downloads by default, and you can navigate elsewhere on the device as needed. Imported files are copied into the app model storage directory and only the filename is shown in Settings. Reachable HTTP/HTTPS URLs can be used, and HTTPS uses normal SSL/TLS certificate verification. For web downloads, the app asks whether to load the file after the download, and download-only is recommended for GGUFs that look like mmproj / projector files. If a multimodal model needs a separate mmproj download, the app asks before continuing (skipping loads text-only). If an incompatible mmproj is assigned, it is disabled automatically at load time and the model loads text-only with a message. The mmproj selection/clear buttons are placed below \"Load Model\".\n");
        sb.append("- Model Parameters: Set generation parameters (including GPU Offload Layers).\n");
        sb.append("- Output Settings: Toggle streaming output on/off.\n");
        sb.append("- Prompt Template: Set System Prompt, Think on/off (chat-template-kwargs.enable_thinking), and custom chat template. When no custom template is set, the app first uses GGUF chat_template metadata and otherwise auto-selects by model family. A Bonsai fallback template is included.\n");
        sb.append("- Llama API Server: Set the server port. The Local URL is shown as http://localhost:<port>, and while connected to Wi-Fi the LAN URL is also shown and can be tapped to copy. Enabling it from the startup popup or the main screen makes both the API and WebUI available on that port.\n");
        sb.append("- MCP Settings: Save MCP config JSON and Function Definitions JSON as app-wide shared settings separate from model profiles. When the new switches are off, they are available only in the WebUI and are treated as absent everywhere else. When enabled, they are also used as shared MCP and function-calling settings for the main prompt input, /api/chat, /api/generate, and /v1/chat/completions.\n");
        sb.append("- Display Language: Switch UI language between Japanese and English. On first launch it follows your device locale, and your choice is saved for later launches.\n");
        sb.append("- Log Settings: Select log level (default on first launch: INFO).\n");
        sb.append("- Show License: Display license text (including third-party notices).\n");
        sb.append("- Documents: View the user manual and the privacy policy.\n");
        sb.append("- SAVE & CLOSE: Save current settings and apply them to the model immediately.\n");
        sb.append("- CLOSE: Return to the main screen without saving any changes.\n\n");
        sb.append("5. Model Parameter Details\n\n");
        sb.append("[Basic Parameters]\n");
        sb.append("- Context Size (n_ctx): Number of tokens the model can process at once. Larger values handle longer contexts but use more memory.\n");
        sb.append("- Threads (n_threads): Number of CPU threads for inference. Adjust based on your device's core count.\n");
        sb.append("- Batch Size (n_batch): Number of tokens processed at once. Larger is faster but uses more memory.\n");
        sb.append("- GPU Offload Layers: Number of layers to offload to GPU. 0 disables offload, 1-39 offloads that many layers, and -1 targets all available layers.\n");
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
        sb.append("6. Prompt Template Auto-Selection\n");
        sb.append("When no custom template is set, the app first estimates the family from GGUF chat_template metadata and otherwise auto-selects from the filename.\n");
        sb.append("Supported families: Gemma, Qwen, Mistral, LLaMA, Phi, Bonsai, Zephyr, Hermes. Falls back to ChatML if unrecognized.\n");
        sb.append("For the Gemma family, the app keeps the system / user / model order.\n");
        sb.append("Selection results are logged to Processing Status/Logs and INFO-level logs.\n");
        sb.append("Conversation history from /api/chat is formatted using model-family-specific multi-turn templates.\n\n");
        sb.append("7. Stop Sequences\n");
        sb.append("Generation automatically stops when common chat template delimiters are detected in the output.\n\n");
        sb.append("8. API/WebUI Server (Optional)\n");
        sb.append("- On app launch, a popup asks whether to enable the local API/WebUI server, and you can check \"Don't show next time\" to skip it on future launches.\n");
        sb.append("- Provides /api/chat, /api/generate, /api/tags, /v1/chat/completions, /v1/models, /props, /slots, and the bundled WebUI on device.\n");
        sb.append("- The WebUI is available at http://<device-ip>:<port>/ on the same port.\n");
        sb.append("- MCP config JSON saved in the app settings is exposed to the WebUI through /props as a shared setting and is used together with the WebUI's local MCP settings.\n");
        sb.append("- When MCP outside WebUI is enabled, shared MCP settings are also used by the main prompt input, /api/chat, /api/generate, and /v1/chat/completions for internal tool execution. When disabled, they remain WebUI-only.\n");
        sb.append("- When Function Calling outside WebUI is enabled, Function Definitions JSON is automatically added as shared function-calling definitions for the main prompt input, /api/chat, /api/generate, and /v1/chat/completions. When disabled, it remains WebUI-only.\n");
        sb.append("- Only one generation runs at a time. When busy, requests are queued (up to 10) and wait up to 60 seconds; queue overflow or timeout returns 503.\n");
        sb.append("- Android 13+ may require notification permission.\n\n");
        sb.append("9. 🔍 Search and Download GGUF in the App (Recommended)\n\n");
        sb.append("The easiest way — find and fetch models entirely inside the app, without a browser.\n");
        sb.append("1) Open the Settings screen and tap the \"Search GGUF on Hugging Face\" button directly below the Model Maintenance section.\n");
        sb.append("2) Enter a model name or keyword (partial match) and search; roughly the top 30 repositories are shown.\n");
        sb.append("3) Pick a repository to list its *.gguf files, then choose the file you want.\n");
        sb.append("4) Choose \"Download only\" or \"Download and load\". For GGUFs that look like an mmproj / projector, \"Download only\" is recommended.\n");
        sb.append("5) If a multimodal model also needs a separate mmproj download, the app asks before continuing; if you skip it, the model loads text-only.\n");
        sb.append("- When a repository also contains a matching mmproj / projector for models such as Gemma-4, the app auto-configures only a compatible projector and shows Projector as Available (unrelated projector files are not auto-applied to text-only models).\n");
        sb.append("- Downloads are kept awake even if the device screen turns off, but a stable Wi-Fi connection is strongly recommended.\n\n");
        sb.append("10. 🧭 Finding and Choosing GGUF Models\n\n");
        sb.append("[10-1. Locating GGUF-compatible models]\n");
        sb.append("- Use the GGUF tag on Hugging Face model search\n");
        sb.append("  → https://huggingface.co/models?library=gguf\n");
        sb.append("- GGUF models often have -GGUF in the repository name\n");
        sb.append("  Example: TheBloke/Mixtral-8x7B-Instruct-v0.1-GGUF\n");
        sb.append("- The model page's Files tab lists available *.gguf files\n\n");
        sb.append("[10-2. Choosing a quantization variant (overview)]\n");
        sb.append("- Q2_K: Lightweight, low memory footprint\n");
        sb.append("- Q4_K_M: Balanced (recommended to start with)\n");
        sb.append("- Q8_0: Larger, higher quality\n\n");
        sb.append("11. 📥 Downloading from a Browser\n\n");
        sb.append("Use this when the in-app search does not find what you need, or to paste a direct URL.\n");
        sb.append("[11-1. Manual download]\n");
        sb.append("1) Open the model page\n");
        sb.append("   Example: https://huggingface.co/unsloth/Mistral-Small-3.1-24B-Instruct-2503-GGUF\n");
        sb.append("2) Click the Files tab\n");
        sb.append("3) Click the desired *.gguf file\n");
        sb.append("4) Press the Download button in the top right\n\n");
        sb.append("[11-2. Getting a direct URL and pasting it into the app]\n");
        sb.append("1) In the Files tab, click the *.gguf file to open its page\n");
        sb.append("2) Right-click the Download button and select \"Copy link\"\n");
        sb.append("3) Paste the direct URL into the model URL field on the Settings screen to download it\n\n");
        sb.append("Tips\n");
        sb.append("Loading a very large model may stop because address-space reservation fails or because the process was interrupted by user action. In that case the app clears temporary load files on the next launch and shows a notice. If needed, try a smaller model or load the model again from Settings.\n");
        return sb.toString();
    }

    private String buildPrivacyText() {
        return AppLanguageManager.isJapanese(this)
                ? buildJapanesePrivacyText()
                : buildEnglishPrivacyText();
    }

    private String buildJapanesePrivacyText() {
        StringBuilder sb = new StringBuilder();
        sb.append("プライバシーポリシー\n\n");
        sb.append("1. 収集する情報\n");
        sb.append("本アプリは、個人情報を外部サーバーへ送信しません。以下の情報を端末内に保存します。\n\n");
        sb.append("- 設定情報（モデルURL、各種パラメータ、APIポート、ログレベル、MCPコンフィグJSON、Function Definitions JSON）\n");
        sb.append("- 構成ファイル（configs/*.json）\n");
        sb.append("- ダウンロードまたは取り込んだモデルファイル\n");
        sb.append("- ログファイル（ollama.log。MAX DEBUG では診断情報やクラッシュ内容もこのログに追記されます。補助ファイルとして last_crash.txt・native_crash.txt・diagnostics/process_diagnostics.log・diagnostics/last_state.txt・best effort の diagnostics/recent_logcat.txt を保持する場合があります）\n\n");
        sb.append("また、アプリの生成機能、API連携、WebUIを利用する際、ユーザーが入力したテキスト（会話内容）は、端末内または同一ローカルネットワーク内で動作するローカル API に送信されます。MCPサーバーを設定して利用しない限り、このデータが外部サーバーへ送信されることはなく、保存も行いません。\n\n");
        sb.append("---\n\n");
        sb.append("2. 通信\n");
        sb.append("- モデルのダウンロード時に、ユーザーが入力した URL へ通信します。\n");
        sb.append("- ローカル .gguf ファイルの取り込み時は、端末内の選択ファイルをアプリ保存領域へコピーします。外部サーバー通信は行いません。\n");
        sb.append("- HTTPS URL を使用する場合は、通常のSSL/TLS証明書検証を行います。\n");
        sb.append("- API/WebUI サーバーを有効にした場合、端末内またはローカルネットワーク内のポート（0.0.0.0）でAPIとWebUIのリクエストを受け付けます。\n");
        sb.append("- ユーザーの入力内容（会話全文）はローカル API に送信されますが、インターネットを経由して外部サーバーへ送信されることはありません。\n");
        sb.append("- MCPサーバーを設定してメイン画面、API連携、またはWebUIから利用した場合、会話内容やツール入力の一部が設定先のMCPサーバーへ送信されることがあります。送信先や取扱いは各MCPサーバーの設定・運用に依存します。\n");
        sb.append("- ローカルネットワーク内からアクセス可能ですが、外部ネットワークからのアクセスは意図されておらず、アプリは外部公開を行いません。\n");
        sb.append("- ローカル通信は暗号化されていない場合がありますが、通信は端末内または同一ネットワーク内に限定されます。\n\n");
        sb.append("3. 利用目的\n");
        sb.append("上記データは、アプリの動作、生成機能、表示のために使用します。ユーザーの入力内容は生成処理のためにのみ利用され、外部送信・第三者提供・保存は行いません。\n\n");
        sb.append("4. ログ\n");
        sb.append("ログにはアプリの動作状況や API リクエスト情報が記録される場合があります。ログは端末内にのみ保存され、外部へ送信されません。\n\n");
        sb.append("5. 保存期間\n");
        sb.append("設定・モデル・ログは、ユーザーが削除するかアプリのデータを消去するまで保持されます。ログはメイン画面の「ログ消去」で削除できます。\n\n");
        sb.append("6. お問い合わせ\n");
        sb.append("ご不明点がある場合は、以下のメールアドレスまでお問い合わせください。\n");
        sb.append("micklab2026@gmail.com\n\n");
        sb.append("© Mick Lab — 生成AIアプリケーション研究\n");
        return sb.toString();
    }

    private String buildEnglishPrivacyText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Privacy Policy\n\n");
        sb.append("1. Information Collected\n");
        sb.append("This application does not transmit any personal information to external servers. The following data is stored locally on the device:\n\n");
        sb.append("- Configuration data (model URL, parameters, API port, log level, MCP config JSON, Function Definitions JSON)\n");
        sb.append("- Configuration files (configs/*.json)\n");
        sb.append("- Downloaded or imported model files\n");
        sb.append("- Log files (ollama.log. In MAX DEBUG, diagnostics and crash contents are also appended to this log. Companion files such as last_crash.txt, native_crash.txt, diagnostics/process_diagnostics.log, diagnostics/last_state.txt, and a best-effort diagnostics/recent_logcat.txt may also be retained)\n\n");
        sb.append("When using the generation features, API integrations, or the WebUI, the text entered by the user (conversation content) is sent to a local API running on the device or within the same local network. Unless you configure and use MCP servers, this data is not transmitted to external servers and is not stored.\n\n");
        sb.append("2. Communication\n");
        sb.append("- When downloading models, the application communicates with the URL entered by the user.\n");
        sb.append("- When importing a local .gguf file, the app copies the selected file into the app storage area and does not communicate with any external server.\n");
        sb.append("- For HTTPS URLs, standard SSL/TLS certificate verification is used.\n");
        sb.append("- When the API/WebUI server is enabled, it listens on a port (0.0.0.0) accessible within the device or the local network.\n");
        sb.append("- User input (full conversation text) is sent to the local API, but it is never transmitted over the internet or to any external server.\n");
        sb.append("- If you configure and use MCP servers from the main prompt input, API integrations, or the WebUI, parts of conversation content or tool inputs may be sent to those configured MCP servers. The destination and handling depend on each MCP server's own configuration and operation.\n");
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
