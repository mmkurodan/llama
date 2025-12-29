package com.example.ollama;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.File;

public class MainActivity extends Activity {

    private TextView tv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tv = new TextView(this);
        tv.setText("Starting...");
        tv.setTextSize(16);
        setContentView(tv);

        new Thread(() -> {
            String url =
                "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v0.3-GGUF/resolve/main/"
                + "tinyllama-1.1b-chat-v0.3.Q4_K_M.gguf";

            File dir = getFilesDir();
            File modelFile = new File(dir, "tinyllama.gguf");
            String modelPath = modelFile.getAbsolutePath();

            // Use a subclass so we can receive onDownloadProgress callbacks
            LlamaNative llama = new LlamaNative() {
                @Override
                public void onDownloadProgress(final int percent) {
                    runOnUiThread(() -> tv.setText("Download progress: " + percent + "%"));
                }
            };

            // Call init("") to register JavaVM in native code (native init stores JavaVM).
            // The return value will be ignored here because model isn't available yet.
            llama.init("");

            runOnUiThread(() -> tv.setText("Starting download..."));

            String dlResult = llama.download(url, modelPath);

            String result;
            if (!"ok".equals(dlResult)) {
                result = "Download failed: " + dlResult;
            } else {
                // After download, initialize with actual model path to load the model
                result = llama.init(modelPath);

                // 推論テスト
                String gen = llama.generate("Hello!");
                result = result + "\n\nGenerated:\n" + gen;
            }

            String finalResult = result;
            runOnUiThread(() -> tv.setText(finalResult));

        }).start();
    }
}
