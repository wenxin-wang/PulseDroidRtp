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

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.appcompat.app.AppCompatActivity
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.audioBufferSize
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.framesPerBurstStr
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.numUnderrun
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferCapacity
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferHeadMove
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferHeadMoveReq
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferSize
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferTailMove
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktBufferTailMoveReq
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.pktReceived
import me.wenxinwang.pulsedroidrtp.PulseRtpAudioEngine.sampleRateStr
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

    private val mParams = PulseRtpAudioEngine.Params()
    private var mPlaying = false

    private lateinit var mMediaBrowser: MediaBrowserCompat
    private lateinit var mHandler: Handler
    private lateinit var mStatusChecker: Runnable

    private val mMediaBrowserConnectionCallback: MediaBrowserCompat.ConnectionCallback =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                super.onConnected()
                mMediaBrowser.sessionToken.also { token ->
                    val mediaController = MediaControllerCompat(this@MainActivity, token)
                    MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
                }
                buildTransportControls()
            }
        }

    private val mMediaControllerCallback: MediaControllerCompat.Callback =
        object : MediaControllerCompat.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackStateCompat) {
                super.onPlaybackStateChanged(state)
                val playing = when (state.state) {
                    PlaybackStateCompat.STATE_PLAYING -> true
                    else -> false
                }
                if (playing == mPlaying) {
                    return
                }
                mPlaying = playing
                togglePlayUI()
                updateStatus()
            }
        }

    private fun buildTransportControls() {
        val mediaController = MediaControllerCompat.getMediaController(this)
        mButton.setOnClickListener {
            hideKb()
            when (mediaController.playbackState.state) {
                PlaybackStateCompat.STATE_PLAYING -> {
                    stopPlaying(mediaController)
                }
                PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.STATE_STOPPED -> {
                    startPlaying(mediaController)
                }
                else -> {
                }
            }
        }
        mPlaying = when (mediaController.playbackState.state) {
            PlaybackStateCompat.STATE_PLAYING -> true
            else -> false
        }
        togglePlayUI()
        mediaController.registerCallback(mMediaControllerCallback)
    }

    /**
     * Called when the user touches the button
     */
    private fun togglePlayUI() {
        // Do something in response to button click
        if (mPlaying) {
            startUpdateStatusTimer()
            mButton.setText(R.string.stop)
        } else {
            stopUpdateStatusTimer()
            mButton.setText(R.string.play)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupViews()
        restoreParams()
        syncViewsWithStates()
        // dynamic states
        mHandler = Handler()
        mStatusChecker = Runnable {
            updateStatus()
            mHandler.postDelayed(mStatusChecker, STATUS_CHECK_INTERVAL.toLong())
        }
        setInfoMsg("""sampleRate: $sampleRateStr, framesPerBurst: $framesPerBurstStr""")
        // Create MediaBrowserServiceCompat
        mMediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, PulseRtpAudioService::class.java),
            mMediaBrowserConnectionCallback,
            intent.extras
        )
        mPlaying = false
    }

    override fun onStart() {
        super.onStart()
        mMediaBrowser.connect()
    }

    public override fun onStop() {
        super.onStop()
        // (see "stay in sync with the MediaSession")
        MediaControllerCompat.getMediaController(this)
            ?.unregisterCallback(mMediaControllerCallback)
        mMediaBrowser.disconnect()
    }

    /*
       * Creating engine in onResume() and destroying in onPause() so the stream retains exclusive
       * mode only while in focus. This allows other apps to reclaim exclusive stream mode.
       */
    override fun onResume() {
        super.onResume()
        volumeControlStream = AudioManager.STREAM_MUSIC
        // StartPlaying();
    }

    override fun onPause() {
        // StopPlaying();
        super.onPause()
    }

    override fun onDestroy() {
        stopPlaying(MediaControllerCompat.getMediaController(this))
        super.onDestroy()
    }

    private fun startPlaying(mediaController: MediaControllerCompat): Boolean {
        // read params from views
        mParams.ip = mIpEdit.text.toString().takeIf { it.isNotEmpty() } ?: run {
            setInfoMsg("Could not get ip")
            return false
        }
        mParams.port = mPortEdit.text.toString().toIntOrNull() ?: run {
            setInfoMsg("Could not get port")
            return false
        }
        mParams.mtu = mMtuEdit.text.toString().toIntOrNull() ?: run {
            setInfoMsg("Could not get mtu")
            return false
        }
        mParams.maxLatency = mMaxLatencyEdit.text.toString().toIntOrNull() ?: run {
            setInfoMsg("Could not get max latency")
            return false
        }
        mParams.numChannel = mNumChannelEdit.text.toString().toIntOrNull() ?: run {
            setInfoMsg("Could not get channel num")
            return false
        }
        mParams.maskChannel = mMaskChannelEdit.text.toString().toIntOrNull() ?: run {
            setInfoMsg("Could not get channel num")
            return false
        }
        mParams.saveToSharedPref(this)
        PulseRtpAudioService.toggleServiceWithIntent(this, mParams.toUri())
        return true
    }

    private fun stopPlaying(mediaController: MediaControllerCompat) {
        mediaController.transportControls.pause()
    }

    private fun startUpdateStatusTimer() {
        mStatusChecker.run()
    }

    private fun stopUpdateStatusTimer() {
        mHandler.removeCallbacks(mStatusChecker)
    }

    private fun updateStatus() {
        val infoMsg = "sampleRate: $sampleRateStr, framesPerBurst: $framesPerBurstStr" +
            if (mPlaying) """
audioBuffer: $audioBufferSize, underRun: $numUnderrun
pktBuffer: $pktBufferSize/$pktBufferCapacity $pktReceived
r: $pktBufferHeadMoveReq/$pktBufferHeadMove
w: $pktBufferTailMoveReq/$pktBufferTailMove""" else ""
        setInfoMsg(infoMsg)
    }

    private fun setInfoMsg(infoMsg: String) {
        runOnUiThread { mInfo.text = infoMsg }
    }

    private fun setupViews() {
        mInfo = findViewById(R.id.info)
        mButton = findViewById(R.id.play)
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
                mParams.latencyOption = i
            }

            override fun onNothingSelected(adapterView: AdapterView<*>?) {}
        }
    }

    private fun createLatencyOptionsList(): List<HashMap<String, String?>> {
        val latencyOptions = ArrayList<HashMap<String, String?>>()
        for (i in PulseRtpAudioEngine.LATENCY_OPTIONS.indices) {
            val option = HashMap<String, String?>()
            option[getString(R.string.description_key)] =
                PulseRtpAudioEngine.LATENCY_OPTIONS[i]
            option[getString(R.string.value_key)] = i.toString()
            latencyOptions.add(option)
        }
        return latencyOptions
    }

    private fun restoreParams() {
        PulseRtpAudioEngine.initDefaultValues(this)
        mParams.fromSharedPref(this)
    }

    private fun syncViewsWithStates() {
        // sync ui with saved preferences
        mLatencySpinner.setSelection(mParams.latencyOption)
        mIpEdit.setText(mParams.ip)
        mPortEdit.setText(mParams.port.toString())
        mMtuEdit.setText(mParams.mtu.toString())
        mMaxLatencyEdit.setText(mParams.maxLatency.toString())
        mNumChannelEdit.setText(mParams.numChannel.toString())
        mMaskChannelEdit.setText(mParams.maskChannel.toString())
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
        private const val STATUS_CHECK_INTERVAL = 1000
    }
}