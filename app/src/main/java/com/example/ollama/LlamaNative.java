package com.example.ollama;

import android.util.Log;

public class LlamaNative {

    private static final String TAG = "LlamaNative";
    
    public interface DownloadProgressListener {
        void onProgress(int percent);
    }
    
    private volatile DownloadProgressListener downloadProgressListener;

    static {
        System.loadLibrary("llama_jni");
    }

    public native String download(String url, String path);
    public native String init(String modelPath);
    public native String generate(String prompt);
    public native void free();

    // 新しく追加したネイティブ: JNI 側のログファイルパスを設定する
    public native void setLogPath(String path);
    
    // Set sampling parameters
    public native void setParameters(
        int penaltyLastN, float penaltyRepeat, float penaltyFreq, float penaltyPresent,
        int mirostat, float mirostatTau, float mirostatEta,
        float minP, float typicalP,
        float dynatempRange, float dynatempExponent,
        float xtcProbability, float xtcThreshold,
        float topNSigma,
        float dryMultiplier, float dryBase, int dryAllowedLength, int dryPenaltyLastN,
        String drySequenceBreakers
    );

    public void setDownloadProgressListener(DownloadProgressListener listener) {
        this.downloadProgressListener = listener;
    }
    
    // Called from native code to deliver download progress (0-100)
    public void onDownloadProgress(int percent) {
        Log.d(TAG, "Download progress: " + percent + "%");
        DownloadProgressListener listener = downloadProgressListener;
        if (listener != null) {
            listener.onProgress(percent);
        }
    }
}
