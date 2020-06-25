//
// Created by wenxin on 20-6-14.
//

#include <jni.h>
#include <oboe/Oboe.h>
#include "OboeEngine.h"

extern "C" {
JNIEXPORT jlong JNICALL
Java_me_wenxinwang_revivexiaoxun_AudioEngine_native_1createEngine(
        JNIEnv *env,
        jclass /*unused*/) {
    // We use std::nothrow so `new` returns a nullptr if the engine creation fails
    OboeEngine *engine = new(std::nothrow) OboeEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_me_wenxinwang_revivexiaoxun_AudioEngine_native_1deleteEngine(
        JNIEnv *env,
        jclass,
        jlong engineHandle) {

    delete reinterpret_cast<OboeEngine *>(engineHandle);
}

JNIEXPORT void JNICALL
Java_me_wenxinwang_revivexiaoxun_AudioEngine_native_1setDefaultStreamValues(
        JNIEnv *env,
        jclass type,
        jint sampleRate,
        jint framesPerBurst) {
    oboe::DefaultStreamValues::SampleRate = (int32_t) sampleRate;
    oboe::DefaultStreamValues::FramesPerBurst = (int32_t) framesPerBurst;
}

} // extern "C"
