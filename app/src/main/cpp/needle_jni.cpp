#include <jni.h>

#include <cstdlib>
#include <functional>
#include <string>
#include <vector>

#include "needle.h"

namespace {

std::string to_std(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = (c != nullptr) ? c : "";
    if (c != nullptr) env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring to_java(JNIEnv* env, const char* s) {
    return env->NewStringUTF(s != nullptr ? s : "");
}

// Executa uma chamada que preenche um buffer de saida JSON e devolve jstring.
// O buffer e sempre terminado em '\0' antes da chamada, por seguranca.
jstring call_with_output(JNIEnv* env, int out_capacity, const std::function<int(char*, int)>& fn) {
    if (out_capacity <= 0) return nullptr;
    std::vector<char> buf(static_cast<size_t>(out_capacity));
    buf[0] = '\0';
    buf.back() = '\0';
    const int rc = fn(buf.data(), out_capacity);
    if (rc < 0) return nullptr;
    buf.back() = '\0';
    return to_java(env, buf.data());
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeDisableTelemetry(JNIEnv*, jobject) {
    // O engine pre-compilado tem telemetria ligada por padrao.
    setenv("NEEDLE_TELEMETRY", "0", 1);
    setenv("DO_NOT_TRACK", "1", 1);
}

JNIEXPORT jint JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeLoad(JNIEnv* env, jobject, jbyteArray cact) {
    if (cact == nullptr) return -1;
    const jsize n = env->GetArrayLength(cact);
    jbyte* data = env->GetByteArrayElements(cact, nullptr);
    if (data == nullptr) return -1;
    const int rc = needle_load(reinterpret_cast<const unsigned char*>(data),
                               static_cast<unsigned long long>(n));
    env->ReleaseByteArrayElements(cact, data, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeModels(JNIEnv*, jobject) {
    return needle_models();
}

JNIEXPORT jstring JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeLastError(JNIEnv* env, jobject) {
    return to_java(env, needle_last_error());
}

JNIEXPORT jint JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeInit(JNIEnv* env, jobject, jstring system_prompt,
                                                           jstring tools_json, jstring tool_index_path) {
    const std::string sp = to_std(env, system_prompt);
    const std::string tj = to_std(env, tools_json);
    const std::string ti = to_std(env, tool_index_path);
    return needle_init(system_prompt != nullptr ? sp.c_str() : nullptr,
                       tools_json != nullptr ? tj.c_str() : nullptr,
                       tool_index_path != nullptr ? ti.c_str() : nullptr);
}

JNIEXPORT jstring JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeCompleteText(JNIEnv* env, jobject, jstring input,
                                                                   jint max_new_tokens, jint out_capacity) {
    if (input == nullptr) return nullptr;
    const std::string in = to_std(env, input);
    return call_with_output(env, out_capacity, [&](char* out, int cap) {
        return needle_complete(in.c_str(), nullptr, 0, max_new_tokens, out, cap);
    });
}

JNIEXPORT jstring JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeCompletePcm(JNIEnv* env, jobject, jfloatArray pcm,
                                                                  jint max_new_tokens, jint out_capacity) {
    if (pcm == nullptr) return nullptr;
    const jsize n = env->GetArrayLength(pcm);
    jfloat* data = env->GetFloatArrayElements(pcm, nullptr);
    if (data == nullptr) return nullptr;
    jstring result = call_with_output(env, out_capacity, [&](char* out, int cap) {
        return needle_complete(nullptr, data, n, max_new_tokens, out, cap);
    });
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeSetAudio(JNIEnv* env, jobject, jstring language,
                                                               jstring keywords, jboolean word_timestamps) {
    const std::string lang = to_std(env, language);
    const std::string kw = to_std(env, keywords);
    needle_set_audio(language != nullptr ? lang.c_str() : nullptr,
                     keywords != nullptr ? kw.c_str() : nullptr,
                     word_timestamps ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeReset(JNIEnv*, jobject) {
    needle_reset();
}

JNIEXPORT jstring JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeTranscribe(JNIEnv* env, jobject, jfloatArray pcm,
                                                                 jstring language, jstring keywords,
                                                                 jboolean word_timestamps, jint out_capacity) {
    if (pcm == nullptr) return nullptr;
    const jsize n = env->GetArrayLength(pcm);
    jfloat* data = env->GetFloatArrayElements(pcm, nullptr);
    if (data == nullptr) return nullptr;
    const std::string lang = to_std(env, language);
    const std::string kw = to_std(env, keywords);
    jstring result = call_with_output(env, out_capacity, [&](char* out, int cap) {
        return needle_transcribe(data, n, language != nullptr ? lang.c_str() : nullptr,
                                 keywords != nullptr ? kw.c_str() : nullptr,
                                 word_timestamps ? 1 : 0, out, cap);
    });
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeEmbedText(JNIEnv* env, jobject, jstring input,
                                                                jint out_capacity) {
    if (input == nullptr || out_capacity <= 0) return nullptr;
    const std::string in = to_std(env, input);
    std::vector<float> buf(static_cast<size_t>(out_capacity));
    const int rc = needle_embed(in.c_str(), nullptr, 0, buf.data(), out_capacity);
    if (rc < 0) return nullptr;
    jfloatArray out = env->NewFloatArray(rc);
    if (out == nullptr) return nullptr;
    env->SetFloatArrayRegion(out, 0, rc, buf.data());
    return out;
}

JNIEXPORT jstring JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeStreamProcess(JNIEnv* env, jobject, jfloatArray pcm,
                                                                    jstring language, jstring keywords,
                                                                    jint out_capacity) {
    if (pcm == nullptr) return nullptr;
    const jsize n = env->GetArrayLength(pcm);
    jfloat* data = env->GetFloatArrayElements(pcm, nullptr);
    if (data == nullptr) return nullptr;
    const std::string lang = to_std(env, language);
    const std::string kw = to_std(env, keywords);
    jstring result = call_with_output(env, out_capacity, [&](char* out, int cap) {
        return needle_stream_transcribe_process(data, n, language != nullptr ? lang.c_str() : nullptr,
                                                keywords != nullptr ? kw.c_str() : nullptr, out, cap);
    });
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_catarina_assistant_engine_NeedleEngine_nativeStreamStop(JNIEnv* env, jobject, jint out_capacity) {
    return call_with_output(env, out_capacity, [&](char* out, int cap) {
        return needle_stream_transcribe_stop(out, cap);
    });
}

}  // extern "C"
