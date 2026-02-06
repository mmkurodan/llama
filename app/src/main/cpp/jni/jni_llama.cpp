#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <fstream>
#include <chrono>
#include <ctime>
#include <sstream>
#include <iomanip>
#include <cerrno>
#include <cstring>
#include <cctype>

#include <android/log.h>
#define LOG_TAG "LLAMA_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-backend-impl.h"   // ★ これが必要
#include "ggml-cpu.h"
#include <curl/curl.h>

// ---------------- グローバル ----------------
static std::mutex g_mutex;
static llama_model   *g_model = nullptr;
static llama_context *g_ctx   = nullptr;
static JavaVM *g_jvm = nullptr;
// Token listener global ref and method IDs
static jobject g_token_listener = nullptr;
static jmethodID g_token_onToken = nullptr;
static jmethodID g_token_onComplete = nullptr;
static jmethodID g_token_onError = nullptr;
// Keep track of currently loaded model path to avoid redundant inits
static std::string g_current_model_path;

// ログ用
static std::mutex g_log_mutex;
static std::string g_log_path;
static std::ofstream g_log_ofs;
static std::atomic<int> g_log_level(GGML_LOG_LEVEL_INFO);

// 設定
static int   g_n_ctx      = 2048;
static int   g_n_threads  = 2;
static int   g_n_batch    = 16;
static float g_temp       = 0.7f;
static float g_top_p      = 0.9f;
static int   g_top_k      = 40;

// DRY sequence breakers default - MUST match Java ConfigurationManager.Configuration.DEFAULT_DRY_SEQUENCE_BREAKERS
static const char* DEFAULT_DRY_SEQUENCE_BREAKERS = "\\n,:,\",*";

// Penalty parameters
static int   g_penalty_last_n    = 64;
static float g_penalty_repeat    = 1.0f;
static float g_penalty_freq      = 0.0f;
static float g_penalty_present   = 0.0f;

// Mirostat parameters
static int   g_mirostat          = 0;
static float g_mirostat_tau      = 5.0f;
static float g_mirostat_eta      = 0.1f;

// Additional sampler parameters
static float g_min_p             = 0.05f;
static float g_typical_p         = 1.0f;
static float g_dynatemp_range    = 0.0f;
static float g_dynatemp_exponent = 1.0f;
static float g_xtc_probability   = 0.0f;
static float g_xtc_threshold     = 0.1f;
static float g_top_n_sigma       = -1.0f;

// DRY parameters
static float g_dry_multiplier       = 0.0f;
static float g_dry_base             = 1.75f;
static int   g_dry_allowed_length   = 2;
static int   g_dry_penalty_last_n   = -1;
static std::string g_dry_sequence_breakers = DEFAULT_DRY_SEQUENCE_BREAKERS;

// ---------------- ログユーティリティ ----------------
static std::string current_time_str() {
    using namespace std::chrono;
    auto now = system_clock::now();
    std::time_t t = system_clock::to_time_t(now);
    struct tm tm_buf;
    localtime_r(&t, &tm_buf);
    std::ostringstream ss;
    ss << std::put_time(&tm_buf, "%Y-%m-%d %H:%M:%S");
    return ss.str();
}

static ggml_log_level normalize_log_level(ggml_log_level level) {
    return level == GGML_LOG_LEVEL_NONE ? GGML_LOG_LEVEL_INFO : level;
}

static bool should_log(ggml_log_level level) {
    const int threshold = g_log_level.load();
    const ggml_log_level effective = normalize_log_level(level);
    return effective >= threshold;
}

static bool should_log_debug() {
    return should_log(GGML_LOG_LEVEL_DEBUG);
}

// Return the last index that ends on a valid UTF-8 boundary (trims incomplete trailing bytes).
static size_t validate_utf8(const std::string& text) {
    size_t len = text.size();
    if (len == 0) return 0;

    for (size_t i = 1; i <= 4 && i <= len; ++i) {
        unsigned char c = static_cast<unsigned char>(text[len - i]);
        if ((c & 0xE0) == 0xC0) {
            if (i < 2) return len - i;
            break;
        } else if ((c & 0xF0) == 0xE0) {
            if (i < 3) return len - i;
            break;
        } else if ((c & 0xF8) == 0xF0) {
            if (i < 4) return len - i;
            break;
        } else if ((c & 0x80) == 0x00) {
            break;
        }
    }

    return len;
}

static int32_t detokenize_with_resize(
        const llama_vocab * vocab,
        const std::vector<llama_token> & tokens,
        std::string & out_text) {
    if (tokens.empty()) {
        out_text.clear();
        return 0;
    }

    size_t target = tokens.size();
    if (out_text.capacity() > target) {
        target = out_text.capacity();
    }
    out_text.resize(target);

    int32_t n_chars = llama_detokenize(
            vocab,
            tokens.data(),
            (int32_t)tokens.size(),
            &out_text[0],
            (int32_t)out_text.size(),
            true,
            false
    );

    if (n_chars < 0) {
        out_text.resize(-n_chars);
        n_chars = llama_detokenize(
                vocab,
                tokens.data(),
                (int32_t)tokens.size(),
                &out_text[0],
                (int32_t)out_text.size(),
                true,
                false
        );
    }

    if (n_chars < 0) {
        return n_chars;
    }

    out_text.resize(n_chars);
    return n_chars;
}

static void log_to_file(const std::string& msg, ggml_log_level level = GGML_LOG_LEVEL_INFO) {
    if (!should_log(level)) return;
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_log_path.empty()) return;
    if (!g_log_ofs.is_open()) {
        g_log_ofs.open(g_log_path, std::ios::app | std::ios::binary);
    }
    if (!g_log_ofs) return;
    g_log_ofs << current_time_str() << " [JNI] " << msg << std::endl;
    g_log_ofs.flush();
}

// ---------------- llama.cpp ログコールバック ----------------
// 0.17.1 は llama_log_level ではなく ggml_log_level を使う
static void llama_log_callback(enum ggml_log_level level, const char * text, void * user_data) {
    const ggml_log_level effective = normalize_log_level(level);
    if (!should_log(effective)) return;
    std::string msg = text ? text : "";
    // Skip empty messages and continuation messages
    if (msg.empty() || msg == "\n") {
        return;
    }
    // Only log INFO, WARN, ERROR levels
    if (effective == GGML_LOG_LEVEL_ERROR) {
        LOGE("[llama.cpp] %s", msg.c_str());
    } else if (effective == GGML_LOG_LEVEL_WARN) {
        LOGI("[llama.cpp WARN] %s", msg.c_str());
    } else if (effective == GGML_LOG_LEVEL_DEBUG) {
        LOGI("[llama.cpp DEBUG] %s", msg.c_str());
    } else {
        LOGI("[llama.cpp] %s", msg.c_str());
    }
    log_to_file(std::string("llama.cpp: ") + msg, effective);
}

// ---------------- 既存ユーティリティ ----------------
static std::string jstring_to_std(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars ? chars : "");
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

static void throw_java_exception(JNIEnv *env, const char *msg) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass) env->ThrowNew(exClass, msg);
}

// Helper function to process escape sequences in a string
// Converts user-friendly escape sequences like "\n" (backslash+n) to actual characters (newline)
static std::string process_escape_sequences(const std::string& input) {
    std::string result;
    for (size_t i = 0; i < input.size(); ++i) {
        if (input[i] == '\\' && i + 1 < input.size()) {
            switch (input[i + 1]) {
                case 'n': result += '\n'; i++; break;
                case 't': result += '\t'; i++; break;
                case 'r': result += '\r'; i++; break;
                case '\\': result += '\\'; i++; break;
                case '"': result += '"'; i++; break;
                default: result += input[i]; break;
            }
        } else {
            result += input[i];
        }
    }
    return result;
}

// ---------------- download() 用 ----------------
static size_t write_data(void* ptr, size_t size, size_t nmemb, void* userdata) {
    std::ofstream* ofs = reinterpret_cast<std::ofstream*>(userdata);
    ofs->write(reinterpret_cast<const char*>(ptr), size * nmemb);
    return size * nmemb;
}

struct ProgressData {
    jobject thiz_global;
    jmethodID onProgressMethod;
    int last_percent;
};

static int xferinfo(void* p, curl_off_t dltotal, curl_off_t dlnow, curl_off_t, curl_off_t) {
    ProgressData* pd = reinterpret_cast<ProgressData*>(p);
    if (!pd) return 0;
    if (dltotal <= 0) return 0;

    int percent = (int)((dlnow * 100) / dltotal);
    if (percent == pd->last_percent) return 0;
    pd->last_percent = percent;

    if (!g_jvm) return 0;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return 0;
        }
        attached = true;
    }

    if (env && pd->thiz_global && pd->onProgressMethod) {
        env->CallVoidMethod(pd->thiz_global, pd->onProgressMethod, (jint)percent);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    {
        std::ostringstream ss;
        ss << "Download progress: " << percent << "%";
        log_to_file(ss.str());
    }

    if (attached) g_jvm->DetachCurrentThread();
    return 0;
}

// ---------------- 解放 ----------------
static void llama_jni_free() {
    std::lock_guard<std::mutex> lock(g_mutex);

    log_to_file("llama_jni_free: freeing resources (explicit)");

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
        log_to_file("Context freed");
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_current_model_path.clear();
        log_to_file("Model freed");
    }

    llama_backend_free();
    log_to_file("Backend freed");

    std::lock_guard<std::mutex> llog(g_log_mutex);
    if (g_log_ofs.is_open()) {
        g_log_ofs << current_time_str() << " [JNI] Log closed" << std::endl;
        g_log_ofs.close();
    }
}

// ---------------- JNI: setLogPath ----------------
extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_llama_LlamaNative_setLogPath(
        JNIEnv *env, jobject, jstring jLogPath) {

    std::string path = jstring_to_std(env, jLogPath);
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_log_ofs.is_open()) {
            g_log_ofs << current_time_str() << " [JNI] Log reopened with path: " << path << std::endl;
            g_log_ofs.close();
        }
        g_log_path = path;
        if (!g_log_path.empty()) {
            g_log_ofs.open(g_log_path, std::ios::app | std::ios::binary);
            if (g_log_ofs) {
                g_log_ofs << current_time_str() << " [JNI] Log opened: " << g_log_path << std::endl;
                g_log_ofs.flush();
            } else {
                LOGE("Failed to open log file: %s", g_log_path.c_str());
            }
        }
    }
}

// ---------------- JNI: setLogLevel ----------------
extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_llama_LlamaNative_setLogLevel(
        JNIEnv *, jobject, jint level) {
    int sanitized = level;
    if (sanitized < GGML_LOG_LEVEL_NONE) sanitized = GGML_LOG_LEVEL_NONE;
    if (sanitized > GGML_LOG_LEVEL_ERROR) sanitized = GGML_LOG_LEVEL_ERROR;
    g_log_level.store(sanitized);
    log_to_file(std::string("log level set to ") + std::to_string(sanitized));
}

// ---------------- JNI: download ----------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_download(
        JNIEnv* env,
        jobject thiz,
        jstring jurl,
        jstring jpath) {

    // Ensure we have a stored JavaVM for callbacks even if init() has not been called yet
    if (!g_jvm) {
        if (env->GetJavaVM(&g_jvm) != JNI_OK) {
            g_jvm = nullptr;
            log_to_file("download: GetJavaVM failed", GGML_LOG_LEVEL_WARN);
        } else {
            log_to_file("download: JavaVM stored");
        }
    }

    const char* url  = env->GetStringUTFChars(jurl,  nullptr);
    const char* path = env->GetStringUTFChars(jpath, nullptr);

    if (!url || !path) {
        if (url)  env->ReleaseStringUTFChars(jurl, url);
        if (path) env->ReleaseStringUTFChars(jpath, path);
        log_to_file("download: invalid args", GGML_LOG_LEVEL_ERROR);
        return env->NewStringUTF("invalid args");
    }

    {
        std::ostringstream ss;
        ss << "download: start url=" << url << " path=" << path;
        log_to_file(ss.str());
    }

    CURL* curl = curl_easy_init();
    if (!curl) {
        env->ReleaseStringUTFChars(jurl,  url);
        env->ReleaseStringUTFChars(jpath, path);
        log_to_file("download: curl init failed", GGML_LOG_LEVEL_ERROR);
        return env->NewStringUTF("curl init failed");
    }

    std::ofstream ofs(path, std::ios::binary);
    if (!ofs) {
        env->ReleaseStringUTFChars(jurl,  url);
        env->ReleaseStringUTFChars(jpath, path);
        curl_easy_cleanup(curl);
        {
            std::ostringstream ss;
            ss << "download: file open failed path=" << path;
            log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
        }
        return env->NewStringUTF("file open failed");
    }

    ProgressData pd;
    pd.last_percent = -1;
    pd.thiz_global = env->NewGlobalRef(thiz);
    pd.onProgressMethod = nullptr;

    jclass cls = env->GetObjectClass(thiz);
    if (cls) {
        pd.onProgressMethod = env->GetMethodID(cls, "onDownloadProgress", "(I)V");
    }

    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_data);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &ofs);

    curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, xferinfo);
    curl_easy_setopt(curl, CURLOPT_XFERINFODATA, &pd);
    curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);

    std::string surl(url);
    // Disable SSL verification for specific hosts (huggingface.co and github.com)
    bool disable_ssl = false;
    std::string ssl_host;
    if (surl.rfind("https://huggingface.co/", 0) == 0) {
        disable_ssl = true;
        ssl_host = "huggingface.co";
    }
    if (surl.rfind("https://github.com/", 0) == 0) {
        disable_ssl = true;
        ssl_host = "github.com";
    }
    if (disable_ssl) {
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
        std::ostringstream ss;
        ss << "download: disabled SSL verification for " << ssl_host;
        log_to_file(ss.str());
    }

    curl_easy_setopt(curl, CURLOPT_USERAGENT,
        "Mozilla/5.0 (Linux; Android 14; Mobile) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Mobile Safari/537.36");

    CURLcode res = curl_easy_perform(curl);

    env->ReleaseStringUTFChars(jurl,  url);
    env->ReleaseStringUTFChars(jpath, path);
    curl_easy_cleanup(curl);
    ofs.close();

    if (pd.thiz_global) env->DeleteGlobalRef(pd.thiz_global);

    if (res != CURLE_OK) {
        std::ostringstream ss;
        ss << "download: curl download failed res=" << res << " msg=" << curl_easy_strerror(res);
        log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
        return env->NewStringUTF("curl download failed");
    }

    log_to_file("download: ok");
    return env->NewStringUTF("ok");
}

// ---------------- JNI: init ----------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_init(
        JNIEnv *env, jobject,
        jstring jModelPath
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    log_to_file("init: start");

    // ★ llama.cpp 内部ログを JNI 側へ流す
    llama_log_set(llama_log_callback, nullptr);
    log_to_file("init: llama_log_callback registered");

    std::string model_path = jstring_to_std(env, jModelPath);

    {
        std::ostringstream ss;
        ss << "init: model_path=" << model_path;
        log_to_file(ss.str());
    }

    // If the same model is already loaded in JNI, skip re-initialization to avoid heavy work
    if (!g_current_model_path.empty() && g_current_model_path == model_path && g_model && g_ctx) {
        std::ostringstream ss;
        ss << "init: model already initialized at path=" << model_path << "; skipping init";
        log_to_file(ss.str());
        return env->NewStringUTF("ok");
    }

    {
        std::ifstream ifs(model_path, std::ios::binary | std::ios::ate);
        if (!ifs) {
            std::ostringstream ss;
            ss << "init: model file cannot be opened: " << model_path
               << " errno=" << errno << " strerror=" << std::strerror(errno);
            log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
            return env->NewStringUTF("model file open failed");
        } else {
            auto sz = ifs.tellg();
            std::ostringstream ss;
            ss << "init: model file exists, size=" << sz << " bytes";
            log_to_file(ss.str());
            ifs.close();
        }
    }

    {
        std::ifstream ifh(model_path, std::ios::binary);
        if (ifh) {
            char hdr_buf[64];
            ifh.read(hdr_buf, sizeof(hdr_buf));
            std::streamsize got = ifh.gcount();
            std::ostringstream ss;
            ss << "init: header(" << got << "):";
            ss << std::hex << std::setfill('0');
            for (std::streamsize i = 0; i < got; ++i) {
                ss << ' ' << std::setw(2)
                   << (static_cast<unsigned int>(static_cast<unsigned char>(hdr_buf[i])));
            }
            ss << " | ";
            for (std::streamsize i = 0; i < got; ++i) {
                unsigned char c = static_cast<unsigned char>(hdr_buf[i]);
                ss << (std::isprint(c) ? static_cast<char>(c) : '.');
            }
            log_to_file(ss.str());
        } else {
            std::ostringstream ss;
            ss << "init: header dump failed to open file: " << model_path;
            log_to_file(ss.str(), GGML_LOG_LEVEL_WARN);
        }
    }

    if (env->GetJavaVM(&g_jvm) != JNI_OK) {
        g_jvm = nullptr;
        log_to_file("init: GetJavaVM failed", GGML_LOG_LEVEL_WARN);
    } else {
        log_to_file("init: JavaVM stored");
    }

    llama_backend_init();
    log_to_file("init: backend init");

    // ★ CPU backend をレジストリ経由で登録
    ggml_backend_reg_t cpu_reg = ggml_backend_cpu_reg();
    if (cpu_reg) {
        ggml_backend_register(cpu_reg);
        log_to_file("init: CPU backend registered via reg");
    } else {
        log_to_file("init: CPU backend_reg() returned null");
    }
    
    llama_model_params mparams = llama_model_default_params();

    {
        using namespace std::chrono;
        auto t0 = high_resolution_clock::now();
        g_model = llama_model_load_from_file(model_path.c_str(), mparams);
        auto t1 = high_resolution_clock::now();
        auto ms = duration_cast<milliseconds>(t1 - t0).count();

        std::ostringstream ss;
        if (!g_model) {
            ss << "init: failed to load model (returned null) after "
               << ms << " ms. path_len=" << model_path.size();
            log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
            return env->NewStringUTF("failed to load model");
        } else {
            ss << "init: model loaded successfully in " << ms << " ms";
            log_to_file(ss.str());
        }
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = g_n_ctx;
    cparams.n_threads       = g_n_threads;
    cparams.n_batch         = g_n_batch;
    cparams.n_threads_batch = g_n_threads;

    {
        using namespace std::chrono;
        auto t0 = high_resolution_clock::now();
        g_ctx = llama_init_from_model(g_model, cparams);
        auto t1 = high_resolution_clock::now();
        auto ms = duration_cast<milliseconds>(t1 - t0).count();

        std::ostringstream ss;
        if (!g_ctx) {
            ss << "init: failed to create context (returned null) after "
               << ms << " ms";
            log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
            return env->NewStringUTF("failed to create context");
        } else {
            ss << "init: context created successfully in " << ms << " ms";
            log_to_file(ss.str());
        }
    }

    g_current_model_path = model_path;
    log_to_file("init: context created");

    return env->NewStringUTF("ok");
}

// ---------------- JNI: setParameters ----------------
extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_llama_LlamaNative_setParameters(
        JNIEnv *env, jobject,
        jint penaltyLastN, jfloat penaltyRepeat, jfloat penaltyFreq, jfloat penaltyPresent,
        jint mirostat, jfloat mirostatTau, jfloat mirostatEta,
        jfloat minP, jfloat typicalP,
        jfloat dynatempRange, jfloat dynatempExponent,
        jfloat xtcProbability, jfloat xtcThreshold,
        jfloat topNSigma,
        jfloat dryMultiplier, jfloat dryBase, jint dryAllowedLength, jint dryPenaltyLastN,
        jstring jDrySequenceBreakers
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    
    // Penalty parameters
    g_penalty_last_n = penaltyLastN;
    g_penalty_repeat = penaltyRepeat;
    g_penalty_freq = penaltyFreq;
    g_penalty_present = penaltyPresent;
    
    // Mirostat parameters
    g_mirostat = mirostat;
    g_mirostat_tau = mirostatTau;
    g_mirostat_eta = mirostatEta;
    
    // Additional sampler parameters
    g_min_p = minP;
    g_typical_p = typicalP;
    g_dynatemp_range = dynatempRange;
    g_dynatemp_exponent = dynatempExponent;
    g_xtc_probability = xtcProbability;
    g_xtc_threshold = xtcThreshold;
    g_top_n_sigma = topNSigma;
    
    // DRY parameters
    g_dry_multiplier = dryMultiplier;
    g_dry_base = dryBase;
    g_dry_allowed_length = dryAllowedLength;
    g_dry_penalty_last_n = dryPenaltyLastN;
    g_dry_sequence_breakers = jstring_to_std(env, jDrySequenceBreakers);
    
    {
        std::ostringstream ss;
        ss << "setParameters: penalty_last_n=" << g_penalty_last_n
           << " penalty_repeat=" << g_penalty_repeat
           << " penalty_freq=" << g_penalty_freq
           << " penalty_present=" << g_penalty_present
           << " mirostat=" << g_mirostat
           << " mirostat_tau=" << g_mirostat_tau
           << " mirostat_eta=" << g_mirostat_eta
           << " min_p=" << g_min_p
           << " typical_p=" << g_typical_p
           << " dynatemp_range=" << g_dynatemp_range
           << " dynatemp_exponent=" << g_dynatemp_exponent
           << " xtc_probability=" << g_xtc_probability
           << " xtc_threshold=" << g_xtc_threshold
           << " top_n_sigma=" << g_top_n_sigma
           << " dry_multiplier=" << g_dry_multiplier
           << " dry_base=" << g_dry_base
           << " dry_allowed_length=" << g_dry_allowed_length
           << " dry_penalty_last_n=" << g_dry_penalty_last_n
           << " dry_sequence_breakers=\"" << g_dry_sequence_breakers << "\"";
        log_to_file(ss.str());
    }
}

// ---------------- JNI: setTokenListener ----------------
extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_llama_LlamaNative_setTokenListener(
        JNIEnv *env, jobject /*thiz*/, jobject listener) {
    // Do NOT lock g_mutex here to avoid deadlocks when this is called from Java callbacks.
    // Ensure JavaVM stored
    if (!g_jvm) {
        if (env->GetJavaVM(&g_jvm) != JNI_OK) {
            g_jvm = nullptr;
            log_to_file("setTokenListener: GetJavaVM failed", GGML_LOG_LEVEL_WARN);
        } else {
            log_to_file("setTokenListener: JavaVM stored");
        }
    }
    // Delete previous global ref if exists
    if (g_token_listener) {
        env->DeleteGlobalRef(g_token_listener);
        g_token_listener = nullptr;
        g_token_onToken = nullptr;
        g_token_onComplete = nullptr;
        g_token_onError = nullptr;
    }
    if (listener == nullptr) {
        log_to_file("setTokenListener: cleared listener");
        return;
    }
    g_token_listener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    if (cls) {
        g_token_onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        g_token_onComplete = env->GetMethodID(cls, "onComplete", "()V");
        g_token_onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
    }
    log_to_file("setTokenListener: listener registered");
    if (should_log_debug()) {
        LOGD("setTokenListener: listener registered");
    }
}

// ---------------- JNI: generate ----------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_generate(
        JNIEnv *env, jobject,
        jstring jPrompt
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_ctx || !g_model) {
        log_to_file("generate: not initialized", GGML_LOG_LEVEL_ERROR);
        return env->NewStringUTF("not initialized");
    }

    std::string prompt = jstring_to_std(env, jPrompt);
    {
        std::ostringstream ss;
        ss << "generate: prompt_len=" << prompt.size();
        log_to_file(ss.str());
    }
    {
        std::ostringstream ss;
        ss << "generate: prompt=\n" << prompt;
        log_to_file(ss.str());
    }
    const int max_tokens = 1024;

    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_memory_seq_rm(mem, -1, 0, -1);
    {
        std::ostringstream ss;
        ss << "generate: kv cache cleared; ctx=" << g_n_ctx;
        log_to_file(ss.str());
    }

    std::vector<llama_token> tokens;
    tokens.resize(g_n_ctx);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    
    int32_t n_tokens = llama_tokenize(
            vocab,
            prompt.c_str(),
            (int)prompt.size(),
            tokens.data(),
            (int)tokens.size(),
            false,
            true
    );

    if (n_tokens <= 0) {
        log_to_file("generate: tokenize failed", GGML_LOG_LEVEL_ERROR);
        return env->NewStringUTF("tokenize failed");
    }

    {
        std::ostringstream ss;
        ss << "generate: n_tokens=" << n_tokens;
        log_to_file(ss.str());
    }

    if (n_tokens >= g_n_ctx) {
        std::ostringstream ss;
        ss << "generate: n_tokens(" << n_tokens << ") exceeds ctx(" << g_n_ctx << ")";
        log_to_file(ss.str(), GGML_LOG_LEVEL_WARN);
        return env->NewStringUTF("token count exceeds context");
    }

    tokens.resize(n_tokens);

    std::string output;
    output.reserve(max_tokens * 4);

    {
        log_to_file("generate: processing prompt in batches");
        if (g_log_ofs.is_open()) g_log_ofs.flush();
        
        auto t_decode0 = std::chrono::high_resolution_clock::now();
        
        // Process prompt in chunks of g_n_batch size to avoid OOM on Android
        for (int i = 0; i < n_tokens; i += g_n_batch) {
            int batch_size = std::min(g_n_batch, n_tokens - i);
            
            {
                std::ostringstream ss;
                ss << "generate: processing batch " << (i / g_n_batch + 1) 
                   << " (tokens " << i << "-" << (i + batch_size - 1) << ")";
                log_to_file(ss.str());
            }
            
            llama_batch batch = llama_batch_init(batch_size, 0, 1);
            batch.n_tokens = batch_size;
            
            for (int j = 0; j < batch_size; j++) {
                batch.token[j] = tokens[i + j];
                batch.pos[j] = i + j;
                batch.n_seq_id[j] = 1;
                batch.seq_id[j][0] = 0;
                // Only compute logits for the last token of the entire prompt
                batch.logits[j] = (i + j == n_tokens - 1) ? 1 : 0;
            }
            
            int rc = llama_decode(g_ctx, batch);
            llama_batch_free(batch);
            
            if (rc != 0) {
                std::ostringstream ss;
                ss << "generate: decode failed at batch " << (i / g_n_batch + 1) 
                   << " (rc=" << rc << ")";
                log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
                if (g_log_ofs.is_open()) g_log_ofs.flush();
                // Notify Java listener about error
                if (g_jvm && g_token_listener && g_token_onError) {
                    JNIEnv* env = nullptr;
                    bool attached = false;
                    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                            attached = true;
                        } else {
                            env = nullptr;
                        }
                    }
                    if (env) {
                        const char *errmsg = "decode failed (prompt)";
                        jstring jerr = env->NewStringUTF(errmsg);
                        if (!jerr) {
                            if (env->ExceptionCheck()) env->ExceptionClear();
                            jerr = env->NewStringUTF("unknown error");
                        }
                        if (jerr) {
                            if (should_log_debug()) {
                                LOGD("token listener onError (prompt) sending");
                            }
                            env->CallVoidMethod(g_token_listener, g_token_onError, jerr);
                            if (env->ExceptionCheck()) env->ExceptionClear();
                            env->DeleteLocalRef(jerr);
                        }
                    }
                    if (attached) g_jvm->DetachCurrentThread();
                }
                return env->NewStringUTF("decode failed (prompt)");
            }
        }
        
        auto t_decode1 = std::chrono::high_resolution_clock::now();
        auto ms_prompt = std::chrono::duration_cast<std::chrono::milliseconds>(t_decode1 - t_decode0).count();
        {
            std::ostringstream ss;
            ss << "generate: prompt decode complete, ms=" << ms_prompt;
            log_to_file(ss.str());
        }
        
        log_to_file("generate: prompt processed with chunked batch decode");
        if (g_log_ofs.is_open()) g_log_ofs.flush();
    }

    const int n_vocab = llama_vocab_n_tokens(vocab);
    
    // Build sampler chain based on parameters
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    
    // 1. Add penalties sampler (if enabled)
    if (g_penalty_last_n > 0 && (g_penalty_repeat != 1.0f || g_penalty_freq != 0.0f || g_penalty_present != 0.0f)) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            g_penalty_last_n, g_penalty_repeat, g_penalty_freq, g_penalty_present));
        log_to_file("generate: added penalties sampler");
    }
    
    // 2. Add DRY sampler (if enabled)
    if (g_dry_multiplier > 0.0f) {
        // Parse comma-separated sequence breakers with escape sequence support
        // Users input escape sequences like "\n" (two characters: backslash + n)
        // We need to convert them to actual characters (one character: newline)
        std::vector<std::string> breaker_strings;
        std::vector<const char*> breaker_ptrs;
        
        std::string temp = g_dry_sequence_breakers;
        size_t pos = 0;
        while ((pos = temp.find(',')) != std::string::npos) {
            std::string token = temp.substr(0, pos);
            if (!token.empty()) {
                breaker_strings.push_back(process_escape_sequences(token));
            }
            temp.erase(0, pos + 1);
        }
        // Don't forget the last token
        if (!temp.empty()) {
            breaker_strings.push_back(process_escape_sequences(temp));
        }
        
        // Convert to const char* array
        for (const auto& s : breaker_strings) {
            breaker_ptrs.push_back(s.c_str());
        }
        
        if (!breaker_ptrs.empty()) {
            llama_sampler_chain_add(smpl, llama_sampler_init_dry(
                vocab, g_n_ctx, g_dry_multiplier, g_dry_base, 
                g_dry_allowed_length, g_dry_penalty_last_n, 
                breaker_ptrs.data(), breaker_ptrs.size()));
            
            std::ostringstream ss;
            ss << "generate: added DRY sampler with " << breaker_ptrs.size() << " breakers";
            log_to_file(ss.str());
        }
    }
    
    // 3. Add top-n-sigma (if enabled)
    if (g_top_n_sigma > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_n_sigma(g_top_n_sigma));
        log_to_file("generate: added top-n-sigma sampler");
    }
    
    // 4. Add top-k (if enabled)
    if (g_top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(g_top_k));
        log_to_file("generate: added top-k sampler");
    }
    
    // 5. Add typical-p (if enabled)
    if (g_typical_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_typical(g_typical_p, 1));
        log_to_file("generate: added typical-p sampler");
    }
    
    // 6. Add top-p (if enabled)
    if (g_top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(g_top_p, 1));
        log_to_file("generate: added top-p sampler");
    }
    
    // 7. Add min-p (if enabled)
    if (g_min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(g_min_p, 1));
        log_to_file("generate: added min-p sampler");
    }
    
    // 8. Add XTC (if enabled)
    if (g_xtc_probability > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_xtc(
            g_xtc_probability, g_xtc_threshold, 1, LLAMA_DEFAULT_SEED));
        log_to_file("generate: added XTC sampler");
    }
    
    // 9. Add temperature sampler
    if (g_dynatemp_range > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp_ext(
            g_temp, g_dynatemp_range, g_dynatemp_exponent));
        log_to_file("generate: added dynamic temperature sampler");
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(g_temp));
        log_to_file("generate: added temperature sampler");
    }
    
    // 10. Add mirostat or distribution sampler
    if (g_mirostat == 1) {
        llama_sampler_chain_add(smpl, llama_sampler_init_mirostat(
            n_vocab, LLAMA_DEFAULT_SEED, g_mirostat_tau, g_mirostat_eta, 100));
        log_to_file("generate: added mirostat v1 sampler");
    } else if (g_mirostat == 2) {
        llama_sampler_chain_add(smpl, llama_sampler_init_mirostat_v2(
            LLAMA_DEFAULT_SEED, g_mirostat_tau, g_mirostat_eta));
        log_to_file("generate: added mirostat v2 sampler");
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        log_to_file("generate: added distribution sampler");
    }

    log_to_file("generate: sampler chain initialized");

// detokenize 用にトークン列を保持
    std::vector<llama_token> out_tokens;
    out_tokens.reserve(max_tokens);

    std::string prev_text;   // ★ 差分抽出用

    log_to_file("generate: entering decode loop");
    for (int i = 0; i < max_tokens; ++i) {
        {
            std::ostringstream ss;
            ss << "generate: step=" << i << " out_tokens=" << out_tokens.size();
            log_to_file(ss.str());
        }
        // Get logits for the last token (index -1 means last position)
        const llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        {
            std::ostringstream ss;
            ss << "generate: sampled token id=" << id;
            log_to_file(ss.str());
        }

        // Accept the token
        llama_sampler_accept(smpl, id);

        // check eos
        if (llama_vocab_is_eog(vocab, id)) {
            log_to_file("generate: reached EOS");
            break;
        }

        // ★ 生成トークンを累積
        out_tokens.push_back(id);

        // ★ ctx の残量チェック（安全マージン 32）
        if ((int)out_tokens.size() >= g_n_ctx - 32) {
            log_to_file("generate: reached ctx safety limit, stopping early");
            break;
        }
        // ★ 累積トークン列を detokenize して全文を得る
        std::string full;
        int n_chars = detokenize_with_resize(vocab, out_tokens, full);

        if (n_chars > 0) {
            size_t safe_len = validate_utf8(full);
            if (safe_len < full.size()) {
                full.resize(safe_len);
            }

            // Compute delta from previous text
            std::string delta;
            if (full.size() > prev_text.size()) {
                delta = full.substr(prev_text.size());
                output += delta;
            }

            // Call token listener with delta if available
            if (!delta.empty() && g_token_listener && g_token_onToken && g_jvm) {
                JNIEnv* env = nullptr;
                bool attached = false;
                if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                    if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                        attached = true;
                    } else {
                        env = nullptr;
                    }
                }
                if (env) {
                    jstring jdelta = env->NewStringUTF(delta.c_str());
                    if (jdelta) {
                        if (should_log_debug()) {
                            LOGD("token listener onToken delta_len=%zu", delta.size());
                        }
                        env->CallVoidMethod(g_token_listener, g_token_onToken, jdelta);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        env->DeleteLocalRef(jdelta);
                    } else {
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        log_to_file("token listener onToken: failed to create jstring", GGML_LOG_LEVEL_WARN);
                    }
                }
                if (attached) g_jvm->DetachCurrentThread();
            }

            prev_text = full;
            {
                std::ostringstream ss;
                ss << "generate: detok chars=" << n_chars
                   << " output_len=" << output.size()
                   << " full_len=" << full.size()
                   << " step=" << i;
                log_to_file(ss.str());
            }
        } else {
            // Only log if detokenize fails (unusual case)
            if (n_chars < 0) {
                std::ostringstream ss;
                ss << "generate: detokenize error n_chars=" << n_chars;
                log_to_file(ss.str());
            }
        }

        // feed token into model for next step using llama_batch_init
        llama_batch batch = llama_batch_init(1, 0, 1);
        batch.n_tokens = 1;
        batch.token[0] = id;
        batch.pos[0] = n_tokens + i;  // position = prompt length + step
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;  // compute logits for this token
        {
            std::ostringstream ss;
            ss << "generate: calling decode for next token, id=" << id
               << " pos=" << (n_tokens + i) << " step=" << i;
            log_to_file(ss.str());
        }
        auto t_step0 = std::chrono::high_resolution_clock::now();
        int rc_step = llama_decode(g_ctx, batch);
        auto t_step1 = std::chrono::high_resolution_clock::now();
        auto ms_step = std::chrono::duration_cast<std::chrono::milliseconds>(t_step1 - t_step0).count();
        llama_batch_free(batch);
        {
            std::ostringstream ss;
            ss << "generate: decode rc=" << rc_step << " ms=" << ms_step << " step=" << i;
            log_to_file(ss.str());
        }
        if (rc_step != 0) {
            log_to_file("generate: decode failed (generation)", GGML_LOG_LEVEL_ERROR);
            if (g_log_ofs.is_open()) g_log_ofs.flush();
            // Notify Java listener about error
            if (g_jvm && g_token_listener && g_token_onError) {
                JNIEnv* env = nullptr;
                bool attached = false;
                if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                    if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                        attached = true;
                    } else {
                        env = nullptr;
                    }
                }
                if (env) {
                    const char *errmsg = "decode failed (generation)";
                    jstring jerr = env->NewStringUTF(errmsg);
                    if (!jerr) {
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        jerr = env->NewStringUTF("unknown error");
                    }
                    if (jerr) {
                        if (should_log_debug()) {
                            LOGD("token listener onError (generation) sending");
                        }
                        env->CallVoidMethod(g_token_listener, g_token_onError, jerr);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        env->DeleteLocalRef(jerr);
                    }
                }
                if (attached) g_jvm->DetachCurrentThread();
            }
            llama_sampler_free(smpl);
            return env->NewStringUTF("decode failed (generation)");
        }
    }

    // Notify Java listener that generation is complete
    if (g_jvm && g_token_listener && g_token_onComplete) {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                env = nullptr;
            }
        }
        if (env) {
            if (should_log_debug()) {
                LOGD("token listener onComplete sending");
            }
            env->CallVoidMethod(g_token_listener, g_token_onComplete);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (attached) g_jvm->DetachCurrentThread();
    }

    // Free the sampler chain
    llama_sampler_free(smpl);

    {
        size_t safe_len = validate_utf8(output);
        if (safe_len < output.size()) {
            output.resize(safe_len);
        }
    }

    {
        std::ostringstream ss;
        ss << "generate: finished, output_len=" << output.size();
        log_to_file(ss.str());
    }

    return env->NewStringUTF(output.c_str());
}

// ---------------- JNI: free ----------------
extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_llama_LlamaNative_free(
        JNIEnv *env, jobject /*thiz*/
) {
    log_to_file("Java_com_micklab_llama_LlamaNative_free called");
    llama_jni_free();
}

// ---------------- JNI: getChatTemplate ----------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_getChatTemplate(
        JNIEnv *env, jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model) {
        log_to_file("getChatTemplate: model not loaded", GGML_LOG_LEVEL_WARN);
        return env->NewStringUTF("");
    }
    
    // Try to get chat template from GGUF metadata
    const char* chat_template = llama_model_chat_template(g_model, nullptr);
    
    if (chat_template && strlen(chat_template) > 0) {
        log_to_file(std::string("getChatTemplate: found template, len=") + std::to_string(strlen(chat_template)));
        return env->NewStringUTF(chat_template);
    }
    
    log_to_file("getChatTemplate: no chat template in model metadata");
    return env->NewStringUTF("");
}
