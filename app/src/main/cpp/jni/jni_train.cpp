// jni_train.cpp — 端末内 LoRA/部分ファインチューン JNI
//
// 内蔵 llama.cpp の学習コア（ggml-opt + llama_opt_*）を JNI から叩く。
// examples/training/finetune.cpp を土台に、以下を足している:
//   1) param_filter を差し替え、指定サブストリング（例 attn_q/attn_v）を名前に含む
//      テンソルだけを学習対象にする（＝凍結ベース＋一部層のみ更新の軽量アダプテーション）。
//   2) エポック/バッチごとに Java 側 TrainListener.onProgress(...) を呼び、進捗を細粒度で返す。
//   3) データセットはテキストファイルのパスで受け取り、ネイティブ側で読む（大データはパス渡し）。
//
// trainRun() は「呼び出しスレッド上で同期実行」する。Java 側は必ずワーカースレッドから呼ぶこと。
// コールバックは同一スレッドで発火するので JNIEnv をそのまま使える（AttachCurrentThread 不要）。
//
// 前提: 学習は 350M 級でもメモリを食う（AdamW モーメント等）。呼び出し前に推論モデルを free()
// して二重ロードを避けること。KV は OUT_PROD の都合で F32 に固定する（finetune.cpp と同様）。

#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <cstring>

#include <android/log.h>
#define TLOG_TAG "LLAMA_TRAIN"
#define TLOGI(...) __android_log_print(ANDROID_LOG_INFO,  TLOG_TAG, __VA_ARGS__)
#define TLOGE(...) __android_log_print(ANDROID_LOG_ERROR, TLOG_TAG, __VA_ARGS__)

#include "llama.h"
#include "ggml.h"
#include "ggml-opt.h"
#include "gguf.h"
#include "common.h"

// ---- 進捗コールバック用のスレッドローカル状態 ----
// ggml_opt_epoch_callback は userdata を取らないので、ループ前にここへ束ねる。
// 学習は g_train_mutex 相当（Java 側で単一実行を保証）で1本ずつ流す前提。
namespace {

struct TrainCtx {
    JNIEnv *   env       = nullptr;
    jobject    listener  = nullptr;   // TrainListener（ローカル参照でよい: 同一スレッド同期）
    jmethodID  onProgress = nullptr;  // (IIIIDLjava/lang/String;)V
    int        epoch      = 0;
    int        epochs     = 0;
    std::vector<std::string> targets; // 学習対象テンソル名のサブストリング
};

TrainCtx * g_tc = nullptr;

void emit(int epoch, int epochs, int ibatch, int ibatchMax, double loss, const char * phase) {
    if (!g_tc || !g_tc->env || !g_tc->listener || !g_tc->onProgress) return;
    JNIEnv * env = g_tc->env;
    jstring jphase = env->NewStringUTF(phase ? phase : "");
    env->CallVoidMethod(g_tc->listener, g_tc->onProgress,
                        (jint) epoch, (jint) epochs, (jint) ibatch, (jint) ibatchMax,
                        (jdouble) loss, jphase);
    if (jphase) env->DeleteLocalRef(jphase);
    if (env->ExceptionCheck()) {
        // Java 側で例外が出ても学習ループは壊さない。ログして握りつぶす。
        env->ExceptionClear();
    }
}

// 学習対象テンソルだけ true を返す param_filter。userdata は std::vector<std::string>*。
bool train_param_filter(const struct ggml_tensor * t, void * ud) {
    const auto * subs = reinterpret_cast<const std::vector<std::string> *>(ud);
    if (!subs || subs->empty()) return true; // 空指定＝フルFT
    const char * name = ggml_get_name(t);
    if (!name) return false;
    for (const auto & s : *subs) {
        if (!s.empty() && std::strstr(name, s.c_str()) != nullptr) return true;
    }
    return false;
}

// 学習中の train 側評価コールバック（バッチ進捗）。
void cb_train(bool train, ggml_opt_context_t opt_ctx, ggml_opt_dataset_t dataset,
              ggml_opt_result_t result, int64_t ibatch, int64_t ibatch_max, int64_t t_start_us) {
    (void) train; (void) opt_ctx; (void) dataset; (void) t_start_us;
    double loss = 0.0, unc = 0.0;
    if (result) ggml_opt_result_loss(result, &loss, &unc);
    if (g_tc) {
        emit(g_tc->epoch + 1, g_tc->epochs, (int) ibatch, (int) ibatch_max, loss, "train");
    }
}

// "q,v,attn_q,ffn_gate..." のような指定を tensor 名サブストリングへ正規化する。
// 短縮名（q/k/v/o/gate/up/down）は llama 系テンソル名へマップ。既に "attn_"/"ffn_" を
// 含む語はそのまま採用。
std::vector<std::string> parse_targets(const std::string & spec) {
    std::vector<std::string> out;
    std::stringstream ss(spec);
    std::string tok;
    while (std::getline(ss, tok, ',')) {
        // trim
        size_t a = tok.find_first_not_of(" \t");
        size_t b = tok.find_last_not_of(" \t");
        if (a == std::string::npos) continue;
        tok = tok.substr(a, b - a + 1);
        if (tok.empty()) continue;
        if (tok.find("attn_") != std::string::npos || tok.find("ffn_") != std::string::npos) {
            out.push_back(tok);
        } else if (tok == "q") out.push_back("attn_q");
        else if (tok == "k") out.push_back("attn_k");
        else if (tok == "v") out.push_back("attn_v");
        else if (tok == "o") out.push_back("attn_output");
        else if (tok == "gate") out.push_back("ffn_gate");
        else if (tok == "up")   out.push_back("ffn_up");
        else if (tok == "down") out.push_back("ffn_down");
        else out.push_back(tok); // 未知はそのまま部分一致
    }
    return out;
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return std::string();
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// GGUF の general.file_type を読む。読めなければ -1。
// llama.cpp の finetune は FP32 前提（README 明記）。量子化ベースは ggml-opt が
// 最適化対象テンソルを扱えず GGML_ASSERT→abort する。事前に弾いて綺麗なエラーにする。
int read_file_type(const std::string & path) {
    struct gguf_init_params gp{ /*no_alloc=*/ true, /*ctx=*/ nullptr };
    struct gguf_context * gc = gguf_init_from_file(path.c_str(), gp);
    if (!gc) return -1;
    int ftype = -1;
    int64_t kid = gguf_find_key(gc, "general.file_type");
    if (kid >= 0) {
        enum gguf_type t = gguf_get_kv_type(gc, kid);
        if (t == GGUF_TYPE_UINT32)      ftype = (int) gguf_get_val_u32(gc, kid);
        else if (t == GGUF_TYPE_INT32)  ftype = (int) gguf_get_val_i32(gc, kid);
    }
    gguf_free(gc);
    return ftype;
}

std::string read_file(const std::string & path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return std::string();
    std::ostringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_trainRun(
        JNIEnv * env, jobject /*thiz*/,
        jstring jModelPath, jstring jDatasetPath, jstring jOutPath,
        jstring jTargets, jfloat lr, jint jEpochs, jint jNCtx, jint jNThreads,
        jint jOptimizer, jobject jListener) {

    const std::string modelPath   = jstr(env, jModelPath);
    const std::string datasetPath = jstr(env, jDatasetPath);
    const std::string outPath     = jstr(env, jOutPath);
    const std::string targetSpec  = jstr(env, jTargets);
    const int epochs   = jEpochs > 0 ? (int) jEpochs : 1;

    auto fail = [&](const std::string & msg) -> jstring {
        TLOGE("trainRun failed: %s", msg.c_str());
        return env->NewStringUTF(("ERROR: " + msg).c_str());
    };

    const std::string corpus = read_file(datasetPath);
    if (corpus.empty()) return fail("dataset file empty/unreadable: " + datasetPath);

    // FP32/F16 以外（量子化）のベースは学習不可。abort する前に明確に弾く。
    // file_type: 0=ALL_F32, 1=MOSTLY_F16。それ以外は量子化とみなす。
    int ftype = read_file_type(modelPath);
    if (ftype != 0 && ftype != 1) {
        return fail("base model must be F32 (or F16). This GGUF file_type=" + std::to_string(ftype)
                    + " is quantized; on-device finetune (ggml-opt) requires an FP32 base. "
                    + "Register/convert an F32 GGUF of the model and retry.");
    }

    // ---- common_params の組み立て（finetune.cpp 準拠） ----
    common_params params;
    params.escape = false;
    params.model.path   = modelPath;
    params.out_file     = outPath.empty() ? std::string("trained.gguf") : outPath;
    params.prompt       = corpus;
    params.n_ctx        = jNCtx > 0 ? (int) jNCtx : 512;
    params.cpuparams.n_threads = jNThreads > 0 ? (int) jNThreads : 4;

    // finetune.cpp と同じ強制設定: 書込み可能な重み + OUT_PROD 用に KV=F32
    params.load_mode    = LLAMA_LOAD_MODE_NONE;
    params.cache_type_k = GGML_TYPE_F32;
    params.cache_type_v = GGML_TYPE_F32;

    // 学習率/エポック/オプティマイザ
    params.lr.lr0    = (float) lr > 0 ? (float) lr : 1e-4f;
    params.lr.epochs = (unsigned) epochs;
    params.optimizer = (jOptimizer == 1) ? GGML_OPT_OPTIMIZER_TYPE_SGD
                                         : GGML_OPT_OPTIMIZER_TYPE_ADAMW;

    llama_backend_init();
    llama_numa_init(params.numa);

    auto llama_init = common_init_from_params(params);
    llama_model   * model = llama_init->model();
    llama_context * ctx   = llama_init->context();
    if (model == nullptr || ctx == nullptr) {
        return fail("model load failed: " + modelPath);
    }

    // ---- 進捗コンテキスト ----
    TrainCtx tc;
    tc.env      = env;
    tc.listener = jListener;
    tc.epochs   = epochs;
    tc.targets  = parse_targets(targetSpec);
    if (jListener) {
        jclass cls = env->GetObjectClass(jListener);
        tc.onProgress = env->GetMethodID(cls, "onProgress", "(IIIIDLjava/lang/String;)V");
    }
    g_tc = &tc;

    emit(0, epochs, 0, 0, 0.0, tc.targets.empty() ? "prep(full-ft)" : "prep");

    // ---- データセット ----
    std::vector<llama_token> tokens = common_tokenize(ctx, params.prompt, true);
    if (tokens.size() < 4) { g_tc = nullptr; return fail("tokenized corpus too small"); }
    ggml_opt_dataset_t dataset = common_opt_dataset_init(ctx, tokens, llama_n_ctx(ctx) / 2);

    // ---- オプティマイザ初期化（param_filter で対象層を限定） ----
    struct llama_opt_params lopt_params {
        /*n_ctx_train     =*/ 0,
        /*param_filter    =*/ train_param_filter,
        /*param_filter_ud =*/ &tc.targets,
        /*get_opt_pars    =*/ common_opt_lr_pars,
        /*get_opt_pars_ud =*/ &params.lr,
        /*optimizer_type  =*/ params.optimizer,
    };
    llama_opt_init(ctx, model, lopt_params);

    const int64_t ndata       = ggml_opt_dataset_ndata(dataset);
    const int64_t idata_split = ndata * (1.0f - params.val_split);

    ggml_opt_result_t result_train = ggml_opt_result_init();
    ggml_opt_result_t result_eval  = ggml_opt_result_init();

    double last_loss = 0.0;
    for (params.lr.epoch = 0; params.lr.epoch < params.lr.epochs; ++params.lr.epoch) {
        tc.epoch = (int) params.lr.epoch;
        llama_opt_epoch(ctx, dataset, result_train, result_eval, idata_split, cb_train, nullptr);

        double loss = 0.0, unc = 0.0;
        ggml_opt_result_loss(result_train, &loss, &unc);
        last_loss = loss;
        emit(tc.epoch + 1, epochs, 0, 0, loss, "epoch_done");
        TLOGI("epoch %d/%d loss=%.4f", tc.epoch + 1, epochs, loss);

        ggml_opt_result_reset(result_train);
        ggml_opt_result_reset(result_eval);
    }

    ggml_opt_result_free(result_train);
    ggml_opt_result_free(result_eval);
    ggml_opt_dataset_free(dataset);

    emit(epochs, epochs, 0, 0, last_loss, "saving");
    llama_model_save_to_file(model, params.out_file.c_str());

    g_tc = nullptr;
    // 学習用に確保したモデル/コンテキストは llama_init のデストラクタで解放される。
    // backend は推論側が使い続けるので free しない。

    std::ostringstream ok;
    ok << "OK loss=" << last_loss << " out=" << params.out_file;
    return env->NewStringUTF(ok.str().c_str());
}
