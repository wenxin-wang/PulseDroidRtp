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

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.appcompat.app.AppCompatActivity
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.sampleRateStr
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.framesPerBurstStr
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.audioBufferSize
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.numUnderrun
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferCapacity
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferHeadMove
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferHeadMoveReq
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferSize
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferTailMove
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferTailMoveReq
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktReceived
import java.util.*

class MainActivity : AppCompatActivity() {
  private lateinit var mInfo: TextView
  private lateinit var mButton: Button
  private lateinit var mIpEdit: EditText
  private lateinit var mPortEdit: EditText
  private lateinit var mMtuEdit: EditText
  private lateinit var mMaxLatencyEdit: EditText
  private lateinit var mNumChannelEdit: EditText
  private lateinit var mMaskChannelEdit: EditText
  private lateinit var mLatencySpinner: Spinner
  private var mLatencyOption = 0
  private var mIp: String = "224.0.0.56"
  private var mPort = 4010
  private var mMtu = 320
  private var mMaxLatency = 300
  private var mNumChannel = 2
  private var mMaskChannel = 0
  private var mPlaying = false

  private lateinit var mHandler: Handler
  private lateinit var mStatusChecker: Runnable

  override fun onSaveInstanceState(outState: Bundle) {
    // Make sure to call the super method so that the states of our views are saved
    super.onSaveInstanceState(outState)
    // Save our own state now
    outState.putSerializable(STATE_PLAYING, mPlaying)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setupViews()
    restoreStates()
    syncViewsWithStates()
    // dynamic states
    mHandler = Handler()
    mStatusChecker = Runnable {
        updateStatus()
        mHandler.postDelayed(mStatusChecker, STATUS_CHECK_INTERVAL.toLong())
    }
    mPlaying = false
    // get playing state
    savedInstanceState?.let {
      if (it.getBoolean(STATE_PLAYING, false)) {
        togglePlay()
      }
    }
  }

  /*
     * Creating engine in onResume() and destroying in onPause() so the stream retains exclusive
     * mode only while in focus. This allows other apps to reclaim exclusive stream mode.
     */
  override fun onResume() {
    super.onResume()
    // StartPlaying();
  }

  override fun onPause() {
    // StopPlaying();
    super.onPause()
  }

  override fun onDestroy() {
    stopPlaying()
    super.onDestroy()
  }

  /**
   * Called when the user touches the button
   */
  private fun togglePlay() {
    hideKb()
    // Do something in response to button click
    if (mPlaying) {
      mPlaying = false
      stopPlaying()
      mButton.setText(R.string.play)
      return
    }
    val success = startPlaying()
    if (success) {
      mPlaying = true
      mButton.setText(R.string.stop)
    }
  }

  private fun startPlaying(): Boolean {
    // read params from views
    mIp = mIpEdit.text.toString().takeIf { it.isNotEmpty() } ?: run {
      setInfoMsg("Could not get ip")
      return false
    }
    mPort = mPortEdit.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
      setInfoMsg("Could not get port")
      return false
    }
    mMtu = mMtuEdit.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: run {
      setInfoMsg("Could not get mtu")
      return false
    }
    mMaxLatency = mMaxLatencyEdit.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: run {
      setInfoMsg("Could not get max latency")
      return false
    }
    mNumChannel = mNumChannelEdit.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: run {
      setInfoMsg("Could not get channel num")
      return false
    }
    mMaskChannel = mMaskChannelEdit.text.toString().toIntOrNull() ?: run {
      setInfoMsg("Could not get channel num")
      return false
    }
    // create engine, which is started immediately
    val success = PulseRtpAudioEngine.create(
      this, mLatencyOption, mIp, mPort, mMtu, mMaxLatency, mNumChannel, mMaskChannel
    )
    if (!success) {
      setInfoMsg("Could not create PulseRtpAudioEngine")
      return false
    }
    startUpdateStatusTimer()
    val sharedPref = getSharedPreferences(
      SHARRED_PREF_NAME, MODE_PRIVATE
    )
    val editor = sharedPref.edit()
    editor.putInt(SHARED_PREF_LATENCY, mLatencyOption)
    editor.putString(SHARED_PREF_IP, mIp)
    editor.putInt(SHARED_PREF_PORT, mPort)
    editor.putInt(SHARED_PREF_MTU, mMtu)
    editor.putInt(SHARED_PREF_MAX_LATENCY, mMaxLatency)
    editor.putInt(SHARED_PREF_NUM_CHANNEL, mNumChannel)
    editor.putInt(SHARED_PREF_MASK_CHANNEL, mMaskChannel)
    editor.commit()
    return true
  }

  private fun stopPlaying() {
    stopUpdateStatusTimer()
    setInfoMsg("")
    PulseRtpAudioEngine.destroy()
  }

  private fun startUpdateStatusTimer() {
    mStatusChecker.run()
  }

  private fun stopUpdateStatusTimer() {
    mHandler.removeCallbacks(mStatusChecker)
  }

  private fun updateStatus() {
    val infoMsg = """sampleRate: $sampleRateStr, framesPerBurst: $framesPerBurstStr
audioBuffer: $audioBufferSize, underRun: $numUnderrun
pktBuffer: $pktBufferSize/$pktBufferCapacity $pktReceived
r: $pktBufferHeadMoveReq/$pktBufferHeadMove
w: $pktBufferTailMoveReq/$pktBufferTailMove"""
    setInfoMsg(infoMsg)
  }

  private fun setInfoMsg(infoMsg: String) {
    runOnUiThread { mInfo.text = infoMsg }
  }

  private fun setupViews() {
    mInfo = findViewById(R.id.info)
    mButton = findViewById(R.id.play)
    mButton.setOnClickListener { togglePlay() }
    mIpEdit = findViewById(R.id.ipEdit)
    mPortEdit = findViewById(R.id.portEdit)
    mMtuEdit = findViewById(R.id.mtuEdit)
    mMaxLatencyEdit = findViewById(R.id.maxLatencyEdit)
    mNumChannelEdit = findViewById(R.id.numChannelEdit)
    mMaskChannelEdit = findViewById(R.id.maskChannelEdit)
    mLatencySpinner = findViewById(R.id.latencyOptionSpinner)
    setupLatencySpinner()
  }

  private fun setupLatencySpinner() {
    mLatencySpinner.adapter = SimpleAdapter(
        this,
        createLatencyOptionsList(),
        R.layout.latency_spinner,
        arrayOf(getString(R.string.description_key)),
        intArrayOf(R.id.latencyOption)
    )
    mLatencySpinner.onItemSelectedListener = object : OnItemSelectedListener {
      override fun onItemSelected(adapterView: AdapterView<*>?, view: View?, i: Int, l: Long) {
        mLatencyOption = i
      }

      override fun onNothingSelected(adapterView: AdapterView<*>?) {}
    }
  }

  private fun createLatencyOptionsList(): List<HashMap<String, String?>> {
    val latencyOptions = ArrayList<HashMap<String, String?>>()
    for (i in LATENCY_OPTIONS.indices) {
      val option = HashMap<String, String?>()
      option[getString(R.string.description_key)] =
        LATENCY_OPTIONS[i]
      option[getString(R.string.value_key)] = i.toString()
      latencyOptions.add(option)
    }
    return latencyOptions
  }

  private fun restoreStates() {
    // get saved states
    val sharedPref = getSharedPreferences(SHARRED_PREF_NAME, MODE_PRIVATE)
    mLatencyOption = sharedPref.getInt(SHARED_PREF_LATENCY, 0)
    mIp = sharedPref.getString(SHARED_PREF_IP, null) ?: mIp
    mPort = sharedPref.getInt(SHARED_PREF_PORT, mPort)
    mMtu = sharedPref.getInt(SHARED_PREF_MTU, mMtu)
    mMaxLatency = sharedPref.getInt(SHARED_PREF_MAX_LATENCY, mMaxLatency)
    mNumChannel = sharedPref.getInt(SHARED_PREF_NUM_CHANNEL, mNumChannel)
    mMaskChannel = sharedPref.getInt(SHARED_PREF_MASK_CHANNEL, mMaskChannel)
  }

  private fun syncViewsWithStates() {
    // sync ui with saved preferences
    mLatencySpinner.setSelection(mLatencyOption)
    mIpEdit.setText(mIp)
    mPortEdit.setText(mPort.toString())
    mMtuEdit.setText(mMtu.toString())
    mMaxLatencyEdit.setText(mMaxLatency.toString())
    mNumChannelEdit.setText(mNumChannel.toString())
    mMaskChannelEdit.setText(mMaskChannel.toString())
  }

  private fun hideKb() {
    val inputManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
    currentFocus?.let {
      inputManager.hideSoftInputFromWindow(
        it.windowToken,
        InputMethodManager.HIDE_NOT_ALWAYS
      )
    }
  }

  companion object {
    private val LATENCY_OPTIONS = arrayOf("Low Latency", "None", "Power Saving")
    private const val SHARRED_PREF_NAME = "PulseDroidRtp"
    private const val SHARED_PREF_LATENCY = "latency"
    private const val SHARED_PREF_IP = "ip"
    private const val SHARED_PREF_PORT = "port"
    private const val SHARED_PREF_MTU = "mtu"
    private const val SHARED_PREF_MAX_LATENCY = "max_latency"
    private const val SHARED_PREF_NUM_CHANNEL = "num_channel"
    private const val SHARED_PREF_MASK_CHANNEL = "mask_channel"
    private const val STATE_PLAYING = "playing"
    private const val STATUS_CHECK_INTERVAL = 1000
  }
}