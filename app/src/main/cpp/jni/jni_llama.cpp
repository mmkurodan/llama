#include <jni.h>
#include <string>

// パッケージ名・クラス名は Java 側と合わせる
// package com.example.ollama; class LlamaNative

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_ollama_LlamaNative_init(
        JNIEnv *env,
        jobject /* this */,
        jstring jModelPath) {

    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);

    // いまは「パスを受け取れているか」の確認だけ
    std::string out = std::string("init() called with: ") + modelPath;

    env->ReleaseStringUTFChars(jModelPath, modelPath);

    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_ollama_LlamaNative_generate(
        JNIEnv *env,
        jobject /* this */,
        jstring jPrompt) {

    const char *prompt = env->GetStringUTFChars(jPrompt, nullptr);

    std::string out = std::string("generate() got: ") + prompt;

    env->ReleaseStringUTFChars(jPrompt, prompt);

    return env->NewStringUTF(out.c_str());
}
