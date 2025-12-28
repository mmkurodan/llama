package com.example.ollama;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.File;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
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

            LlamaNative llama = new LlamaNative();

            String dlResult = llama.download(url, modelPath);

            String result;
            if (!"ok".equals(dlResult)) {
                result = "Download failed: " + dlResult;
            } else {
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
