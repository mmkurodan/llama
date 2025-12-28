#include <jni.h>
#include <string>
#include <vector>
#include <mutex>

#include "llama.h"          // ${CMAKE_SOURCE_DIR}/llama/include が通っている前提

// ログ用
#include <android/log.h>
#define LOG_TAG "LLAMA_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// グローバル（簡易版・シングルトン運用）
static std::mutex g_mutex;
static llama_model *g_model  = nullptr;
static llama_context *g_ctx  = nullptr;
static llama_sampling_context *g_sampling = nullptr;

// 設定
static int g_n_ctx      = 512;
static int g_n_threads  = 2;
static int g_n_batch    = 16;
static float g_temp     = 0.7f;
static float g_top_p    = 0.9f;
static int g_top_k      = 40;

// ユーティリティ: JNI の jstring → std::string
static std::string jstring_to_std(JNIEnv *env, jstring jstr) {
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// ユーティリティ: エラー時に Java 側へ例外投げる
static void throw_java_exception(JNIEnv *env, const char *msg) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass) {
        env->ThrowNew(exClass, msg);
    }
}

// モデル・コンテキスト開放
static void llama_jni_free() {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_sampling) {
        llama_sampling_free(g_sampling);
        g_sampling = nullptr;
    }

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    llama_backend_free();
}

// ---------------------- JNI: init(modelPath, n_ctx) ----------------------
// Java 側: native void init(String modelPath, int nCtx);
extern "C"
JNIEXPORT void JNICALL
Java_com_example_ollama_LlamaBridge_init(
        JNIEnv *env, jobject /*thiz*/,
        jstring jModelPath,
        jint jNCtx
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    // すでに初期化済みなら一度解放
    llama_jni_free();

    std::string model_path = jstring_to_std(env, jModelPath);
    g_n_ctx = (int) jNCtx;

    LOGI("llama_jni: init backend");
    llama_backend_init();

    // モデルパラメータ
    llama_model_params mparams = llama_model_default_params();
    // 必要なら mparams.n_gpu_layers など設定

    LOGI("llama_jni: loading model from: %s", model_path.c_str());
    g_model = llama_load_model_from_file(model_path.c_str(), mparams);
    if (!g_model) {
        LOGE("failed to load model");
        throw_java_exception(env, "failed to load model");
        return;
    }

    // コンテキストパラメータ
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx    = g_n_ctx;
    cparams.n_threads = g_n_threads;
    cparams.n_batch   = g_n_batch;

    LOGI("llama_jni: creating context (n_ctx=%d)", g_n_ctx);
    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("failed to create context");
        throw_java_exception(env, "failed to create context");
        llama_jni_free();
        return;
    }

    // サンプリングコンテキスト
    llama_sampling_params sparams = llama_sampling_default_params();
    sparams.temp  = g_temp;
    sparams.top_p = g_top_p;
    sparams.top_k = g_top_k;

    g_sampling = llama_sampling_init(sparams);

    LOGI("llama_jni: init done");
}

// ---------------------- JNI: release() ----------------------
// Java 側: native void release();
extern "C"
JNIEXPORT void JNICALL
Java_com_example_ollama_LlamaBridge_release(
        JNIEnv *env, jobject /*thiz*/
) {
    (void) env;
    LOGI("llama_jni: release");
    llama_jni_free();
}

// ---------------------- JNI: generate(prompt, maxTokens) ----------------------
// Java 側: native String generate(String prompt, int maxTokens);
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_ollama_LlamaBridge_generate(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPrompt,
        jint jMaxTokens
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_ctx || !g_model || !g_sampling) {
        throw_java_exception(env, "llama is not initialized");
        return nullptr;
    }

    std::string prompt = jstring_to_std(env, jPrompt);
    int max_tokens = (int) jMaxTokens;

    LOGI("llama_jni: generate start, prompt=\"%s\"", prompt.c_str());

    // ---- 1. プロンプトをトークナイズ ----
    std::vector<llama_token> tokens;
    tokens.resize(g_n_ctx); // 最大 n_ctx まで

    int n_tokens = llama_tokenize(
            g_ctx,
            prompt.c_str(),
            (int) prompt.size(),
            tokens.data(),
            (int) tokens.size(),
            true  // add_special (BOS 付与)
    );

    if (n_tokens < 0) {
        LOGE("tokenize failed");
        throw_java_exception(env, "tokenize failed");
        return nullptr;
    }

    tokens.resize(n_tokens);

    // ---- 2. batch 準備 ----
    llama_batch batch = llama_batch_init(/*n_tokens_alloc*/ g_n_batch,
                                         /*embd*/ 0,
                                         /*n_seq_max*/ 1);

    int n_past = 0;
    std::string output;
    output.reserve(max_tokens * 4);

    // ---- 3. プロンプトをモデルに流す（初期 eval）----
    for (int i = 0; i < (int) tokens.size(); i++) {
        batch.n_tokens = 1;
        batch.token[0] = tokens[i];
        batch.pos[0]   = n_past;
        batch.seq_id[0] = 0;
        batch.logits[0] = false;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode (prompt) failed");
            throw_java_exception(env, "llama_decode (prompt) failed");
            llama_batch_free(batch);
            return nullptr;
        }

        n_past++;
    }

    // ---- 4. 生成ループ ----
    for (int i = 0; i < max_tokens; i++) {
        // サンプリング
        llama_token id = llama_sampling_sample(g_ctx, g_sampling);

        if (id == llama_token_eos(g_ctx)) {
            LOGI("llama_jni: EOS");
            break;
        }

        // トークンを文字列に変換
        // 最新の llama.cpp では llama_token_to_piece が推奨
        std::string piece;
        piece.resize(8); // 初期サイズ（必要に応じて拡張される）

        // 安全策で一旦バッファ確保 → 実際の長さを取得
        int n_chars = llama_token_to_piece(g_ctx, id, nullptr, 0);
        if (n_chars > 0) {
            piece.resize(n_chars);
            llama_token_to_piece(g_ctx, id, piece.data(), n_chars);
            output += piece;
        }

        // 次のトークンをモデルに入力
        batch.n_tokens = 1;
        batch.token[0] = id;
        batch.pos[0]   = n_past;
        batch.seq_id[0] = 0;
        batch.logits[0] = false;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode (gen) failed");
            throw_java_exception(env, "llama_decode (gen) failed");
            llama_batch_free(batch);
            return nullptr;
        }

        n_past++;
    }

    llama_batch_free(batch);

    LOGI("llama_jni: generate done, len=%zu", output.size());

    return env->NewStringUTF(output.c_str());
}
