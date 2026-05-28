#include <jni.h>
#include <algorithm>
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
#include <exception>
#include <signal.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#if defined(__GLIBC__)
#include <malloc.h>
#endif

#if defined(__ANDROID__)
#include <dlfcn.h>
#include <malloc.h>
#endif

#include <android/log.h>
#define LOG_TAG "LLAMA_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#include <nlohmann/json.hpp>

#include "llama.h"
#include "gguf.h"
#include "ggml-backend.h"
#include "ggml-backend-impl.h"   // ★ これが必要
#include "ggml-cpu.h"
#include "chat.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"
#include <curl/curl.h>

using json = nlohmann::ordered_json;

// ---------------- グローバル ----------------
static std::mutex g_mutex;
static llama_model   *g_model = nullptr;
static llama_context *g_ctx   = nullptr;
static mtmd_context  *g_mtmd  = nullptr;
static JavaVM *g_jvm = nullptr;
// Token listener global ref and method IDs
static jobject g_token_listener = nullptr;
static jmethodID g_token_onToken = nullptr;
static jmethodID g_token_onComplete = nullptr;
static jmethodID g_token_onError = nullptr;
static std::atomic<bool> g_cancel_generation(false);
static bool g_backend_initialized = false;
// Keep track of currently loaded model path to avoid redundant inits
static std::string g_current_model_path;
static std::string g_current_mmproj_path;
static bool g_supports_vision = false;
static bool g_supports_audio = false;
static std::mutex g_download_ca_bundle_mutex;
static std::string g_download_ca_bundle_path;

// ログ用
static std::mutex g_log_mutex;
static std::string g_log_path;
static std::ofstream g_log_ofs;
static std::atomic<int> g_log_level(GGML_LOG_LEVEL_INFO);
static volatile sig_atomic_t g_signal_log_fd = -1;
static volatile sig_atomic_t g_signal_crash_fd = -1;
static std::atomic<bool> g_fatal_signal_handlers_installed(false);
static constexpr const char * NATIVE_CRASH_LOG_FILENAME = "native_crash.txt";
static void log_to_file(const std::string& msg, ggml_log_level level = GGML_LOG_LEVEL_INFO);

// 設定
static int   g_n_ctx      = 2048;
static int   g_n_threads  = 2;
static int   g_n_batch    = 16;
static float g_temp       = 0.7f;
static float g_top_p      = 0.9f;
static int   g_top_k      = 40;
static int   g_n_gpu_layers = 0;

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

// Common stop sequences for chat templates to prevent runaway generation
static const std::vector<std::string> DEFAULT_STOP_SEQUENCES = {
        "<|end|>",
        "</s>",
        "<|eot_id|>",
        "<|im_end|>",
        "<|IM_END|>",
        "<|im_end|",
        "<|IM_END|",
        "<|im_end",
        "<|IM_END",
        "<end",
        "<END",
        "<end_of_turn>",
        "<|user|>",
        "<|system|>",
        "<|im_start|>user",
        "<|im_start|>system",
        "<start_of_turn>user",
        "<start_of_turn>model"
};

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

static bool is_max_debug_mode() {
    return g_log_level.load() == GGML_LOG_LEVEL_NONE;
}

static size_t append_literal(char * buffer, size_t offset, size_t capacity, const char * text) {
    if (!text || offset >= capacity) {
        return offset;
    }
    while (*text != '\0' && offset < capacity) {
        buffer[offset++] = *text++;
    }
    return offset;
}

static size_t append_decimal(char * buffer, size_t offset, size_t capacity, int value) {
    if (offset >= capacity) {
        return offset;
    }
    if (value == 0) {
        if (offset < capacity) {
            buffer[offset++] = '0';
        }
        return offset;
    }

    unsigned int magnitude;
    if (value < 0) {
        if (offset < capacity) {
            buffer[offset++] = '-';
        }
        magnitude = static_cast<unsigned int>(-(value + 1)) + 1u;
    } else {
        magnitude = static_cast<unsigned int>(value);
    }

    char digits[16];
    size_t count = 0;
    while (magnitude > 0 && count < sizeof(digits)) {
        digits[count++] = static_cast<char>('0' + (magnitude % 10u));
        magnitude /= 10u;
    }
    while (count > 0 && offset < capacity) {
        buffer[offset++] = digits[--count];
    }
    return offset;
}

static const char * fatal_signal_name(int signo) {
    switch (signo) {
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGSEGV: return "SIGSEGV";
        default:      return "UNKNOWN";
    }
}

static void write_best_effort(int fd, const char * buffer, size_t length) {
    if (fd < 0 || !buffer || length == 0) {
        return;
    }
    size_t written = 0;
    while (written < length) {
        const ssize_t rc = write(fd, buffer + written, length - written);
        if (rc > 0) {
            written += static_cast<size_t>(rc);
            continue;
        }
        if (rc < 0 && errno == EINTR) {
            continue;
        }
        break;
    }
}

static void write_native_crash_marker(int fd, int signo) {
    char buffer[256];
    size_t len = 0;
    len = append_literal(buffer, len, sizeof(buffer), "\n=== Native fatal signal ===\nSignal: ");
    len = append_literal(buffer, len, sizeof(buffer), fatal_signal_name(signo));
    len = append_literal(buffer, len, sizeof(buffer), " (");
    len = append_decimal(buffer, len, sizeof(buffer), signo);
    len = append_literal(buffer, len, sizeof(buffer), ")\nThe process aborted before Java crash handlers could run.\nSee ollama.log for the preceding JNI/llama.cpp lines.\n");
    write_best_effort(fd, buffer, len);
}

static void native_fatal_signal_handler(int signo, siginfo_t *, void *) {
    const int saved_errno = errno;
    const int crash_fd = static_cast<int>(g_signal_crash_fd);
    const int log_fd = static_cast<int>(g_signal_log_fd);

    write_native_crash_marker(crash_fd, signo);
    if (log_fd >= 0 && log_fd != crash_fd) {
        write_native_crash_marker(log_fd, signo);
    }

    errno = saved_errno;
    kill(getpid(), signo);
    _exit(128 + signo);
}

static void ensure_fatal_signal_handlers_installed() {
    if (g_fatal_signal_handlers_installed.exchange(true)) {
        return;
    }

    struct sigaction action = {};
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = native_fatal_signal_handler;
    action.sa_flags = SA_SIGINFO | SA_RESETHAND | SA_NODEFER;

    const int fatal_signals[] = {SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV};
    for (const int signo : fatal_signals) {
        sigaction(signo, &action, nullptr);
    }
}

static std::string derive_crash_log_path(const std::string & log_path) {
    if (log_path.empty()) {
        return "";
    }
    const size_t slash = log_path.find_last_of("/\\");
    if (slash == std::string::npos) {
        return NATIVE_CRASH_LOG_FILENAME;
    }
    return log_path.substr(0, slash + 1) + NATIVE_CRASH_LOG_FILENAME;
}

static void replace_signal_fd(volatile sig_atomic_t * target, int new_fd) {
    if (!target || new_fd < 0) {
        return;
    }
    const int old_fd = static_cast<int>(*target);
    *target = static_cast<sig_atomic_t>(new_fd);
    if (old_fd >= 0 && old_fd != new_fd) {
        close(old_fd);
    }
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

static void append_replacement_char(std::u16string & out) {
    out.push_back(static_cast<char16_t>(0xFFFD));
}

static void append_code_point_utf16(std::u16string & out, uint32_t code_point) {
    if (code_point <= 0xFFFF) {
        out.push_back(static_cast<char16_t>(code_point));
        return;
    }

    code_point -= 0x10000;
    out.push_back(static_cast<char16_t>(0xD800 + ((code_point >> 10) & 0x3FF)));
    out.push_back(static_cast<char16_t>(0xDC00 + (code_point & 0x3FF)));
}

static std::u16string utf8_to_utf16_lossy(const std::string & text) {
    std::u16string out;
    out.reserve(text.size());

    size_t i = 0;
    while (i < text.size()) {
        const unsigned char c0 = static_cast<unsigned char>(text[i]);
        uint32_t code_point = 0;
        size_t width = 0;
        bool valid = false;

        if (c0 <= 0x7F) {
            code_point = c0;
            width = 1;
            valid = true;
        } else if (c0 >= 0xC2 && c0 <= 0xDF && i + 1 < text.size()) {
            const unsigned char c1 = static_cast<unsigned char>(text[i + 1]);
            if ((c1 & 0xC0) == 0x80) {
                code_point = ((c0 & 0x1F) << 6) | (c1 & 0x3F);
                width = 2;
                valid = true;
            }
        } else if (c0 == 0xE0 && i + 2 < text.size()) {
            const unsigned char c1 = static_cast<unsigned char>(text[i + 1]);
            const unsigned char c2 = static_cast<unsigned char>(text[i + 2]);
            if (c1 >= 0xA0 && c1 <= 0xBF && (c2 & 0xC0) == 0x80) {
                code_point = ((c0 & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
                width = 3;
                valid = true;
            }
        } else if ((c0 >= 0xE1 && c0 <= 0xEC || c0 >= 0xEE && c0 <= 0xEF) && i + 2 < text.size()) {
            const unsigned char c1 = static_cast<unsigned char>(text[i + 1]);
            const unsigned char c2 = static_cast<unsigned char>(text[i + 2]);
            if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80) {
                code_point = ((c0 & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
                width = 3;
                valid = true;
            }
        } else if (c0 == 0xED && i + 2 < text.size()) {
            const unsigned char c1 = static_cast<unsigned char>(text[i + 1]);
            const unsigned char c2 = static_cast<unsigned char>(text[i + 2]);
            if (c1 >= 0x80 && c1 <= 0x9F && (c2 & 0xC0) == 0x80) {
                code_point = ((c0 & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
                width = 3;
                valid = true;
            }
        } else if (c0 == 0xF0 && i + 3 < text.size()) {
            const unsigned char c1 = static_cast<unsigned char>(text[i + 1]);
            const unsigned char c2 = static_cast<unsigned char>(text[i + 2]);
            const unsigned char c3 = static_cast<unsigned char>(text[i + 3]);
            if (c1 >= 0x90 && c1 <= 0xBF
                    && (c2 & 0xC0) == 0x80
                    && (c3 & 0xC0) == 0x80) {
                code_point = ((c0 & 0x07) << 18)
                        | ((c1 & 0x3F) << 12)
                        | ((c2 & 0x3F) << 6)
                        | (c3 & 0x3F);
                width = 4;
                valid = true;
            }
        } else if ((c0 >= 0xF1 && c0 <= 0xF3) && i + 3 < text.size()) {
            const unsigned char c1 = static_cast<unsigned char>(text[i + 1]);
            const unsigned char c2 = static_cast<unsigned char>(text[i + 2]);
            const unsigned char c3 = static_cast<unsigned char>(text[i + 3]);
            if ((c1 & 0xC0) == 0x80
                    && (c2 & 0xC0) == 0x80
                    && (c3 & 0xC0) == 0x80) {
                code_point = ((c0 & 0x07) << 18)
                        | ((c1 & 0x3F) << 12)
                        | ((c2 & 0x3F) << 6)
                        | (c3 & 0x3F);
                width = 4;
                valid = true;
            }
        } else if (c0 == 0xF4 && i + 3 < text.size()) {
            const unsigned char c1 = static_cast<unsigned char>(text[i + 1]);
            const unsigned char c2 = static_cast<unsigned char>(text[i + 2]);
            const unsigned char c3 = static_cast<unsigned char>(text[i + 3]);
            if (c1 >= 0x80 && c1 <= 0x8F
                    && (c2 & 0xC0) == 0x80
                    && (c3 & 0xC0) == 0x80) {
                code_point = ((c0 & 0x07) << 18)
                        | ((c1 & 0x3F) << 12)
                        | ((c2 & 0x3F) << 6)
                        | (c3 & 0x3F);
                width = 4;
                valid = true;
            }
        }

        if (!valid) {
            append_replacement_char(out);
            ++i;
            continue;
        }

        append_code_point_utf16(out, code_point);
        i += width;
    }

    return out;
}

static jstring new_java_string_utf8(JNIEnv * env, const std::string & text) {
    static const jchar empty_chars[1] = {0};
    const std::u16string utf16 = utf8_to_utf16_lossy(text);
    const jchar * chars = utf16.empty()
            ? empty_chars
            : reinterpret_cast<const jchar *>(utf16.data());
    return env->NewString(chars, static_cast<jsize>(utf16.size()));
}

static jstring new_java_string_utf8(JNIEnv * env, const char * text) {
    return new_java_string_utf8(env, text != nullptr ? std::string(text) : std::string());
}

static bool find_stop_sequence(const std::string& text, size_t * stop_pos, std::string * stop_seq) {
    size_t earliest = std::string::npos;
    std::string found;
    for (const auto& seq : DEFAULT_STOP_SEQUENCES) {
        size_t pos = text.find(seq);
        if (pos != std::string::npos && (earliest == std::string::npos || pos < earliest)) {
            earliest = pos;
            found = seq;
        }
    }
    if (earliest != std::string::npos) {
        if (stop_pos) *stop_pos = earliest;
        if (stop_seq) *stop_seq = found;
        return true;
    }
    return false;
}

static void log_requested_load_params(const char * log_prefix) {
    std::ostringstream ss;
    ss << log_prefix
       << ": requested load params n_ctx=" << g_n_ctx
       << " n_threads=" << g_n_threads
       << " n_batch=" << g_n_batch
       << " n_gpu_layers=" << g_n_gpu_layers
       << " temp=" << g_temp
       << " top_p=" << g_top_p
       << " top_k=" << g_top_k;
    log_to_file(ss.str());
}

static std::string build_model_load_failure_message(const char * log_prefix) {
    std::ostringstream ss;
    ss << log_prefix
       << ": failed to load model (possible mmap/address-space exhaustion or low_4GB fragmentation)";
    return ss.str();
}

static std::string build_context_load_failure_message(const char * log_prefix) {
    std::ostringstream ss;
    ss << log_prefix
       << ": failed to create context (n_ctx=" << g_n_ctx
       << ", possible commit/address-space exhaustion)";
    return ss.str();
}

static std::string summarize_registered_backends() {
    const size_t backend_count = ggml_backend_reg_count();
    std::ostringstream ss;
    ss << "registered backends=" << backend_count;
    if (backend_count > 0) {
        ss << " [";
        for (size_t i = 0; i < backend_count; ++i) {
            if (i > 0) {
                ss << ", ";
            }
            ggml_backend_reg_t reg = ggml_backend_reg_get(i);
            ss << (reg ? ggml_backend_reg_name(reg) : "<null>");
        }
        ss << "]";
    }
    return ss.str();
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

struct reusable_token_batch {
    llama_batch batch;

    explicit reusable_token_batch(int32_t capacity)
            : batch(llama_batch_init(std::max<int32_t>(capacity, 1), 0, 1)) {
    }

    ~reusable_token_batch() {
        llama_batch_free(batch);
    }

    bool valid() const {
        return batch.token != nullptr
                && batch.pos != nullptr
                && batch.n_seq_id != nullptr
                && batch.seq_id != nullptr
                && batch.seq_id[0] != nullptr
                && batch.logits != nullptr;
    }

    void set_token(int32_t index, llama_token token, llama_pos pos, bool logits) {
        batch.token[index] = token;
        batch.pos[index] = pos;
        batch.n_seq_id[index] = 1;
        batch.seq_id[index][0] = 0;
        batch.logits[index] = logits ? 1 : 0;
    }
};

static void reset_generation_state_locked() {
    if (!g_ctx) {
        return;
    }
    // Upstream Android llama.cpp examples only clear memory metadata between generations.
    // Clearing backing buffers on every request exercises extra backend buffer operations and
    // has correlated with repeated-run instability on recurrent/MTP models.
    llama_synchronize(g_ctx);
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (mem != nullptr) {
        llama_memory_clear(mem, false);
    }
    llama_synchronize(g_ctx);
    llama_perf_context_reset(g_ctx);
}

static void log_to_file(const std::string& msg, ggml_log_level level) {
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

static void trim_native_allocator(const char * reason) {
#if defined(__GLIBC__)
    const int trimmed = malloc_trim(0);
    std::ostringstream ss;
    ss << reason << ": malloc_trim result=" << trimmed;
    log_to_file(ss.str());
#elif defined(__ANDROID__)
    using mallopt_fn = int (*)(int, int);
    mallopt_fn mallopt_ptr = reinterpret_cast<mallopt_fn>(dlsym(RTLD_DEFAULT, "mallopt"));
    if (mallopt_ptr == nullptr) {
        if (reason != nullptr) {
            log_to_file(std::string(reason) + ": mallopt unavailable on this platform");
        }
        return;
    }

    int purge_all_result = 0;
    int purge_result = 0;
#if defined(M_PURGE_ALL)
    purge_all_result = mallopt_ptr(M_PURGE_ALL, 0);
#endif
#if defined(M_PURGE)
    purge_result = mallopt_ptr(M_PURGE, 0);
#endif
    std::ostringstream ss;
    ss << reason
       << ": mallopt purge_all=" << purge_all_result
       << " purge=" << purge_result;
    log_to_file(ss.str());
#else
    if (reason != nullptr) {
        log_to_file(std::string(reason) + ": malloc_trim unavailable on this platform");
    }
#endif
}

static void ensure_backend_initialized_locked(const char * log_prefix) {
    if (g_backend_initialized) {
        std::ostringstream ss;
        ss << log_prefix << ": backend already initialized, reusing process-wide backend";
        log_to_file(ss.str());
        return;
    }

    llama_backend_init();
    g_backend_initialized = true;

    std::ostringstream ss;
    ss << log_prefix << ": backend init complete, " << summarize_registered_backends();
    const size_t backend_count = ggml_backend_reg_count();
    log_to_file(ss.str(), backend_count == 0 ? GGML_LOG_LEVEL_ERROR : GGML_LOG_LEVEL_INFO);
}

// ---------------- llama.cpp ログコールバック ----------------
// 0.17.1 は llama_log_level ではなく ggml_log_level を使う
static void llama_log_callback(enum ggml_log_level level, const char * text, void * user_data) {
    (void) user_data;
    try {
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
    } catch (const std::exception & e) {
        LOGE("llama_log_callback exception: %s", e.what());
    } catch (...) {
        LOGE("llama_log_callback unknown exception");
    }
}

// ---------------- 既存ユーティリティ ----------------
static std::string jstring_to_std(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars ? chars : "");
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

static std::string to_lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

static std::string path_filename(const std::string & path) {
    const size_t slash = path.find_last_of("/\\");
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

static bool is_regular_file_path(const std::string & path) {
    struct stat st = {};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

static std::string strip_gguf_suffix(const std::string & filename) {
    std::string lower = to_lower_ascii(path_filename(filename));
    if (lower.size() > 5 && lower.compare(lower.size() - 5, 5, ".gguf") == 0) {
        lower.resize(lower.size() - 5);
    }
    return lower;
}

static bool contains_any_substring(const std::string & haystack, const std::vector<std::string> & needles) {
    for (const auto & needle : needles) {
        if (!needle.empty() && haystack.find(needle) != std::string::npos) {
            return true;
        }
    }
    return false;
}

static std::vector<std::string> collect_multimodal_projector_candidates(
        const std::string & model_path,
        const std::string & requested_mmproj_path) {
    (void) model_path;
    std::vector<std::string> result;
    // Only try the projector path that Java has already resolved.
    // Native directory scanning made text-only model switches pick unrelated mmproj files
    // that happened to live next to the model, which could crash during mtmd init.
    if (!requested_mmproj_path.empty() && is_regular_file_path(requested_mmproj_path)) {
        result.push_back(requested_mmproj_path);
    }
    return result;
}

static bool is_likely_multimodal_model_path(const std::string & model_path) {
    const std::string lower_name = strip_gguf_suffix(model_path);
    return contains_any_substring(lower_name, {
            "gemma-4", "gemma4", "gemma-3n", "gemma3n", "llava", "vision",
            "audio", "qwen2-vl", "qwen25vl", "qwen2vl", "qwen2.5-vl",
            "qwen2-audio", "qwen2audio", "qwen2.5-omni", "qwen25omni",
            "glm-4v", "glm4v", "minicpm-v", "minicpmv", "voxtral", "ultravox"
    });
}

static std::string initialize_optional_multimodal_support_locked(
        const std::string & model_path,
        const std::string & requested_mmproj_path,
        const char * log_prefix) {
    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }
    g_supports_vision = false;
    g_supports_audio = false;

    const bool likely_multimodal_model = is_likely_multimodal_model_path(model_path);
    const auto candidates = collect_multimodal_projector_candidates(model_path, requested_mmproj_path);

    if (candidates.empty()) {
        if (!requested_mmproj_path.empty()) {
            std::ostringstream ss;
            ss << log_prefix << ": requested mmproj is unavailable or not a regular file: "
               << requested_mmproj_path << "; loading text-only";
            log_to_file(ss.str(), GGML_LOG_LEVEL_WARN);
        } else if (likely_multimodal_model) {
            std::ostringstream ss;
            ss << log_prefix << ": likely multimodal model detected but no explicit mmproj was requested; "
               << "skipping native projector auto-detection for model " << model_path
               << "; multimodal support disabled";
            log_to_file(ss.str(), GGML_LOG_LEVEL_WARN);
        }
        return "";
    }

    for (const auto & candidate_path : candidates) {
        std::ostringstream start_ss;
        start_ss << log_prefix << ": trying multimodal projector " << candidate_path;
        log_to_file(start_ss.str());

        mtmd_context_params mparams_mtmd = mtmd_context_params_default();
        mparams_mtmd.use_gpu = g_n_gpu_layers != 0;
        mparams_mtmd.print_timings = false;
        mparams_mtmd.n_threads = g_n_threads;
        mparams_mtmd.warmup = true;

        mtmd_context * mtmd = mtmd_init_from_file(candidate_path.c_str(), g_model, mparams_mtmd);
        if (!mtmd) {
            std::ostringstream fail_ss;
            fail_ss << log_prefix << ": failed to initialize multimodal projector " << candidate_path
                    << "; trying next candidate if available";
            log_to_file(fail_ss.str(), GGML_LOG_LEVEL_WARN);
            continue;
        }

        const bool supports_vision = mtmd_support_vision(mtmd);
        const bool supports_audio = mtmd_support_audio(mtmd);
        if (!supports_vision && !supports_audio) {
            std::ostringstream fail_ss;
            fail_ss << log_prefix << ": projector initialized but exposed no supported modalities: "
                    << candidate_path;
            log_to_file(fail_ss.str(), GGML_LOG_LEVEL_WARN);
            mtmd_free(mtmd);
            continue;
        }

        g_mtmd = mtmd;
        g_supports_vision = supports_vision;
        g_supports_audio = supports_audio;

        std::ostringstream ok_ss;
        ok_ss << log_prefix << ": multimodal projector loaded path=" << candidate_path
              << " vision=" << g_supports_vision
              << " audio=" << g_supports_audio;
        log_to_file(ok_ss.str());
        return candidate_path;
    }

    if (likely_multimodal_model || !requested_mmproj_path.empty()) {
        std::ostringstream ss;
        ss << log_prefix << ": no usable multimodal projector could be initialized for model "
           << model_path << "; multimodal support disabled";
        log_to_file(ss.str(), GGML_LOG_LEVEL_WARN);
    }
    return "";
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

static std::vector<std::string> parse_dry_sequence_breakers() {
    std::vector<std::string> breaker_strings;
    std::string temp = g_dry_sequence_breakers;
    size_t pos = 0;
    while ((pos = temp.find(',')) != std::string::npos) {
        std::string token = temp.substr(0, pos);
        if (!token.empty()) {
            breaker_strings.push_back(process_escape_sequences(token));
        }
        temp.erase(0, pos + 1);
    }
    if (!temp.empty()) {
        breaker_strings.push_back(process_escape_sequences(temp));
    }
    return breaker_strings;
}

static bool find_stop_sequence_in_list(
        const std::vector<std::string> & stop_sequences,
        const std::string & text,
        size_t * stop_pos,
        std::string * stop_seq) {
    size_t earliest = std::string::npos;
    std::string found;
    for (const auto & seq : stop_sequences) {
        if (seq.empty()) {
            continue;
        }
        size_t pos = text.find(seq);
        if (pos != std::string::npos && (earliest == std::string::npos || pos < earliest)) {
            earliest = pos;
            found = seq;
        }
    }
    if (earliest != std::string::npos) {
        if (stop_pos) *stop_pos = earliest;
        if (stop_seq) *stop_seq = found;
        return true;
    }
    return false;
}

static common_params_sampling build_chat_sampling_params_locked(const common_chat_params & chat_params) {
    common_params_sampling params;
    params.seed               = LLAMA_DEFAULT_SEED;
    params.top_k              = g_top_k;
    params.top_p              = g_top_p;
    params.min_p              = g_min_p;
    params.xtc_probability    = g_xtc_probability;
    params.xtc_threshold      = g_xtc_threshold;
    params.typ_p              = g_typical_p;
    params.temp               = g_temp;
    params.dynatemp_range     = g_dynatemp_range;
    params.dynatemp_exponent  = g_dynatemp_exponent;
    params.penalty_last_n     = g_penalty_last_n > 0 ? g_penalty_last_n : g_n_ctx;
    params.penalty_repeat     = g_penalty_repeat;
    params.penalty_freq       = g_penalty_freq;
    params.penalty_present    = g_penalty_present;
    params.dry_multiplier     = g_dry_multiplier;
    params.dry_base           = g_dry_base;
    params.dry_allowed_length = g_dry_allowed_length;
    params.dry_penalty_last_n = g_dry_penalty_last_n > 0 ? g_dry_penalty_last_n : g_n_ctx;
    params.mirostat           = g_mirostat;
    params.mirostat_tau       = g_mirostat_tau;
    params.mirostat_eta       = g_mirostat_eta;
    params.top_n_sigma        = g_top_n_sigma;
    params.dry_sequence_breakers = parse_dry_sequence_breakers();
    params.n_prev             = std::max(32, params.penalty_last_n);
    params.grammar_lazy       = chat_params.grammar_lazy;
    params.generation_prompt  = chat_params.generation_prompt;
    params.backend_sampling   = false;

    if (!chat_params.grammar.empty()) {
        params.grammar = { COMMON_GRAMMAR_TYPE_TOOL_CALLS, chat_params.grammar };
    }
    for (const auto & trigger : chat_params.grammar_triggers) {
        params.grammar_triggers.push_back(trigger);
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    for (const auto & token_text : chat_params.preserved_tokens) {
        auto ids = common_tokenize(vocab, token_text, false, true);
        if (ids.size() == 1) {
            params.preserved_tokens.insert(ids[0]);
        }
    }
    return params;
}

static std::vector<std::string> build_chat_stop_sequences(const common_chat_params & chat_params) {
    std::vector<std::string> stop_sequences = DEFAULT_STOP_SEQUENCES;
    for (const auto & stop : chat_params.additional_stops) {
        if (stop.empty()) {
            continue;
        }
        if (std::find(stop_sequences.begin(), stop_sequences.end(), stop) == stop_sequences.end()) {
            stop_sequences.push_back(stop);
        }
    }
    return stop_sequences;
}

// ---------------- download() 用 ----------------
static size_t write_data(void* ptr, size_t size, size_t nmemb, void* userdata) {
    std::ofstream* ofs = reinterpret_cast<std::ofstream*>(userdata);
    if (!ofs) {
        return 0;
    }
    const size_t bytes = size * nmemb;
    ofs->write(reinterpret_cast<const char*>(ptr), bytes);
    if (!(*ofs)) {
        log_to_file("download: file write failed", GGML_LOG_LEVEL_ERROR);
        return 0;
    }
    return bytes;
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

static bool is_existing_nonempty_file(const std::string & path) {
    std::ifstream ifs(path, std::ios::binary | std::ios::ate);
    if (!ifs) {
        return false;
    }
    const std::streampos size = ifs.tellg();
    return size > 0;
}

static bool is_ascii_digits(const std::string & value) {
    if (value.empty()) {
        return false;
    }
    for (unsigned char c : value) {
        if (!std::isdigit(c)) {
            return false;
        }
    }
    return true;
}

static bool parse_split_file_info(
        const std::string & candidate,
        std::string & prefix,
        int & split_no,
        int & split_count) {
    static const std::string suffix = ".gguf";
    if (candidate.size() <= suffix.size() ||
        candidate.compare(candidate.size() - suffix.size(), suffix.size(), suffix) != 0) {
        return false;
    }

    const size_t suffix_pos = candidate.size() - suffix.size();
    const size_t of_pos = candidate.rfind("-of-", suffix_pos);
    if (of_pos == std::string::npos) {
        return false;
    }

    const size_t no_pos = candidate.rfind('-', of_pos == 0 ? 0 : of_pos - 1);
    if (no_pos == std::string::npos) {
        return false;
    }

    const std::string split_no_str = candidate.substr(no_pos + 1, of_pos - (no_pos + 1));
    const std::string split_count_str = candidate.substr(of_pos + 4, suffix_pos - (of_pos + 4));
    if (split_no_str.size() != 5 || split_count_str.size() != 5 ||
        !is_ascii_digits(split_no_str) || !is_ascii_digits(split_count_str)) {
        return false;
    }

    split_no = std::stoi(split_no_str);
    split_count = std::stoi(split_count_str);
    if (split_no <= 0 || split_count <= 1 || split_no > split_count) {
        return false;
    }

    prefix = candidate.substr(0, no_pos);
    return !prefix.empty();
}

static std::string split_url_suffix(const std::string & url, std::string & url_without_suffix) {
    const size_t query_pos = url.find('?');
    const size_t fragment_pos = url.find('#');

    size_t suffix_pos = std::string::npos;
    if (query_pos != std::string::npos && fragment_pos != std::string::npos) {
        suffix_pos = std::min(query_pos, fragment_pos);
    } else if (query_pos != std::string::npos) {
        suffix_pos = query_pos;
    } else {
        suffix_pos = fragment_pos;
    }

    if (suffix_pos == std::string::npos) {
        url_without_suffix = url;
        return "";
    }

    url_without_suffix = url.substr(0, suffix_pos);
    return url.substr(suffix_pos);
}

static void cleanup_downloaded_files(const std::vector<std::string> & paths) {
    for (const auto & path : paths) {
        if (path.empty()) {
            continue;
        }
        if (unlink(path.c_str()) == 0) {
            log_to_file(std::string("download: removed partial file ") + path, GGML_LOG_LEVEL_WARN);
        }
    }
}

static bool download_file_with_curl(
        const std::string & url,
        const std::string & path,
        ProgressData * progress_data,
        std::string & error_message) {
    CURL * curl = curl_easy_init();
    if (!curl) {
        error_message = "curl init failed";
        log_to_file("download: curl init failed", GGML_LOG_LEVEL_ERROR);
        return false;
    }

    std::ofstream ofs(path, std::ios::binary | std::ios::trunc);
    if (!ofs) {
        curl_easy_cleanup(curl);
        error_message = "file open failed";
        log_to_file(std::string("download: file open failed path=") + path, GGML_LOG_LEVEL_ERROR);
        return false;
    }

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_data);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &ofs);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 1L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 2L);

    std::string ca_bundle_path;
    {
        std::lock_guard<std::mutex> lock(g_download_ca_bundle_mutex);
        ca_bundle_path = g_download_ca_bundle_path;
    }
    if (!ca_bundle_path.empty()) {
        curl_easy_setopt(curl, CURLOPT_CAINFO, ca_bundle_path.c_str());
        log_to_file(std::string("download: using CA bundle path=") + ca_bundle_path);
    } else {
        log_to_file("download: no CA bundle configured; using libcurl defaults", GGML_LOG_LEVEL_WARN);
    }

    if (progress_data) {
        progress_data->last_percent = -1;
        curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, xferinfo);
        curl_easy_setopt(curl, CURLOPT_XFERINFODATA, progress_data);
        curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
    } else {
        curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 1L);
    }

    curl_easy_setopt(curl, CURLOPT_USERAGENT,
        "Mozilla/5.0 (Linux; Android 14; Mobile) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Mobile Safari/537.36");

    const CURLcode res = curl_easy_perform(curl);

    long http_code = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http_code);

    ofs.flush();
    ofs.close();
    curl_easy_cleanup(curl);

    if (res != CURLE_OK) {
        std::ostringstream ss;
        ss << "curl download failed";
        if (http_code > 0) {
            ss << " (HTTP " << http_code << ")";
        }
        ss << ": " << curl_easy_strerror(res);
        error_message = ss.str();
        log_to_file(std::string("download: ") + ss.str(), GGML_LOG_LEVEL_ERROR);
        unlink(path.c_str());
        return false;
    }

    if (!is_existing_nonempty_file(path)) {
        error_message = "downloaded file is empty";
        log_to_file(std::string("download: empty file after download path=") + path, GGML_LOG_LEVEL_ERROR);
        unlink(path.c_str());
        return false;
    }

    return true;
}

static bool read_split_count_from_gguf(
        const std::string & path,
        int & n_split,
        std::string & error_message) {
    n_split = 0;

    struct gguf_init_params gguf_params = {
            /*.no_alloc = */ true,
            /*.ctx      = */ nullptr,
    };

    gguf_context * ctx_gguf = gguf_init_from_file(path.c_str(), gguf_params);
    if (!ctx_gguf) {
        error_message = std::string("failed to load GGUF metadata from ") + path;
        log_to_file(std::string("download: ") + error_message, GGML_LOG_LEVEL_ERROR);
        return false;
    }

    const int64_t key_n_split = gguf_find_key(ctx_gguf, "split.count");
    if (key_n_split >= 0) {
        n_split = gguf_get_val_u16(ctx_gguf, key_n_split);
    }

    gguf_free(ctx_gguf);
    return true;
}

static bool find_missing_split_shard_path(
        const std::string & model_path,
        std::string & missing_path) {
    std::string split_prefix;
    int split_no = 0;
    int split_count = 0;
    if (!parse_split_file_info(model_path, split_prefix, split_no, split_count)) {
        missing_path.clear();
        return false;
    }

    for (int idx = 0; idx < split_count; ++idx) {
        std::vector<char> split_path_buf(split_prefix.size() + 32, '\0');
        if (!llama_split_path(split_path_buf.data(), split_path_buf.size(), split_prefix.c_str(), idx, split_count)) {
            missing_path = model_path;
            return true;
        }
        const std::string shard_path(split_path_buf.data());
        if (!is_existing_nonempty_file(shard_path)) {
            missing_path = shard_path;
            return true;
        }
    }

    missing_path.clear();
    return false;
}

static bool download_model_with_splits(
        const std::string & model_url,
        const std::string & model_path,
        ProgressData * progress_data,
        std::string & error_message) {
    std::vector<std::string> newly_downloaded_paths;
    bool allow_progress = progress_data != nullptr;

    if (!is_existing_nonempty_file(model_path)) {
        log_to_file(std::string("download: fetching primary shard url=") + model_url + " path=" + model_path);
        if (!download_file_with_curl(model_url, model_path, allow_progress ? progress_data : nullptr, error_message)) {
            cleanup_downloaded_files(newly_downloaded_paths);
            return false;
        }
        newly_downloaded_paths.push_back(model_path);
        allow_progress = false;
    } else {
        log_to_file(std::string("download: primary shard already present, probing metadata path=") + model_path);
    }

    int n_split = 0;
    if (!read_split_count_from_gguf(model_path, n_split, error_message)) {
        cleanup_downloaded_files(newly_downloaded_paths);
        return false;
    }

    if (n_split <= 1) {
        return true;
    }

    std::string path_prefix;
    int split_no = 0;
    int split_count_from_name = 0;
    if (!parse_split_file_info(model_path, path_prefix, split_no, split_count_from_name)) {
        error_message = std::string("unexpected split model file name: ") + model_path;
        log_to_file(std::string("download: ") + error_message, GGML_LOG_LEVEL_ERROR);
        cleanup_downloaded_files(newly_downloaded_paths);
        return false;
    }
    if (split_count_from_name != n_split) {
        error_message = std::string("split count mismatch between file name and GGUF metadata: ") + model_path;
        log_to_file(std::string("download: ") + error_message, GGML_LOG_LEVEL_ERROR);
        cleanup_downloaded_files(newly_downloaded_paths);
        return false;
    }

    std::string url_without_suffix;
    const std::string url_suffix = split_url_suffix(model_url, url_without_suffix);
    std::string url_prefix;
    int url_split_no = 0;
    int url_split_count = 0;
    if (!parse_split_file_info(url_without_suffix, url_prefix, url_split_no, url_split_count)) {
        error_message = std::string("unexpected split model url: ") + model_url;
        log_to_file(std::string("download: ") + error_message, GGML_LOG_LEVEL_ERROR);
        cleanup_downloaded_files(newly_downloaded_paths);
        return false;
    }
    if (url_split_no != split_no || url_split_count != n_split) {
        error_message = std::string("split model url does not match local shard naming: ") + model_url;
        log_to_file(std::string("download: ") + error_message, GGML_LOG_LEVEL_ERROR);
        cleanup_downloaded_files(newly_downloaded_paths);
        return false;
    }

    {
        std::ostringstream ss;
        ss << "download: split GGUF detected split_no=" << split_no << " split_count=" << n_split;
        log_to_file(ss.str());
    }

    for (int idx = 0; idx < n_split; ++idx) {
        std::vector<char> split_path_buf(path_prefix.size() + 32, '\0');
        std::vector<char> split_url_buf(url_prefix.size() + 32, '\0');
        if (!llama_split_path(split_path_buf.data(), split_path_buf.size(), path_prefix.c_str(), idx, n_split) ||
            !llama_split_path(split_url_buf.data(), split_url_buf.size(), url_prefix.c_str(), idx, n_split)) {
            error_message = "failed to build split file path";
            log_to_file(std::string("download: ") + error_message, GGML_LOG_LEVEL_ERROR);
            cleanup_downloaded_files(newly_downloaded_paths);
            return false;
        }

        const std::string split_path(split_path_buf.data());
        if (is_existing_nonempty_file(split_path)) {
            continue;
        }

        const std::string split_url = std::string(split_url_buf.data()) + url_suffix;
        log_to_file(std::string("download: fetching split shard url=") + split_url + " path=" + split_path);
        if (!download_file_with_curl(split_url, split_path, allow_progress ? progress_data : nullptr, error_message)) {
            cleanup_downloaded_files(newly_downloaded_paths);
            return false;
        }
        newly_downloaded_paths.push_back(split_path);
        allow_progress = false;
    }

    std::string missing_split_path;
    if (find_missing_split_shard_path(model_path, missing_split_path)) {
        error_message = std::string("missing split shard after download: ") + missing_split_path;
        log_to_file(std::string("download: ") + error_message, GGML_LOG_LEVEL_ERROR);
        cleanup_downloaded_files(newly_downloaded_paths);
        return false;
    }

    return true;
}

// ---------------- 解放 ----------------
static void llama_jni_free() {
    std::lock_guard<std::mutex> lock(g_mutex);

    log_to_file("llama_jni_free: freeing resources (explicit)");

    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
        g_current_mmproj_path.clear();
        g_supports_vision = false;
        g_supports_audio = false;
        log_to_file("Multimodal context freed");
    }
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
    g_current_mmproj_path.clear();
    g_supports_vision = false;
    g_supports_audio = false;

    if (g_backend_initialized) {
        log_to_file("Backend retained for process reuse");
    }
    trim_native_allocator("llama_jni_free");
}

// ---------------- JNI: setLogPath ----------------
extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_llama_LlamaNative_setLogPath(
        JNIEnv *env, jobject, jstring jLogPath) {

    std::string path = jstring_to_std(env, jLogPath);
    ensure_fatal_signal_handlers_installed();
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (path == g_log_path && g_log_ofs.is_open() &&
                g_signal_log_fd >= 0 && g_signal_crash_fd >= 0) {
            return;
        }
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

        if (!g_log_path.empty()) {
            const int signal_log_fd = open(g_log_path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
            if (signal_log_fd >= 0) {
                replace_signal_fd(&g_signal_log_fd, signal_log_fd);
            } else {
                LOGE("Failed to open signal log file: %s", g_log_path.c_str());
            }

            const std::string crash_path = derive_crash_log_path(g_log_path);
            if (!crash_path.empty()) {
                const int signal_crash_fd = open(crash_path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
                if (signal_crash_fd >= 0) {
                    replace_signal_fd(&g_signal_crash_fd, signal_crash_fd);
                    if (g_log_ofs) {
                        g_log_ofs << current_time_str() << " [JNI] Native crash log path: " << crash_path << std::endl;
                        g_log_ofs.flush();
                    }
                } else {
                    LOGE("Failed to open native crash log file: %s", crash_path.c_str());
                }
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

extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_llama_LlamaNative_setDownloadCaBundlePath(
        JNIEnv * env, jobject, jstring jpath) {
    const std::string path = jpath ? jstring_to_std(env, jpath) : "";
    {
        std::lock_guard<std::mutex> lock(g_download_ca_bundle_mutex);
        g_download_ca_bundle_path = path;
    }

    if (path.empty()) {
        log_to_file("download: cleared CA bundle path", GGML_LOG_LEVEL_WARN);
    } else {
        log_to_file(std::string("download: configured CA bundle path=") + path);
    }
}

// ---------------- JNI: download ----------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_download(
        JNIEnv* env,
        jobject thiz,
        jstring jurl,
        jstring jpath) {
    ensure_fatal_signal_handlers_installed();

    // Ensure we have a stored JavaVM for callbacks even if init() has not been called yet
    if (!g_jvm) {
        if (env->GetJavaVM(&g_jvm) != JNI_OK) {
            g_jvm = nullptr;
            log_to_file("download: GetJavaVM failed", GGML_LOG_LEVEL_WARN);
        } else {
            log_to_file("download: JavaVM stored");
        }
    }

    const char* url_chars  = env->GetStringUTFChars(jurl,  nullptr);
    const char* path_chars = env->GetStringUTFChars(jpath, nullptr);

    if (!url_chars || !path_chars) {
        if (url_chars)  env->ReleaseStringUTFChars(jurl, url_chars);
        if (path_chars) env->ReleaseStringUTFChars(jpath, path_chars);
        log_to_file("download: invalid args", GGML_LOG_LEVEL_ERROR);
        return new_java_string_utf8(env, "invalid args");
    }

    const std::string url(url_chars);
    const std::string path(path_chars);
    env->ReleaseStringUTFChars(jurl, url_chars);
    env->ReleaseStringUTFChars(jpath, path_chars);

    {
        std::ostringstream ss;
        ss << "download: start url=" << url << " path=" << path;
        log_to_file(ss.str());
    }

    ProgressData pd;
    pd.last_percent = -1;
    pd.thiz_global = env->NewGlobalRef(thiz);
    pd.onProgressMethod = nullptr;

    jclass cls = env->GetObjectClass(thiz);
    if (cls) {
        pd.onProgressMethod = env->GetMethodID(cls, "onDownloadProgress", "(I)V");
    }

    std::string error_message;
    const bool ok = download_model_with_splits(url, path, &pd, error_message);
    if (pd.thiz_global) env->DeleteGlobalRef(pd.thiz_global);
    if (!ok) {
        if (error_message.empty()) {
            error_message = "download failed";
        }
        return new_java_string_utf8(env, error_message);
    }

    log_to_file("download: ok");
    return new_java_string_utf8(env, "ok");
}

// ---------------- JNI: init ----------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_init(
        JNIEnv *env, jobject,
        jstring jModelPath
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_fatal_signal_handlers_installed();

    auto clear_partial_init = []() {
        if (g_mtmd) {
            mtmd_free(g_mtmd);
            g_mtmd = nullptr;
        }
        if (g_ctx) {
            llama_free(g_ctx);
            g_ctx = nullptr;
        }
        if (g_model) {
            llama_model_free(g_model);
            g_model = nullptr;
        }
        g_current_model_path.clear();
        g_current_mmproj_path.clear();
        g_supports_vision = false;
        g_supports_audio = false;
    };

    try {
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
            return new_java_string_utf8(env, "ok");
        }

        // Defensively free existing resources before loading a new model
        if (g_mtmd) {
            log_to_file("init: freeing existing multimodal context before re-init");
            mtmd_free(g_mtmd);
            g_mtmd = nullptr;
        }
        g_current_mmproj_path.clear();
        g_supports_vision = false;
        g_supports_audio = false;
        if (g_ctx) {
            log_to_file("init: freeing existing context before re-init");
            llama_free(g_ctx);
            g_ctx = nullptr;
        }
        if (g_model) {
            log_to_file("init: freeing existing model before re-init");
            llama_model_free(g_model);
            g_model = nullptr;
        }
        if (!g_current_model_path.empty()) {
            log_to_file("init: clearing previous model path");
            g_current_model_path.clear();
        }

        {
            std::ifstream ifs(model_path, std::ios::binary | std::ios::ate);
            if (!ifs) {
                std::ostringstream ss;
                ss << "init: model file cannot be opened: " << model_path
                   << " errno=" << errno << " strerror=" << std::strerror(errno);
                log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
                return new_java_string_utf8(env, "model file open failed");
            } else {
                auto sz = ifs.tellg();
                std::ostringstream ss;
                ss << "init: model file exists, size=" << sz << " bytes";
                log_to_file(ss.str());
                ifs.close();
            }
        }

        {
            std::string missing_split_path;
            if (find_missing_split_shard_path(model_path, missing_split_path)) {
                std::ostringstream ss;
                ss << "init: missing split shard: " << missing_split_path;
                log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
                return new_java_string_utf8(env, ss.str());
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

        ensure_backend_initialized_locked("init");
        log_requested_load_params("init");
        const size_t backend_count = ggml_backend_reg_count();
        if (backend_count == 0) {
            return new_java_string_utf8(env, "no ggml backends registered");
        }

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = g_n_gpu_layers;
        mparams.use_mmap = true;
        mparams.use_mlock = false;

        {
            using namespace std::chrono;
            auto t0 = high_resolution_clock::now();
            g_model = llama_model_load_from_file(model_path.c_str(), mparams);
            auto t1 = high_resolution_clock::now();
            auto ms = duration_cast<milliseconds>(t1 - t0).count();

            std::ostringstream ss;
            if (!g_model) {
                const std::string hint = build_model_load_failure_message("init");
                ss << hint << " after "
                   << ms << " ms. path_len=" << model_path.size();
                log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
                return new_java_string_utf8(env, hint);
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
                const std::string hint = build_context_load_failure_message("init");
                ss << hint << " after "
                   << ms << " ms";
                log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
                return new_java_string_utf8(env, hint);
            } else {
                ss << "init: context created successfully in " << ms << " ms";
                log_to_file(ss.str());
            }
        }

        g_current_model_path = model_path;
        g_current_mmproj_path = initialize_optional_multimodal_support_locked(model_path, "", "init");
        log_to_file("init: initialization complete");

        return new_java_string_utf8(env, "ok");
    } catch (const std::exception & e) {
        std::ostringstream ss;
        ss << "init: exception: " << e.what();
        log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
        clear_partial_init();
        return new_java_string_utf8(env, "init exception");
    } catch (...) {
        log_to_file("init: unknown native exception", GGML_LOG_LEVEL_ERROR);
        clear_partial_init();
        return new_java_string_utf8(env, "init unknown exception");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_initWithMmproj(
        JNIEnv *env, jobject,
        jstring jModelPath,
        jstring jMmprojPath
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_fatal_signal_handlers_installed();

    auto clear_partial_init = []() {
        if (g_mtmd) {
            mtmd_free(g_mtmd);
            g_mtmd = nullptr;
        }
        if (g_ctx) {
            llama_free(g_ctx);
            g_ctx = nullptr;
        }
        if (g_model) {
            llama_model_free(g_model);
            g_model = nullptr;
        }
        g_current_model_path.clear();
        g_current_mmproj_path.clear();
        g_supports_vision = false;
        g_supports_audio = false;
    };

    try {
        log_to_file("initWithMmproj: start");

        llama_log_set(llama_log_callback, nullptr);
        mtmd_helper_log_set(llama_log_callback, nullptr);
        log_to_file("initWithMmproj: llama/mtmd log callbacks registered");

        std::string model_path = jstring_to_std(env, jModelPath);
        std::string mmproj_path = jMmprojPath ? jstring_to_std(env, jMmprojPath) : "";

        {
            std::ostringstream ss;
            ss << "initWithMmproj: model_path=" << model_path
               << " mmproj_path=" << (mmproj_path.empty() ? "<none>" : mmproj_path);
            log_to_file(ss.str());
        }

        if (!g_current_model_path.empty()
                && g_current_model_path == model_path
                && g_model && g_ctx
                && ((mmproj_path.empty() && (g_current_mmproj_path.empty() || g_mtmd != nullptr))
                || g_current_mmproj_path == mmproj_path)) {
            std::ostringstream ss;
            ss << "initWithMmproj: model already initialized at path=" << model_path
               << " mmproj=" << (g_current_mmproj_path.empty() ? "<none>" : g_current_mmproj_path)
               << "; skipping init";
            log_to_file(ss.str());
            return new_java_string_utf8(env, "ok");
        }

        if (g_mtmd) {
            log_to_file("initWithMmproj: freeing existing multimodal context before re-init");
            mtmd_free(g_mtmd);
            g_mtmd = nullptr;
        }
        g_current_mmproj_path.clear();
        g_supports_vision = false;
        g_supports_audio = false;
        if (g_ctx) {
            log_to_file("initWithMmproj: freeing existing context before re-init");
            llama_free(g_ctx);
            g_ctx = nullptr;
        }
        if (g_model) {
            log_to_file("initWithMmproj: freeing existing model before re-init");
            llama_model_free(g_model);
            g_model = nullptr;
        }
        if (!g_current_model_path.empty()) {
            log_to_file("initWithMmproj: clearing previous model path");
            g_current_model_path.clear();
        }

        {
            std::ifstream ifs(model_path, std::ios::binary | std::ios::ate);
            if (!ifs) {
                std::ostringstream ss;
                ss << "initWithMmproj: model file cannot be opened: " << model_path
                   << " errno=" << errno << " strerror=" << std::strerror(errno);
                log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
                return new_java_string_utf8(env, "model file open failed");
            } else {
                auto sz = ifs.tellg();
                std::ostringstream ss;
                ss << "initWithMmproj: model file exists, size=" << sz << " bytes";
                log_to_file(ss.str());
                ifs.close();
            }
        }

        if (!mmproj_path.empty()) {
            std::ifstream ifs(mmproj_path, std::ios::binary | std::ios::ate);
            if (!ifs) {
                std::ostringstream ss;
                ss << "initWithMmproj: mmproj file cannot be opened: " << mmproj_path
                   << " errno=" << errno << " strerror=" << std::strerror(errno);
                log_to_file(ss.str(), GGML_LOG_LEVEL_WARN);
                mmproj_path.clear();
            } else {
                auto sz = ifs.tellg();
                std::ostringstream ss;
                ss << "initWithMmproj: mmproj file exists, size=" << sz << " bytes";
                log_to_file(ss.str());
                ifs.close();
            }
        }

        {
            std::string missing_split_path;
            if (find_missing_split_shard_path(model_path, missing_split_path)) {
                std::ostringstream ss;
                ss << "initWithMmproj: missing split shard: " << missing_split_path;
                log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
                return new_java_string_utf8(env, ss.str());
            }
        }

        if (env->GetJavaVM(&g_jvm) != JNI_OK) {
            g_jvm = nullptr;
            log_to_file("initWithMmproj: GetJavaVM failed", GGML_LOG_LEVEL_WARN);
        } else {
            log_to_file("initWithMmproj: JavaVM stored");
        }

        ensure_backend_initialized_locked("initWithMmproj");
        log_requested_load_params("initWithMmproj");
        const size_t backend_count = ggml_backend_reg_count();
        if (backend_count == 0) {
            return new_java_string_utf8(env, "no ggml backends registered");
        }

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = g_n_gpu_layers;
        mparams.use_mmap = true;
        mparams.use_mlock = false;

        {
            using namespace std::chrono;
            auto t0 = high_resolution_clock::now();
            g_model = llama_model_load_from_file(model_path.c_str(), mparams);
            auto t1 = high_resolution_clock::now();
            auto ms = duration_cast<milliseconds>(t1 - t0).count();

            std::ostringstream ss;
            if (!g_model) {
                const std::string hint = build_model_load_failure_message("initWithMmproj");
                ss << hint << " after "
                   << ms << " ms. path_len=" << model_path.size();
                log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
                return new_java_string_utf8(env, hint);
            } else {
                ss << "initWithMmproj: model loaded successfully in " << ms << " ms";
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
                const std::string hint = build_context_load_failure_message("initWithMmproj");
                ss << hint << " after "
                   << ms << " ms";
                log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
                return new_java_string_utf8(env, hint);
            } else {
                ss << "initWithMmproj: context created successfully in " << ms << " ms";
                log_to_file(ss.str());
            }
        }

        const std::string selected_mmproj_path = initialize_optional_multimodal_support_locked(
                model_path,
                mmproj_path,
                "initWithMmproj");

        g_current_model_path = model_path;
        g_current_mmproj_path = selected_mmproj_path;
        log_to_file("initWithMmproj: initialization complete");

        return new_java_string_utf8(env, "ok");
    } catch (const std::exception & e) {
        std::ostringstream ss;
        ss << "initWithMmproj: exception: " << e.what();
        log_to_file(ss.str(), GGML_LOG_LEVEL_ERROR);
        clear_partial_init();
        return new_java_string_utf8(env, "init exception");
    } catch (...) {
        log_to_file("initWithMmproj: unknown native exception", GGML_LOG_LEVEL_ERROR);
        clear_partial_init();
        return new_java_string_utf8(env, "init unknown exception");
    }
}

// ---------------- JNI: setLoadParameters ----------------
extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_llama_LlamaNative_setLoadParameters(
        JNIEnv *, jobject,
        jint nCtx, jint nThreads, jint nBatch,
        jfloat temp, jfloat topP, jint topK, jint nGpuLayers
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    g_n_ctx = nCtx > 0 ? nCtx : 1;
    g_n_threads = nThreads > 0 ? nThreads : 1;
    g_n_batch = nBatch > 0 ? nBatch : 1;
    g_temp = temp;
    g_top_p = topP;
    g_top_k = topK;
    g_n_gpu_layers = nGpuLayers;

    {
        std::ostringstream ss;
        ss << "setLoadParameters: n_ctx=" << g_n_ctx
           << " n_threads=" << g_n_threads
           << " n_batch=" << g_n_batch
           << " temp=" << g_temp
           << " top_p=" << g_top_p
           << " top_k=" << g_top_k
           << " n_gpu_layers=" << g_n_gpu_layers;
        log_to_file(ss.str());
    }
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
    std::lock_guard<std::mutex> lock(g_mutex);
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

// ---------------- JNI: cancelGeneration ----------------
extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_llama_LlamaNative_cancelGeneration(
        JNIEnv *, jobject) {
    g_cancel_generation.store(true);
    log_to_file("cancelGeneration: cancellation requested", GGML_LOG_LEVEL_WARN);
}

static void notify_token_error(const char * errmsg) {
    if (!errmsg || !g_jvm || !g_token_listener || !g_token_onError) {
        return;
    }
    JNIEnv * env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        } else {
            env = nullptr;
        }
    }
    if (env) {
        jstring jerr = new_java_string_utf8(env, errmsg);
        if (!jerr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            jerr = new_java_string_utf8(env, "unknown error");
        }
        if (jerr) {
            env->CallVoidMethod(g_token_listener, g_token_onError, jerr);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(jerr);
        }
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static void notify_token_delta(const std::string & delta) {
    if (delta.empty() || !g_jvm || !g_token_listener || !g_token_onToken) {
        return;
    }
    JNIEnv * env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        } else {
            env = nullptr;
        }
    }
    if (env) {
        jstring jdelta = new_java_string_utf8(env, delta);
        if (jdelta) {
            env->CallVoidMethod(g_token_listener, g_token_onToken, jdelta);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(jdelta);
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static void notify_token_complete() {
    if (!g_jvm || !g_token_listener || !g_token_onComplete) {
        return;
    }
    JNIEnv * env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        } else {
            env = nullptr;
        }
    }
    if (env) {
        env->CallVoidMethod(g_token_listener, g_token_onComplete);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static bool prefill_text_prompt_locked(
        const llama_vocab * vocab,
        const std::string & prompt,
        size_t & n_prompt_tokens,
        llama_pos & n_past,
        std::string & error_message) {
    std::vector<llama_token> tokens(g_n_ctx);
    int32_t n_tokens = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int>(prompt.size()),
            tokens.data(),
            static_cast<int>(tokens.size()),
            false,
            true
    );

    if (n_tokens <= 0) {
        error_message = "tokenize failed";
        return false;
    }
    if (n_tokens >= g_n_ctx) {
        error_message = "token count exceeds context";
        return false;
    }

    tokens.resize(n_tokens);
    n_prompt_tokens = static_cast<size_t>(n_tokens);
    n_past = n_tokens;

    reusable_token_batch prompt_batch(std::min(g_n_batch, n_tokens));
    if (!prompt_batch.valid()) {
        error_message = "failed to allocate prompt batch";
        return false;
    }

    for (int i = 0; i < n_tokens; i += g_n_batch) {
        if (g_cancel_generation.load()) {
            error_message = "generation cancelled";
            return false;
        }

        const int batch_size = std::min(g_n_batch, n_tokens - i);
        prompt_batch.batch.n_tokens = batch_size;

        for (int j = 0; j < batch_size; ++j) {
            prompt_batch.set_token(j, tokens[i + j], i + j, i + j == n_tokens - 1);
        }

        const int rc = llama_decode(g_ctx, prompt_batch.batch);
        if (rc != 0) {
            error_message = "decode failed (prompt)";
            return false;
        }
    }

    return true;
}

static bool prefill_multimodal_prompt_locked(
        const std::string & prompt,
        const std::vector<std::vector<uint8_t>> & media_files,
        size_t & n_prompt_tokens,
        llama_pos & n_past,
        std::string & error_message) {
    if (!g_mtmd) {
        error_message = "multimodal projector not initialized";
        return false;
    }

    std::vector<mtmd_bitmap *> bitmaps;
    bitmaps.reserve(media_files.size());

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (!chunks) {
        error_message = "failed to allocate multimodal chunks";
        return false;
    }

    auto cleanup = [&]() {
        for (mtmd_bitmap * bitmap : bitmaps) {
            if (bitmap) {
                mtmd_bitmap_free(bitmap);
            }
        }
        mtmd_input_chunks_free(chunks);
    };

    for (const auto & file : media_files) {
        mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(g_mtmd, file.data(), file.size());
        if (!bitmap) {
            error_message = "failed to decode multimodal input";
            cleanup();
            return false;
        }
        bitmaps.push_back(bitmap);
    }

    std::vector<const mtmd_bitmap *> bitmap_ptrs(bitmaps.begin(), bitmaps.end());
    mtmd_input_text input_text = {
            prompt.c_str(),
            true,
            true
    };

    const int32_t tokenized = mtmd_tokenize(
            g_mtmd,
            chunks,
            &input_text,
            bitmap_ptrs.data(),
            bitmap_ptrs.size()
    );
    if (tokenized != 0) {
        error_message = "failed to tokenize multimodal prompt";
        cleanup();
        return false;
    }

    n_prompt_tokens = mtmd_helper_get_n_tokens(chunks);
    const int32_t eval_rc = mtmd_helper_eval_chunks(
            g_mtmd,
            g_ctx,
            chunks,
            0,
            0,
            g_n_batch,
            true,
            &n_past
    );
    if (eval_rc != 0) {
        error_message = "decode failed (multimodal prompt)";
        cleanup();
        return false;
    }

    cleanup();
    return true;
}

static jstring generate_locked(
        JNIEnv * env,
        const std::string & prompt,
        const std::vector<std::vector<uint8_t>> * media_files) {
    if (!g_ctx || !g_model) {
        log_to_file("generate: not initialized", GGML_LOG_LEVEL_ERROR);
        notify_token_error("not initialized");
        return new_java_string_utf8(env, "not initialized");
    }

    g_cancel_generation.store(false);

    {
        std::ostringstream ss;
        ss << "generate: prompt_len=" << prompt.size()
           << " media_count=" << (media_files ? media_files->size() : 0);
        log_to_file(ss.str());
    }
    if (is_max_debug_mode()) {
        log_to_file(std::string("generate: prompt=\n") + prompt, GGML_LOG_LEVEL_DEBUG);
    }

    const int max_tokens = 1024;
    reset_generation_state_locked();

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    size_t n_prompt_tokens = 0;
    llama_pos n_past = 0;
    std::string prefill_error;
    reusable_token_batch generation_batch(1);
    if (!generation_batch.valid()) {
        const char * errmsg = "failed to allocate generation batch";
        log_to_file(std::string("generate: ") + errmsg, GGML_LOG_LEVEL_ERROR);
        notify_token_error(errmsg);
        return new_java_string_utf8(env, errmsg);
    }

    struct generation_reset_guard {
        ~generation_reset_guard() {
            reset_generation_state_locked();
        }
    } reset_guard;

    const bool has_media = media_files != nullptr && !media_files->empty();
    const bool prefill_ok = has_media
            ? prefill_multimodal_prompt_locked(prompt, *media_files, n_prompt_tokens, n_past, prefill_error)
            : prefill_text_prompt_locked(vocab, prompt, n_prompt_tokens, n_past, prefill_error);

    if (!prefill_ok) {
        log_to_file(std::string("generate: prompt prefill failed: ") + prefill_error, GGML_LOG_LEVEL_ERROR);
        notify_token_error(prefill_error.c_str());
        return new_java_string_utf8(env, prefill_error);
    }

    if (n_past >= g_n_ctx) {
        const char * errmsg = "prompt exceeds context";
        log_to_file(std::string("generate: ") + errmsg, GGML_LOG_LEVEL_WARN);
        notify_token_error(errmsg);
        return new_java_string_utf8(env, errmsg);
    }

    const llama_pos generation_base_pos = n_past;
    const int n_vocab = llama_vocab_n_tokens(vocab);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);

    if (g_penalty_last_n > 0 && (g_penalty_repeat != 1.0f || g_penalty_freq != 0.0f || g_penalty_present != 0.0f)) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                g_penalty_last_n, g_penalty_repeat, g_penalty_freq, g_penalty_present));
    }

    if (g_dry_multiplier > 0.0f) {
        std::vector<std::string> breaker_strings;
        std::vector<const char *> breaker_ptrs;
        std::string temp = g_dry_sequence_breakers;
        size_t pos = 0;
        while ((pos = temp.find(',')) != std::string::npos) {
            std::string token = temp.substr(0, pos);
            if (!token.empty()) {
                breaker_strings.push_back(process_escape_sequences(token));
            }
            temp.erase(0, pos + 1);
        }
        if (!temp.empty()) {
            breaker_strings.push_back(process_escape_sequences(temp));
        }
        for (const auto & s : breaker_strings) {
            breaker_ptrs.push_back(s.c_str());
        }
        if (!breaker_ptrs.empty()) {
            llama_sampler_chain_add(smpl, llama_sampler_init_dry(
                    vocab, g_n_ctx, g_dry_multiplier, g_dry_base,
                    g_dry_allowed_length, g_dry_penalty_last_n,
                    breaker_ptrs.data(), breaker_ptrs.size()));
        }
    }

    if (g_top_n_sigma > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_n_sigma(g_top_n_sigma));
    }
    if (g_top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(g_top_k));
    }
    if (g_typical_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_typical(g_typical_p, 1));
    }
    if (g_top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(g_top_p, 1));
    }
    if (g_min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(g_min_p, 1));
    }
    if (g_xtc_probability > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_xtc(
                g_xtc_probability, g_xtc_threshold, 1, LLAMA_DEFAULT_SEED));
    }
    if (g_dynatemp_range > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp_ext(
                g_temp, g_dynatemp_range, g_dynatemp_exponent));
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(g_temp));
    }
    if (g_mirostat == 1) {
        llama_sampler_chain_add(smpl, llama_sampler_init_mirostat(
                n_vocab, LLAMA_DEFAULT_SEED, g_mirostat_tau, g_mirostat_eta, 100));
    } else if (g_mirostat == 2) {
        llama_sampler_chain_add(smpl, llama_sampler_init_mirostat_v2(
                LLAMA_DEFAULT_SEED, g_mirostat_tau, g_mirostat_eta));
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string output;
    output.reserve(max_tokens * 4);
    std::vector<llama_token> out_tokens;
    out_tokens.reserve(max_tokens);
    std::string prev_text;

    for (int i = 0; i < max_tokens; ++i) {
        if (g_cancel_generation.load()) {
            break;
        }

        const llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, id);
        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        out_tokens.push_back(id);
        if (generation_base_pos + static_cast<llama_pos>(out_tokens.size()) >= g_n_ctx - 32) {
            log_to_file("generate: reached ctx safety limit, stopping early");
            break;
        }

        std::string full;
        int n_chars = detokenize_with_resize(vocab, out_tokens, full);
        if (n_chars > 0) {
            size_t safe_len = validate_utf8(full);
            if (safe_len < full.size()) {
                full.resize(safe_len);
            }

            bool stop_hit = false;
            size_t stop_pos = std::string::npos;
            std::string stop_seq;
            if (find_stop_sequence(full, &stop_pos, &stop_seq)) {
                if (stop_pos < full.size()) {
                    full.resize(stop_pos);
                }
                stop_hit = true;
            }

            std::string delta;
            if (full.size() > prev_text.size()) {
                delta = full.substr(prev_text.size());
                output += delta;
            }
            if (stop_hit) {
                output = full;
            }

            if (!delta.empty()) {
                notify_token_delta(delta);
            }

            prev_text = full;
            if (stop_hit) {
                break;
            }
        }

        generation_batch.batch.n_tokens = 1;
        generation_batch.set_token(0, id, generation_base_pos + i, true);

        const int rc_step = llama_decode(g_ctx, generation_batch.batch);
        if (rc_step != 0) {
            const char * errmsg = "decode failed (generation)";
            log_to_file(std::string("generate: ") + errmsg, GGML_LOG_LEVEL_ERROR);
            notify_token_error(errmsg);
            llama_sampler_free(smpl);
            return new_java_string_utf8(env, errmsg);
        }
    }

    const bool was_cancelled = g_cancel_generation.load();
    if (!was_cancelled) {
        notify_token_complete();
    } else {
        log_to_file("generate: completed with cancellation", GGML_LOG_LEVEL_WARN);
    }

    llama_sampler_free(smpl);

    size_t safe_len = validate_utf8(output);
    if (safe_len < output.size()) {
        output.resize(safe_len);
    }

    if (is_max_debug_mode()) {
        log_to_file(std::string("generate: output=\n") + output, GGML_LOG_LEVEL_DEBUG);
    }

    return new_java_string_utf8(env, output);
}

static jstring generate_openai_chat_completion_locked(
        JNIEnv * env,
        const std::string & messages_json_text,
        const std::string & tools_json_text,
        const std::string & chat_template_override,
        const std::string & tool_choice_json_text,
        bool parallel_tool_calls,
        bool enable_thinking,
        const std::vector<std::vector<uint8_t>> * media_files) {
    auto build_error = [&](const std::string & message) -> jstring {
        log_to_file(std::string("generateOpenAiChatCompletion: ") + message, GGML_LOG_LEVEL_ERROR);
        json error_json = {
                {"error", message},
        };
        return new_java_string_utf8(env, error_json.dump());
    };

    if (!g_ctx || !g_model) {
        return build_error("not initialized");
    }

    g_cancel_generation.store(false);

    try {
        json messages_json = json::parse(messages_json_text.empty() ? "[]" : messages_json_text);
        json tools_json = tools_json_text.empty() ? json::array() : json::parse(tools_json_text);
        json tool_choice_json = tool_choice_json_text.empty() ? json() : json::parse(tool_choice_json_text);

        auto messages = common_chat_msgs_parse_oaicompat(messages_json);
        auto tools = common_chat_tools_parse_oaicompat(tools_json);
        common_chat_tool_choice tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;

        if (!tool_choice_json.is_null()) {
            if (tool_choice_json.is_string()) {
                tool_choice = common_chat_tool_choice_parse_oaicompat(tool_choice_json.get<std::string>());
            } else if (tool_choice_json.is_object()) {
                if (tool_choice_json.value("type", std::string()) != "function") {
                    throw std::runtime_error("unsupported tool_choice object type");
                }
                const auto & function = tool_choice_json.at("function");
                const std::string forced_tool_name = function.at("name").get<std::string>();
                std::vector<common_chat_tool> filtered_tools;
                for (const auto & tool : tools) {
                    if (tool.name == forced_tool_name) {
                        filtered_tools.push_back(tool);
                    }
                }
                if (filtered_tools.empty()) {
                    throw std::runtime_error("tool_choice function not found in tools");
                }
                tools = std::move(filtered_tools);
                tool_choice = COMMON_CHAT_TOOL_CHOICE_REQUIRED;
            } else {
                throw std::runtime_error("tool_choice must be a string or object");
            }
        }

        common_chat_templates_inputs inputs;
        inputs.messages = std::move(messages);
        inputs.tools = std::move(tools);
        inputs.tool_choice = tool_choice;
        inputs.parallel_tool_calls = parallel_tool_calls;
        inputs.enable_thinking = enable_thinking;
        if (enable_thinking) {
            inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        }
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;

        auto chat_templates = common_chat_templates_init(g_model, chat_template_override);
        auto chat_params = common_chat_templates_apply(chat_templates.get(), inputs);

        common_chat_parser_params parser_params(chat_params);
        parser_params.parse_tool_calls = true;
        if (!chat_params.parser.empty()) {
            parser_params.parser.load(chat_params.parser);
        }

        const std::string & prompt = chat_params.prompt;
        const auto stop_sequences = build_chat_stop_sequences(chat_params);

        if (is_max_debug_mode()) {
            log_to_file(std::string("generateOpenAiChatCompletion: prompt=\n") + prompt, GGML_LOG_LEVEL_DEBUG);
        }

        reset_generation_state_locked();

        const llama_vocab * vocab = llama_model_get_vocab(g_model);
        size_t n_prompt_tokens = 0;
        llama_pos n_past = 0;
        std::string prefill_error;
        reusable_token_batch generation_batch(1);
        if (!generation_batch.valid()) {
            return build_error("failed to allocate generation batch");
        }
        struct generation_reset_guard {
            ~generation_reset_guard() {
                reset_generation_state_locked();
            }
        } reset_guard;

        const bool has_media = media_files != nullptr && !media_files->empty();
        const bool prefill_ok = has_media
                ? prefill_multimodal_prompt_locked(prompt, *media_files, n_prompt_tokens, n_past, prefill_error)
                : prefill_text_prompt_locked(vocab, prompt, n_prompt_tokens, n_past, prefill_error);

        if (!prefill_ok) {
            return build_error(prefill_error);
        }

        if (n_past >= g_n_ctx) {
            return build_error("prompt exceeds context");
        }

        common_params_sampling sampling_params = build_chat_sampling_params_locked(chat_params);
        common_sampler_ptr sampler(common_sampler_init(g_model, sampling_params));
        if (!sampler) {
            return build_error("failed to initialize sampler");
        }

        const llama_pos generation_base_pos = n_past;
        const int max_tokens = 1024;

        std::string output;
        output.reserve(max_tokens * 4);
        std::vector<llama_token> out_tokens;
        out_tokens.reserve(max_tokens);
        std::string prev_text;

        for (int i = 0; i < max_tokens; ++i) {
            if (g_cancel_generation.load()) {
                break;
            }

            const llama_token id = common_sampler_sample(sampler.get(), g_ctx, -1);
            common_sampler_accept(sampler.get(), id, true);
            if (llama_vocab_is_eog(vocab, id)) {
                break;
            }

            out_tokens.push_back(id);
            if (generation_base_pos + static_cast<llama_pos>(out_tokens.size()) >= g_n_ctx - 32) {
                log_to_file("generateOpenAiChatCompletion: reached ctx safety limit, stopping early");
                break;
            }

            std::string full;
            int n_chars = detokenize_with_resize(vocab, out_tokens, full);
            if (n_chars > 0) {
                size_t safe_len = validate_utf8(full);
                if (safe_len < full.size()) {
                    full.resize(safe_len);
                }

                size_t stop_pos = std::string::npos;
                std::string stop_seq;
                if (find_stop_sequence_in_list(stop_sequences, full, &stop_pos, &stop_seq)) {
                    if (stop_pos < full.size()) {
                        full.resize(stop_pos);
                    }
                    output = full;
                    prev_text = full;
                    break;
                }

                if (full.size() > prev_text.size()) {
                    output += full.substr(prev_text.size());
                }
                prev_text = full;
            }

            generation_batch.batch.n_tokens = 1;
            generation_batch.set_token(0, id, generation_base_pos + i, true);

            const int rc_step = llama_decode(g_ctx, generation_batch.batch);
            if (rc_step != 0) {
                return build_error("decode failed (generation)");
            }
        }

        size_t safe_len = validate_utf8(output);
        if (safe_len < output.size()) {
            output.resize(safe_len);
        }

        common_chat_msg parsed_msg;
        try {
            parsed_msg = common_chat_parse(output, false, parser_params);
        } catch (const std::exception & e) {
            log_to_file(std::string("generateOpenAiChatCompletion: parser fallback: ") + e.what(), GGML_LOG_LEVEL_WARN);
            parsed_msg.content = output;
        }
        if (parsed_msg.empty() && !output.empty()) {
            parsed_msg.content = output;
        }

        json result = {
                {"content", parsed_msg.content},
                {"finish_reason", parsed_msg.tool_calls.empty() ? "stop" : "tool_calls"},
        };
        if (!parsed_msg.reasoning_content.empty()) {
            result["reasoning_content"] = parsed_msg.reasoning_content;
        }
        if (!parsed_msg.tool_calls.empty()) {
            auto msg_json = parsed_msg.to_json_oaicompat(false);
            result["tool_calls"] = msg_json["tool_calls"];
        }

        if (is_max_debug_mode()) {
            log_to_file(std::string("generateOpenAiChatCompletion: result=\n") + result.dump(2), GGML_LOG_LEVEL_DEBUG);
        }

        return new_java_string_utf8(env, result.dump());
    } catch (const std::exception & e) {
        return build_error(e.what());
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
    return generate_locked(env, jstring_to_std(env, jPrompt), nullptr);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_generateWithMedia(
        JNIEnv *env, jobject,
        jstring jPrompt,
        jobjectArray jMediaFiles
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    std::vector<std::vector<uint8_t>> media_files;
    if (jMediaFiles != nullptr) {
        const jsize count = env->GetArrayLength(jMediaFiles);
        media_files.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto * byte_array = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(jMediaFiles, i));
            if (!byte_array) {
                media_files.emplace_back();
                continue;
            }
            const jsize len = env->GetArrayLength(byte_array);
            std::vector<uint8_t> bytes(static_cast<size_t>(len));
            if (len > 0) {
                env->GetByteArrayRegion(byte_array, 0, len, reinterpret_cast<jbyte *>(bytes.data()));
            }
            media_files.push_back(std::move(bytes));
            env->DeleteLocalRef(byte_array);
        }
    }

    return generate_locked(env, jstring_to_std(env, jPrompt), &media_files);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_llama_LlamaNative_generateOpenAiChatCompletion(
        JNIEnv *env, jobject,
        jstring jMessagesJson,
        jstring jToolsJson,
        jstring jChatTemplateOverride,
        jstring jToolChoiceJson,
        jboolean jParallelToolCalls,
        jboolean jEnableThinking,
        jobjectArray jMediaFiles
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    std::vector<std::vector<uint8_t>> media_files;
    if (jMediaFiles != nullptr) {
        const jsize count = env->GetArrayLength(jMediaFiles);
        media_files.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto * byte_array = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(jMediaFiles, i));
            if (!byte_array) {
                media_files.emplace_back();
                continue;
            }
            const jsize len = env->GetArrayLength(byte_array);
            std::vector<uint8_t> bytes(static_cast<size_t>(len));
            if (len > 0) {
                env->GetByteArrayRegion(byte_array, 0, len, reinterpret_cast<jbyte *>(bytes.data()));
            }
            media_files.push_back(std::move(bytes));
            env->DeleteLocalRef(byte_array);
        }
    }

    return generate_openai_chat_completion_locked(
            env,
            jstring_to_std(env, jMessagesJson),
            jstring_to_std(env, jToolsJson),
            jstring_to_std(env, jChatTemplateOverride),
            jstring_to_std(env, jToolChoiceJson),
            jParallelToolCalls == JNI_TRUE,
            jEnableThinking == JNI_TRUE,
            &media_files);
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
        return new_java_string_utf8(env, "");
    }
    
    // Try to get chat template from GGUF metadata
    const char* chat_template = llama_model_chat_template(g_model, nullptr);
    
    if (chat_template && strlen(chat_template) > 0) {
        log_to_file(std::string("getChatTemplate: found template, len=") + std::to_string(strlen(chat_template)));
        return new_java_string_utf8(env, chat_template);
    }
    
    log_to_file("getChatTemplate: no chat template in model metadata");
    return new_java_string_utf8(env, "");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_micklab_llama_LlamaNative_supportsVision(
        JNIEnv *, jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_supports_vision ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_micklab_llama_LlamaNative_supportsAudio(
        JNIEnv *, jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_supports_audio ? JNI_TRUE : JNI_FALSE;
}
