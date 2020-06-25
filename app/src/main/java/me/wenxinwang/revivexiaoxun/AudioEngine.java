package me.wenxinwang.revivexiaoxun;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

public class AudioEngine {
    static long mEngineHandle = 0;

    // Load native library
    static {
        System.loadLibrary("revive-xiaoxun");
    }

    static boolean create(Context context){

        if (mEngineHandle == 0){
            setDefaultStreamValues(context);
            mEngineHandle = native_createEngine();
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

    // Native methods
    private static native long native_createEngine();
    private static native void native_deleteEngine(long engineHandle);
    private static native void native_setDefaultStreamValues(int sampleRate, int framesPerBurst);
}
