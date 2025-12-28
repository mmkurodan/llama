package com.example.ollama;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    // URL からファイルを内部ストレージへダウンロードする
    private String downloadFileToInternalStorage(String urlStr, String fileName) {
        File outFile = new File(getFilesDir(), fileName);

        // すでに存在していれば再ダウンロードしない
        if (outFile.exists()) {
            return outFile.getAbsolutePath();
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null;
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(outFile)) {

                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }

            return outFile.getAbsolutePath();

        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LlamaNative llama = new LlamaNative();

        // 実際の TinyLlama GGUF の URL
        String url =
                "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v0.3-GGUF/resolve/main/"
                        + "tinyllama-1.1b-chat-v0.3.Q4_K_M.gguf";

        // 内部ストレージに保存するファイル名
        String fileName = "tinyllama.gguf";

        // ダウンロード実行
        String modelPath = downloadFileToInternalStorage(url, fileName);

        String initResult;
        if (modelPath == null) {
            initResult = "Download failed";
        } else {
            initResult = llama.init(modelPath);
        }

        // 動作確認用に generate も呼ぶ（JNI 側はダミーでもOK）
        String genResult = llama.generate("Hello from Java");

        TextView tv = new TextView(this);
        tv.setText(initResult + "\n" + genResult);
        tv.setTextSize(16);

        setContentView(tv);
    }
}
