//
// Created by wenxin on 20-6-14.
//

#include <jni.h>
#include <oboe/Oboe.h>
#include "PulseRtpOboeEngine.h"

int g_latency_option = 0;

extern "C" {
JNIEXPORT jlong JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1createEngine(
        JNIEnv *env,
        jclass /*unused*/) {
    // We use std::nothrow so `new` returns a nullptr if the engine creation fails
    PulseRtpOboeEngine *engine = new(std::nothrow) PulseRtpOboeEngine(g_latency_option);
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

JNIEXPORT void JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1setLatencyOption(
        JNIEnv *env,
        jclass type,
        jint latency_option) {
    g_latency_option = latency_option;
}

} // extern "C"
