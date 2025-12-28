package com.example.ollama;

public class LlamaNative {

    static {
        System.loadLibrary("llama_jni");
    }

    // ① モデル初期化用（今はダミーで OK）
    public native String init(String modelPath);

    // ② 推論用（プロンプトを渡してレスポンスをもらう）
    public native String generate(String prompt);
}
