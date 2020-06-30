/*
 * Copyright 2020 Wenxin Wang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
        jint mtu,
        jint max_latency,
        jint num_channel,
        jint mask_channel) {
    // We use std::nothrow so `new` returns a nullptr if the engine creation fails
    const char *ip_c = env->GetStringUTFChars(jip, 0);
    std::string ip(ip_c);
    env->ReleaseStringUTFChars(jip, ip_c);
    PulseRtpOboeEngine *engine = nullptr;
    try {
        engine = new(std::nothrow) PulseRtpOboeEngine(
                latency_option, ip, (uint16_t) port, mtu, max_latency, num_channel, mask_channel);
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

JNIEXPORT jint JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1getNumUnderrun(
        JNIEnv *env,
        jclass /*unused*/,
        jlong engineHandle) {
    if (!engineHandle) {
        return 0;
    }
    auto engine = reinterpret_cast<PulseRtpOboeEngine *>(engineHandle);
    return jint(engine->num_underrun());
}

JNIEXPORT jint JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1getAudioBufferSize(
        JNIEnv *env,
        jclass /*unused*/,
        jlong engineHandle) {
    if (!engineHandle) {
        return 0;
    }
    auto engine = reinterpret_cast<PulseRtpOboeEngine *>(engineHandle);
    return jint(engine->audio_buffer_size());
}

JNIEXPORT jlong JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1getPktBufferSize(
        JNIEnv *env,
        jclass /*unused*/,
        jlong engineHandle) {
    if (!engineHandle) {
        return 0;
    }
    auto engine = reinterpret_cast<PulseRtpOboeEngine *>(engineHandle);
    return jlong(engine->pkt_buffer_size());
}

JNIEXPORT jlong JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1getPktBufferCapacity(
        JNIEnv *env,
        jclass /*unused*/,
        jlong engineHandle) {
    if (!engineHandle) {
        return 0;
    }
    auto engine = reinterpret_cast<PulseRtpOboeEngine *>(engineHandle);
    return jlong(engine->pkt_buffer_capacity());
}

JNIEXPORT jlong JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1getPktBufferHeadMoveReq(
        JNIEnv *env,
        jclass /*unused*/,
        jlong engineHandle) {
    if (!engineHandle) {
        return 0;
    }
    auto engine = reinterpret_cast<PulseRtpOboeEngine *>(engineHandle);
    return jlong(engine->pkt_buffer_head_move_req());
}

JNIEXPORT jlong JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1getPktBufferHeadMove(
        JNIEnv *env,
        jclass /*unused*/,
        jlong engineHandle) {
    if (!engineHandle) {
        return 0;
    }
    auto engine = reinterpret_cast<PulseRtpOboeEngine *>(engineHandle);
    return jlong(engine->pkt_buffer_head_move());
}

JNIEXPORT jlong JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1getPktBufferTailMoveReq(
        JNIEnv *env,
        jclass /*unused*/,
        jlong engineHandle) {
    if (!engineHandle) {
        return 0;
    }
    auto engine = reinterpret_cast<PulseRtpOboeEngine *>(engineHandle);
    return jlong(engine->pkt_buffer_tail_move_req());
}

JNIEXPORT jlong JNICALL
Java_me_wenxinwang_pulsedroidrtp_PulseRtpAudioEngine_native_1getPktBufferTailMove(
        JNIEnv *env,
        jclass /*unused*/,
        jlong engineHandle) {
    if (!engineHandle) {
        return 0;
    }
    auto engine = reinterpret_cast<PulseRtpOboeEngine *>(engineHandle);
    return jlong(engine->pkt_buffer_tail_move());
}

} // extern "C"
