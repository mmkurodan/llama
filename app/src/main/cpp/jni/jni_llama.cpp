#include <jni.h>
#include <string>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ollama_LlamaNative_init(JNIEnv* env, jobject thiz, jstring jModelPath) {
    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);

    // TODO: ここで本当は llama_model_load を呼ぶ
    // いまは動作確認用のダミー実装
    std::string out = std::string("init() called with: ") + modelPath;

    env->ReleaseStringUTFChars(jModelPath, modelPath);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ollama_LlamaNative_generate(JNIEnv* env, jobject thiz, jstring jPrompt) {
    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);

    // TODO: ここで本当は llama_eval などを呼ぶ
    // いまは動作確認用のダミー実装
    std::string out = std::string("generate() got: ") + prompt;

    env->ReleaseStringUTFChars(jPrompt, prompt);
    return env->NewStringUTF(out.c_str());
}
