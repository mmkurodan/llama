package com.example.ollama;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LlamaNative llama = new LlamaNative();

        // ① init のテスト（今はダミー）
        String initResult = llama.init("models/dummy.gguf");

        // ② generate のテスト（今はダミー）
        String genResult = llama.generate("Hello from Java");

        TextView tv = new TextView(this);
        tv.setText(initResult + "\n" + genResult);
        tv.setTextSize(18);

        setContentView(tv);
    }
}
