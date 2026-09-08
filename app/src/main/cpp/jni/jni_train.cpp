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
#include <cstdio>
#include <exception>

#include <android/log.h>
#define TLOG_TAG "LLAMA_TRAIN"
#define TLOGI(...) __android_log_print(ANDROID_LOG_INFO,  TLOG_TAG, __VA_ARGS__)
#define TLOGE(...) __android_log_print(ANDROID_LOG_ERROR, TLOG_TAG, __VA_ARGS__)

#include "llama.h"
#include "ggml.h"
#include "ggml-opt.h"
#include "ggml-backend.h"
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

// クラッシュ耐性のある段階トレース。ollama.log へ即 flush して書く（SIGSEGV でも直前段階が残る）。
// /api/diagnostics?file=ollama で読める。emit(HTTP)はクラッシュで失われるため、これが唯一の頼り。
void trace(const char * stage) {
    static const char * kPath = "/storage/emulated/0/Android/data/com.micklab.llama/files/ollama.log";
    FILE * f = std::fopen(kPath, "a");
    if (f) { std::fprintf(f, "[TRAIN-TRACE] %s\n", stage); std::fflush(f); std::fclose(f); }
    TLOGI("TRACE %s", stage);
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

    // ベース精度は「情報として出す」だけでブロックしない（Q4/BF16/F32 の可否を実測で見極める）。
    // file_type: 0=ALL_F32, 1=F16, 32=BF16, その他=量子化。base_ftype として emit する。
    const int base_ftype = read_file_type(modelPath);

    // ---- common_params の組み立て（finetune.cpp 準拠） ----
    common_params params;
    params.escape = false;
    params.model.path   = modelPath;
    params.out_file     = outPath.empty() ? std::string("trained.gguf") : outPath;
    params.prompt       = corpus;
    params.n_ctx        = jNCtx > 0 ? (int) jNCtx : 512;
    // finetune.cpp は -c -b -ub を同値にする。batch/ubatch を n_ctx に揃えないと
    // コンテキスト生成時に n_ubatch>n_ctx 等で GGML_ASSERT→abort する。
    params.n_batch      = params.n_ctx;
    params.n_ubatch     = params.n_ctx;
    params.cpuparams.n_threads = jNThreads > 0 ? (int) jNThreads : 4;

    // finetune.cpp と同じ強制設定: 書込み可能な重み + OUT_PROD 用に KV=F32
    params.load_mode    = LLAMA_LOAD_MODE_NONE;
    params.cache_type_k = GGML_TYPE_F32;
    params.cache_type_v = GGML_TYPE_F32;
    // ★学習は CPU 強制。この APK は Adreno(OpenCL) バックエンド込みビルドで、ggml-opt の
    //   逆伝播/OUT_PROD は GPU バックエンド未対応 → SIGSEGV になる（finetune README:
    //   "For CPU training, compile without additional backends"）。GPUオフロードを止める。
    params.n_gpu_layers = 0;
    // 学習に不要な warmup（前向きデコード）を無効化。学習用コンテキストでの warmup が
    // クラッシュ源の候補のため切る。
    params.warmup = false;

    // 学習率/エポック/オプティマイザ
    params.lr.lr0    = (float) lr > 0 ? (float) lr : 1e-4f;
    params.lr.epochs = (unsigned) epochs;
    params.optimizer = (jOptimizer == 1) ? GGML_OPT_OPTIMIZER_TYPE_SGD
                                         : GGML_OPT_OPTIMIZER_TYPE_ADAMW;

    // ---- 進捗コンテキスト（モデルロード前に用意し、段階を可視化する） ----
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

    trace("enter trainRun");
    { char b[64]; std::snprintf(b, sizeof b, "base_ftype=%d n_ctx=%d", base_ftype, params.n_ctx); trace(b); }
    emit(0, epochs, base_ftype, 0, 0.0, "base_ftype");  // batch フィールドに file_type を載せる
    emit(0, epochs, 0, 0, 0.0, "loading_model");        // ← ここまで出れば model load 前まで到達

    trace("before backend_init");
    llama_backend_init();
    llama_numa_init(params.numa);

    // ★A: 学習は CPU デバイスのみを使う（Adreno/OpenCL を完全に回避）。ngl=0 だけでは
    //   バックエンドが登録済みで巻き込まれるため、model/context のデバイスを CPU に固定する。
    {
        ggml_backend_dev_t cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        char b[128];
        std::snprintf(b, sizeof b, "backend dev_count=%zu cpu_dev=%p (CPU-only for training)",
                      ggml_backend_dev_count(), (void*) cpu_dev);
        trace(b);
        if (cpu_dev) params.devices = { cpu_dev };
    }

    // ---- モデルロードとコンテキスト生成を分割し、どの段で落ちるか特定する ----
    trace("before model load (model_only=true)");
    auto llama_init = common_init_from_params(params, /*model_only=*/true);
    llama_model * model = llama_init->model();
    if (model == nullptr) {
        g_tc = nullptr;
        return fail("model load failed: " + modelPath);
    }
    trace("model load OK; before llama_init_from_model (context)");

    llama_context_params cparams = common_context_params_to_llama(params);
    llama_context * ctx = llama_init_from_model(model, cparams);
    trace(ctx ? "context create OK" : "context create returned NULL");
    if (ctx == nullptr) {
        g_tc = nullptr;
        return fail("context create failed: " + modelPath);
    }

    emit(0, epochs, 0, 0, 0.0, tc.targets.empty() ? "prep(full-ft)" : "prep");

    // ---- データセット ----
    trace("before tokenize/dataset");
    std::vector<llama_token> tokens = common_tokenize(ctx, params.prompt, true);
    if ((int) tokens.size() < 4) { g_tc = nullptr; return fail("tokenized corpus too small (" + std::to_string(tokens.size()) + " tokens)"); }
    emit(0, epochs, (int) tokens.size(), 0, 0.0, "dataset");
    ggml_opt_dataset_t dataset = common_opt_dataset_init(ctx, tokens, llama_n_ctx(ctx) / 2);
    if (ggml_opt_dataset_ndata(dataset) < 1) {
        ggml_opt_dataset_free(dataset); g_tc = nullptr;
        return fail("dataset has 0 datapoints (corpus shorter than n_ctx/2=" + std::to_string(llama_n_ctx(ctx) / 2) + " tokens; use more data or smaller n_ctx)");
    }

    emit(0, epochs, 0, 0, 0.0, "opt_init");
    trace("before llama_opt_init");
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
    trace("after llama_opt_init (entering epoch loop)");

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
    // context は自前生成なので明示 free（model より先に）。model は llama_init が解放する。
    llama_free(ctx);
    // backend は推論側が使い続けるので free しない。

    std::ostringstream ok;
    ok << "OK loss=" << last_loss << " out=" << params.out_file;
    return env->NewStringUTF(ok.str().c_str());
}

// ---- GGUF 精度変換（例: Q8_0 → F32 デクオンタイズ）----
// 外部ツール無しで端末内 F32 GGUF を作るための最小ラッパ。llama_model_quantize は
// ftype=ALL_F32(0) を指定でき、量子化テンソルは allow_requantize=true で F32 へ逆量子化される。
extern "C" JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_convertModel(
        JNIEnv * env, jobject /*thiz*/, jstring jIn, jstring jOut, jint jFtype) {
    const std::string in  = jstr(env, jIn);
    const std::string out = jstr(env, jOut);
    if (in.empty() || out.empty()) return env->NewStringUTF("ERROR: in/out path required");

    llama_backend_init();
    llama_model_quantize_params qp = llama_model_quantize_default_params();
    qp.ftype                  = (enum llama_ftype) jFtype; // 0 = LLAMA_FTYPE_ALL_F32
    qp.allow_requantize       = true;   // 量子化→F32 の逆量子化を許可
    qp.quantize_output_tensor = true;
    qp.only_copy              = false;
    qp.nthread                = 0;      // 0 = 自動

    uint32_t rc;
    try {
        rc = llama_model_quantize(in.c_str(), out.c_str(), &qp);
    } catch (const std::exception & e) {
        return env->NewStringUTF((std::string("ERROR: ") + e.what()).c_str());
    } catch (...) {
        return env->NewStringUTF("ERROR: unknown exception in llama_model_quantize");
    }
    if (rc != 0) return env->NewStringUTF(("ERROR: llama_model_quantize rc=" + std::to_string(rc)).c_str());
    return env->NewStringUTF(("OK out=" + out).c_str());
}
