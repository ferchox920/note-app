#include <jni.h>
#include <whisper.h>

#include <algorithm>
#include <cstdint>
#include <string>

#ifndef NOTEAPP_NATIVE_BUILD_TYPE
#define NOTEAPP_NATIVE_BUILD_TYPE "unknown"
#endif

namespace {

whisper_context * context_from(jlong pointer) {
    return reinterpret_cast<whisper_context *>(pointer);
}

void throw_illegal_state(JNIEnv * env, const char * message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) {
        env->ThrowNew(exception, message);
    }
}

jstring new_utf8_string(JNIEnv * env, const char * utf8) {
    if (utf8 == nullptr) {
        return env->NewStringUTF("");
    }
    const auto length = static_cast<jsize>(std::char_traits<char>::length(utf8));
    jbyteArray bytes = env->NewByteArray(length);
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(utf8));

    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(
        string_class,
        "<init>",
        "([BLjava/lang/String;)V"
    );
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_class);
    return result;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_noteapp_asr_WhisperNative_nativeInit(
    JNIEnv * env,
    jobject,
    jstring model_path
) {
    if (model_path == nullptr) return 0;
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    whisper_context * context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_noteapp_asr_WhisperNative_nativeFree(JNIEnv *, jobject, jlong pointer) {
    whisper_context * context = context_from(pointer);
    if (context != nullptr) whisper_free(context);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_noteapp_asr_WhisperNative_nativeTranscribe(
    JNIEnv * env,
    jobject,
    jlong pointer,
    jfloatArray audio,
    jint thread_count,
    jstring language
) {
    whisper_context * context = context_from(pointer);
    if (context == nullptr) {
        throw_illegal_state(env, "Whisper context is closed");
        return -1;
    }
    if (audio == nullptr || language == nullptr) return -2;

    jfloat * samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize sample_count = env->GetArrayLength(audio);
    const char * language_chars = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, static_cast<int>(thread_count));
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = false;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = language_chars;
    params.detect_language = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.temperature = 0.0f;
    // Whisper defaults to the model's full 30 s encoder context even for the
    // short 5–8 s chunks used by the mobile pipeline. Limit the graph to the
    // actual mel-frame requirement (one encoder position per 20 ms) so short
    // chunks do not pay the full-window cost.
    params.audio_ctx = std::clamp(
        static_cast<int>((sample_count + 319) / 320),
        1,
        whisper_model_n_audio_ctx(context)
    );

    whisper_reset_timings(context);
    const int result = whisper_full(context, params, samples, sample_count);

    env->ReleaseStringUTFChars(language, language_chars);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_noteapp_asr_WhisperNative_nativeSegmentCount(JNIEnv *, jobject, jlong pointer) {
    whisper_context * context = context_from(pointer);
    return context == nullptr ? 0 : whisper_full_n_segments(context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noteapp_asr_WhisperNative_nativeSegmentText(
    JNIEnv * env,
    jobject,
    jlong pointer,
    jint index
) {
    whisper_context * context = context_from(pointer);
    if (context == nullptr) return env->NewStringUTF("");
    return new_utf8_string(env, whisper_full_get_segment_text(context, index));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_noteapp_asr_WhisperNative_nativeSegmentStart(
    JNIEnv *, jobject, jlong pointer, jint index
) {
    whisper_context * context = context_from(pointer);
    return context == nullptr ? 0 : whisper_full_get_segment_t0(context, index);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_noteapp_asr_WhisperNative_nativeSegmentEnd(
    JNIEnv *, jobject, jlong pointer, jint index
) {
    whisper_context * context = context_from(pointer);
    return context == nullptr ? 0 : whisper_full_get_segment_t1(context, index);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_noteapp_asr_WhisperNative_nativeSegmentNoSpeechProbability(
    JNIEnv *, jobject, jlong pointer, jint index
) {
    whisper_context * context = context_from(pointer);
    return context == nullptr ? 1.0f : whisper_full_get_segment_no_speech_prob(context, index);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_noteapp_asr_WhisperNative_nativeTimings(JNIEnv * env, jobject, jlong pointer) {
    constexpr jsize count = 5;
    jfloat values[count] = {};
    whisper_context * context = context_from(pointer);
    if (context != nullptr) {
        whisper_timings * timings = whisper_get_timings(context);
        if (timings != nullptr) {
            values[0] = timings->sample_ms;
            values[1] = timings->encode_ms;
            values[2] = timings->decode_ms;
            values[3] = timings->batchd_ms;
            values[4] = timings->prompt_ms;
        }
    }
    jfloatArray result = env->NewFloatArray(count);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, count, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noteapp_asr_WhisperNative_nativeSystemInfo(JNIEnv * env, jobject) {
    std::string info = "build_type=";
    info += NOTEAPP_NATIVE_BUILD_TYPE;
    info += "; ";
    info += whisper_print_system_info();
    return new_utf8_string(env, info.c_str());
}
