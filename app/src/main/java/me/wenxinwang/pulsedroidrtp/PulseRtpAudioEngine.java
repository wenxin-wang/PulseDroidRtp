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

package me.wenxinwang.pulsedroidrtp;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

public class PulseRtpAudioEngine {
    static long mEngineHandle = 0;

    // Load native library
    static {
        System.loadLibrary("pulsedroid-rtp");
    }

    static boolean create(
        Context context, int latencyOption, String ip, int port, int mtu, int max_latency, int num_channel, int mask_channel){
        if (mEngineHandle == 0){
            setDefaultStreamValues(context);
            mEngineHandle = native_createEngine(latencyOption, ip, port, mtu, max_latency, num_channel, mask_channel);
        } else {
            Log.e("pulsedroid-rtp", "Engine handle already created");
        }
        return (mEngineHandle != 0);
    }

    private static void setDefaultStreamValues(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1){
            AudioManager myAudioMgr = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            String sampleRateStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            int defaultSampleRate = Integer.parseInt(sampleRateStr);
            String framesPerBurstStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            int defaultFramesPerBurst = Integer.parseInt(framesPerBurstStr);

            native_setDefaultStreamValues(defaultSampleRate, defaultFramesPerBurst);
        }
    }

    static void delete(){
        if (mEngineHandle != 0){
            native_deleteEngine(mEngineHandle);
        }
        mEngineHandle = 0;
    }

    static int getNumUnderrun(){
        return native_getNumUnderrun(mEngineHandle);
    }

    static int getAudioBufferSize(){
        return native_getAudioBufferSize(mEngineHandle);
    }

    static long getPktBufferSize(){
        return native_getPktBufferSize(mEngineHandle);
    }

    static long getPktBufferCapacity(){
        return native_getPktBufferCapacity(mEngineHandle);
    }

    static long getPktBufferHeadMoveReq(){
        return native_getPktBufferHeadMoveReq(mEngineHandle);
    }

    static long getPktBufferHeadMove(){
        return native_getPktBufferHeadMove(mEngineHandle);
    }

    static long getPktBufferTailMoveReq(){
        return native_getPktBufferTailMoveReq(mEngineHandle);
    }

    static long getPktBufferTailMove(){
        return native_getPktBufferTailMove(mEngineHandle);
    }

    // Native methods
    private static native long native_createEngine(
        int latencyOption, String ip, int port, int mtu, int max_latency, int num_channel, int mask_channel);
    private static native void native_deleteEngine(long engineHandle);
    private static native void native_setDefaultStreamValues(int sampleRate, int framesPerBurst);
    private static native int native_getNumUnderrun(long engineHandle);
    private static native int native_getAudioBufferSize(long engineHandle);
    private static native long native_getPktBufferCapacity(long engineHandle);
    private static native long native_getPktBufferSize(long engineHandle);
    private static native long native_getPktBufferHeadMoveReq(long engineHandle);
    private static native long native_getPktBufferHeadMove(long engineHandle);
    private static native long native_getPktBufferTailMoveReq(long engineHandle);
    private static native long native_getPktBufferTailMove(long engineHandle);
}
