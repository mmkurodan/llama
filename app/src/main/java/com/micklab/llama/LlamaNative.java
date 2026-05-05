package com.micklab.llama;

import android.util.Log;

public class LlamaNative {

    private static final String TAG = "LlamaNative";
    private static final String[] HEXAGON_HTP_LIBRARIES = new String[] {
            "ggml-htp-v68",
            "ggml-htp-v69",
            "ggml-htp-v73",
            "ggml-htp-v75",
            "ggml-htp-v79",
            "ggml-htp-v81"
    };
    
    public interface DownloadProgressListener {
        void onProgress(int percent);
    }
    
    private volatile DownloadProgressListener downloadProgressListener;

    // Token listener for streaming
    public interface TokenListener {
        void onToken(String token);
        void onComplete();
        void onError(String error);
    }

    static {
        loadOptionalLibrary("ggml-base", "ggml base runtime");
        loadOptionalLibrary("ggml-cpu", "ggml cpu runtime");

        boolean hexagonLoaded = loadOptionalLibrary("ggml-hexagon", "Hexagon backend");
        int loadedHtpLibraries = 0;
        for (String htpLibrary : HEXAGON_HTP_LIBRARIES) {
            if (loadOptionalLibrary(htpLibrary, "Hexagon HTP runtime")) {
                loadedHtpLibraries++;
            }
        }

        if (!hexagonLoaded) {
            Log.w(TAG, "Hexagon backend library was not loaded. NPU requests will fall back to CPU until libggml-hexagon.so is bundled in the APK.");
        } else if (loadedHtpLibraries == 0) {
            Log.w(TAG, "Hexagon backend loaded without any libggml-htp-vXX.so runtime libraries. NPU requests will fall back to CPU.");
        } else {
            Log.i(TAG, "Loaded " + loadedHtpLibraries + " Hexagon HTP runtime libraries");
        }

        loadJniLibrary();
    }

    private static boolean loadOptionalLibrary(String libraryName, String label) {
        try {
            System.loadLibrary(libraryName);
            Log.i(TAG, "Loaded " + label + ": " + libraryName);
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.d(TAG, "Optional native library unavailable: " + libraryName, e);
            return false;
        }
    }

    private static void loadJniLibrary() {
        try {
            System.loadLibrary("llama");
            Log.i(TAG, "Loaded JNI library: llama");
        } catch (UnsatisfiedLinkError primaryError) {
            Log.w(TAG, "Primary JNI library name libllama.so was unavailable, trying legacy name", primaryError);
            try {
                System.loadLibrary("llama_jni");
                Log.w(TAG, "Loaded legacy JNI library name: llama_jni");
            } catch (UnsatisfiedLinkError legacyError) {
                Log.e(TAG, "Failed to load JNI library. Expected libllama.so in the APK.", legacyError);
                throw primaryError;
            }
        }
    }

    public native String download(String url, String path);
    public native void setDownloadCaBundlePath(String path);
    public native String init(String modelPath);
    public native String initWithMmproj(String modelPath, String mmprojPath);
    public native void setLoadParameters(
            int nCtx,
            int nThreads,
            int nBatch,
            float temp,
            float topP,
            int topK,
            int nGpuLayers,
            int backend,
            boolean useHexagon
    );
    public native String configureBackend(
            int backend,
            int npuDeviceCount,
            String nativeLibraryDir,
            boolean useHexagon
    );
    public native String generate(String prompt);
    public native String generateWithMedia(String prompt, byte[][] mediaFiles);
    public native String generateOpenAiChatCompletion(
        String messagesJson,
        String toolsJson,
        String chatTemplateOverride,
        String toolChoiceJson,
        boolean parallelToolCalls,
        boolean enableThinking,
        byte[][] mediaFiles
    );
    public native void cancelGeneration();
    public native void free();

    // Token listener registration (native will keep a global ref)
    public native void setTokenListener(TokenListener listener);

    // 新しく追加したネイティブ: JNI 側のログファイルパスを設定する
    public native void setLogPath(String path);
    public native void setLogLevel(int level);
    
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
    
    // Get chat template from loaded GGUF model metadata
    public native String getChatTemplate();
    public native boolean supportsVision();
    public native boolean supportsAudio();

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
