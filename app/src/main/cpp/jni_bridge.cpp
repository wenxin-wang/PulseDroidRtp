//
// Created by wenxin on 20-6-14.
//

#include <jni.h>
#include <oboe/Oboe.h>
#include "PulseRtpOboeEngine.h"
#include <logging_macros.h>

extern "C" {
JNIEXPORT jlong JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1createEngine(
        JNIEnv *env,
        jclass /*unused*/,
        jint latency_option,
        jstring jip,
        jint port,
        jint mtu) {
    // We use std::nothrow so `new` returns a nullptr if the engine creation fails
    const char *ip_c = env->GetStringUTFChars(jip, 0);
    std::string ip(ip_c);
    env->ReleaseStringUTFChars(jip, ip_c);
    PulseRtpOboeEngine *engine = nullptr;
    try {
        engine = new(std::nothrow) PulseRtpOboeEngine(
                latency_option, ip, (uint16_t) port, mtu);
    } catch (const std::system_error& e) {
        LOGE("Cannot create PulseRtpOboeEngine %s", e.what());
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1deleteEngine(
        JNIEnv *env,
        jclass,
        jlong engineHandle) {

    delete reinterpret_cast<PulseRtpOboeEngine *>(engineHandle);
}

JNIEXPORT void JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1setDefaultStreamValues(
        JNIEnv *env,
        jclass type,
        jint sampleRate,
        jint framesPerBurst) {
    oboe::DefaultStreamValues::SampleRate = (int32_t) sampleRate;
    oboe::DefaultStreamValues::FramesPerBurst = (int32_t) framesPerBurst;
}

} // extern "C"
