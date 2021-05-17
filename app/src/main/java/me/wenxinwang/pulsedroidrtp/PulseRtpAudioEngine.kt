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
import android.media.AudioManager
import android.os.Build
import android.util.Log

object PulseRtpAudioEngine {
  var mEngineHandle: Long = 0
  var mSampleRateStr: String = ""
  var mFramesPerBurstStr: String = ""

  fun initDefaultValues(context: Context) {
    setDefaultStreamValues(context)
  }

  fun create(
    latencyOption: Int,
    ip: String,
    port: Int,
    mtu: Int,
    max_latency: Int,
    num_channel: Int,
    mask_channel: Int
  ): Boolean {
    if (mEngineHandle == 0L) {
      mEngineHandle =
        native_createEngine(latencyOption, ip, port, mtu, max_latency, num_channel, mask_channel)
    } else {
      Log.e("pulsedroid-rtp", "Engine handle already created")
    }
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
  private external fun native_createEngine(
    latencyOption: Int,
    ip: String,
    port: Int,
    mtu: Int,
    max_latency: Int,
    num_channel: Int,
    mask_channel: Int
  ): Long

  private external fun native_deleteEngine(engineHandle: Long)
  private external fun native_setDefaultStreamValues(sampleRate: Int, framesPerBurst: Int)
  private external fun native_getNumUnderrun(engineHandle: Long): Int
  private external fun native_getAudioBufferSize(engineHandle: Long): Int
  private external fun native_getPktBufferCapacity(engineHandle: Long): Long
  private external fun native_getPktBufferSize(engineHandle: Long): Long
  private external fun native_getPktBufferHeadMoveReq(engineHandle: Long): Long
  private external fun native_getPktBufferHeadMove(engineHandle: Long): Long
  private external fun native_getPktBufferTailMoveReq(engineHandle: Long): Long
  private external fun native_getPktBufferTailMove(engineHandle: Long): Long
  private external fun native_getPktReceived(engineHandle: Long): Long

  // Load native library
  init {
    System.loadLibrary("pulsedroid-rtp")
  }
}