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
package me.wenxinwang.pulsedroidrtp

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

object PulseRtpAudioEngine {
    @JvmField
    val LATENCY_OPTIONS = arrayOf("Low Latency", "None", "Power Saving") // final

    class Params {
        var latencyOption = 0
            set(value) {
                if (value in 0..LATENCY_OPTIONS.size) field = value
            }
        var ip: String = "224.0.0.56"
            set(value) {
                if (value.isNotEmpty()) field = value
            }
        var port = 4010
            set(value) {
                if (value in 1..65535) field = value
            }
        var mtu = 320
            set(value) {
                if (value > 0) field = value
            }
        var maxLatency = 300
            set(value) {
                if (value > 0) field = value
            }
        var numChannel = 2
            set(value) {
                if (value > 0) field = value
            }
        var maskChannel = 0

        fun fromSharedPref(context: Context) {
            val sharedPref = getSharedPreference(context)
            ip = sharedPref.getString(SHARED_PREF_IP, null) ?: ""
            port = sharedPref.getInt(SHARED_PREF_PORT, 0)
            latencyOption = sharedPref.getInt(SHARED_PREF_LATENCY, 0)
            mtu = sharedPref.getInt(SHARED_PREF_MTU, 0)
            maxLatency = sharedPref.getInt(SHARED_PREF_MAX_LATENCY, 0)
            numChannel = sharedPref.getInt(SHARED_PREF_NUM_CHANNEL, 0)
            maskChannel = sharedPref.getInt(SHARED_PREF_MASK_CHANNEL, 0)
        }

        fun saveToSharedPref(context: Context) {
            val sharedPref = getSharedPreference(context)
            val editor = sharedPref.edit()
            editor.putInt(SHARED_PREF_LATENCY, latencyOption)
            editor.putString(SHARED_PREF_IP, ip)
            editor.putInt(SHARED_PREF_PORT, port)
            editor.putInt(SHARED_PREF_MTU, mtu)
            editor.putInt(SHARED_PREF_MAX_LATENCY, maxLatency)
            editor.putInt(SHARED_PREF_NUM_CHANNEL, numChannel)
            editor.putInt(SHARED_PREF_MASK_CHANNEL, maskChannel)
            editor.apply()
        }

        fun fromUri(uri: Uri) {
            ip = uri.host ?: ip
            port = uri.port.let { if (it > 0) it else port }
            latencyOption = uri.getQueryParameter(SHARED_PREF_LATENCY)?.toIntOrNull() ?: 0
            mtu = uri.getQueryParameter(SHARED_PREF_MTU)?.toIntOrNull() ?: 0
            maxLatency = uri.getQueryParameter(SHARED_PREF_MAX_LATENCY)?.toIntOrNull() ?: 0
            numChannel = uri.getQueryParameter(SHARED_PREF_NUM_CHANNEL)?.toIntOrNull() ?: 0
            maskChannel = uri.getQueryParameter(SHARED_PREF_MASK_CHANNEL)?.toIntOrNull() ?: 0
        }

        fun toUri(): Uri {
            val builder = Uri.Builder()
            builder.scheme("udp")
                .encodedAuthority("$ip:$port")
                .path("/")
                .appendQueryParameter(SHARED_PREF_LATENCY, latencyOption.toString())
                .appendQueryParameter(SHARED_PREF_MTU, mtu.toString())
                .appendQueryParameter(SHARED_PREF_MAX_LATENCY, maxLatency.toString())
                .appendQueryParameter(SHARED_PREF_NUM_CHANNEL, numChannel.toString())
                .appendQueryParameter(SHARED_PREF_MASK_CHANNEL, maskChannel.toString())
            return builder.build()
        }
    }

    private var mEngineHandle: Long = 0
    private var mSampleRateStr: String = ""
    private var mFramesPerBurstStr: String = ""

    fun create(params: Params): Boolean {
        if (mEngineHandle == 0L) with(params) {
            mEngineHandle =
                native_createEngine(latencyOption, ip, port, mtu, maxLatency, numChannel, maskChannel)
        } else {
            Log.e("pulsedroid-rtp", "Engine handle already created")
        }
        return mEngineHandle != 0L
    }

    fun initDefaultValues(context: Context) {
        setDefaultStreamValues(context)
    }

    fun isPlaying(): Boolean {
        return mEngineHandle != 0L
    }

    private fun setDefaultStreamValues(context: Context) {
        val myAudioMgr = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mSampleRateStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val defaultSampleRate = mSampleRateStr.toInt()
        mFramesPerBurstStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val defaultFramesPerBurst = mFramesPerBurstStr.toInt()
        native_setDefaultStreamValues(defaultSampleRate, defaultFramesPerBurst)
    }

    fun destroy() {
        if (mEngineHandle != 0L) {
            native_deleteEngine(mEngineHandle)
        }
        mEngineHandle = 0
    }

    fun restoreUri(context: Context): Uri? {
        val sharedPref = getSharedPreference(context)
        return sharedPref.getString(SHARED_PREF_URI, null)?.let { Uri.parse(it) }
    }

    fun commitUri(uri: Uri, context: Context) {
        val sharedPref = getSharedPreference(context)
        val editor = sharedPref.edit()
        editor.putString(SHARED_PREF_URI, uri.toString())
        editor.commit()
    }

    fun restorePlayState(context: Context): Boolean {
        val sharedPref = getSharedPreference(context)
        return sharedPref.getBoolean(SHARED_PREF_PLAY_STATE, false)
    }

    fun savePlayState(isPlaying: Boolean, context: Context) {
        val sharedPref = getSharedPreference(context)
        val editor = sharedPref.edit()
        editor.putBoolean(SHARED_PREF_PLAY_STATE, isPlaying)
        editor.apply()
    }

    private fun getSharedPreference(context: Context): SharedPreferences {
        return context.getSharedPreferences(
            SHARRED_PREF_NAME, AppCompatActivity.MODE_PRIVATE)
    }

    val sampleRateStr: String
        get() = mSampleRateStr
    val framesPerBurstStr: String
        get() = mFramesPerBurstStr
    val numUnderrun: Int
        get() = native_getNumUnderrun(mEngineHandle)
    val audioBufferSize: Int
        get() = native_getAudioBufferSize(mEngineHandle)
    val pktBufferSize: Long
        get() = native_getPktBufferSize(mEngineHandle)
    val pktBufferCapacity: Long
        get() = native_getPktBufferCapacity(mEngineHandle)
    val pktBufferHeadMoveReq: Long
        get() = native_getPktBufferHeadMoveReq(mEngineHandle)
    val pktBufferHeadMove: Long
        get() = native_getPktBufferHeadMove(mEngineHandle)
    val pktBufferTailMoveReq: Long
        get() = native_getPktBufferTailMoveReq(mEngineHandle)
    val pktBufferTailMove: Long
        get() = native_getPktBufferTailMove(mEngineHandle)
    val pktReceived: Long
        get() = native_getPktReceived(mEngineHandle)

    // Native methods
    @JvmStatic
    private external fun native_createEngine(
        latencyOption: Int,
        ip: String,
        port: Int,
        mtu: Int,
        max_latency: Int,
        num_channel: Int,
        mask_channel: Int
    ): Long

    @JvmStatic
    private external fun native_deleteEngine(engineHandle: Long)

    @JvmStatic
    private external fun native_setDefaultStreamValues(sampleRate: Int, framesPerBurst: Int)

    @JvmStatic
    private external fun native_getNumUnderrun(engineHandle: Long): Int

    @JvmStatic
    private external fun native_getAudioBufferSize(engineHandle: Long): Int

    @JvmStatic
    private external fun native_getPktBufferCapacity(engineHandle: Long): Long

    @JvmStatic
    private external fun native_getPktBufferSize(engineHandle: Long): Long

    @JvmStatic
    private external fun native_getPktBufferHeadMoveReq(engineHandle: Long): Long

    @JvmStatic
    private external fun native_getPktBufferHeadMove(engineHandle: Long): Long

    @JvmStatic
    private external fun native_getPktBufferTailMoveReq(engineHandle: Long): Long

    @JvmStatic
    private external fun native_getPktBufferTailMove(engineHandle: Long): Long

    @JvmStatic
    private external fun native_getPktReceived(engineHandle: Long): Long

    // Load native library
    init {
        System.loadLibrary("pulsedroid-rtp")
    }

    private const val SHARRED_PREF_NAME = "PulseDroidRtp"
    private const val SHARED_PREF_LATENCY = "latency"
    private const val SHARED_PREF_IP = "ip"
    private const val SHARED_PREF_PORT = "port"
    private const val SHARED_PREF_MTU = "mtu"
    private const val SHARED_PREF_MAX_LATENCY = "max_latency"
    private const val SHARED_PREF_NUM_CHANNEL = "num_channel"
    private const val SHARED_PREF_MASK_CHANNEL = "mask_channel"
    private const val SHARED_PREF_URI = "uri"
    private const val SHARED_PREF_PLAY_STATE = "play_state"
}