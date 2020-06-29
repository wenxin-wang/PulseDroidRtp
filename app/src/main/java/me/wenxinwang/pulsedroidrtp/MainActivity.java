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
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SimpleAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String[] LATENCY_OPTIONS = {"Low Latency", "None", "Power Saving"};
    private static final String SHARRED_PREF_NAME = "PulseDroidRtp";
    private static final String SHARED_PREF_LATENCY = "latency";
    private static final String SHARED_PREF_IP = "ip";
    private static final String SHARED_PREF_PORT = "port";
    private static final String SHARED_PREF_MTU = "mtu";
    private static final String SHARED_PREF_MAX_LATENCY = "max_latency";

    private static final String STATE_PLAYING = "playing";

    private static final int STATUS_CHECK_INTERVAL = 1000;

    private Spinner mLatencySpinner = null;
    private EditText mIpEdit = null;
    private EditText mPortEdit = null;
    private EditText mMtuEdit = null;
    private EditText mMaxLatencyEdit = null;
    private TextView mInfo = null;
    private Button mButton = null;

    private int mLatencyOption = 0;
    private String mIp = "224.0.0.56";
    private int mPort = 4010;
    private int mMtu = 320;
    private int mMaxLatency = 200;

    private Boolean mPlaying = false;
    private Handler mHandler = null;
    private Runnable mStatusChecker = null;
    private String mSampleRateStr = "";
    private String mFramesPerBurstStr = "";

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // Make sure to call the super method so that the states of our views are saved
        super.onSaveInstanceState(outState);
        // Save our own state now
        outState.putSerializable(STATE_PLAYING, mPlaying);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mInfo = findViewById(R.id.info);
        // StartPlaying();
        mButton = (Button) findViewById(R.id.play);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePlay();
            }
        });

        mIpEdit = (EditText) findViewById(R.id.ipEdit);
        mPortEdit = (EditText) findViewById(R.id.portEdit);
        mMtuEdit = (EditText) findViewById(R.id.mtuEdit);
        mMaxLatencyEdit = (EditText) findViewById(R.id.maxLatencyEdit);

        setupLatencySpinner();

        SharedPreferences sharedPref = getSharedPreferences(
            SHARRED_PREF_NAME, Context.MODE_PRIVATE);
        mLatencyOption = sharedPref.getInt(SHARED_PREF_LATENCY, 0);
        mIp = sharedPref.getString(SHARED_PREF_IP, mIp);
        mPort = sharedPref.getInt(SHARED_PREF_PORT, mPort);
        mMtu = sharedPref.getInt(SHARED_PREF_MTU, mMtu);
        mMaxLatency = sharedPref.getInt(SHARED_PREF_MAX_LATENCY, mMaxLatency);

        mLatencySpinner.setSelection(mLatencyOption);
        mIpEdit.setText(mIp);
        mPortEdit.setText(String.valueOf(mPort));
        mMtuEdit.setText(String.valueOf(mMtu));
        mMaxLatencyEdit.setText(String.valueOf(mMaxLatency));

        mPlaying = false;
        if (savedInstanceState != null) {
            boolean wasPlaying = savedInstanceState.getBoolean(STATE_PLAYING, false);
            if (wasPlaying) {
                togglePlay();
            }
        }
    }

    /*
     * Creating engine in onResume() and destroying in onPause() so the stream retains exclusive
     * mode only while in focus. This allows other apps to reclaim exclusive stream mode.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // StartPlaying();
    }

    @Override
    protected void onPause() {
        // StopPlaying();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopPlaying();
        super.onDestroy();
    }

    /**
     * Called when the user touches the button
     */
    public void togglePlay() {
        // Do something in response to button click
        if (mPlaying) {
            mPlaying = false;
            stopPlaying();
            mButton.setText(R.string.play);
            return;
        }
        boolean success = startPlaying();
        if (success) {
            mPlaying = true;
            mButton.setText(R.string.stop);
        }
    }

    private boolean startPlaying() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            mSampleRateStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            mFramesPerBurstStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        }

        String ip = mIpEdit.getText().toString();
        if (!ip.isEmpty()) {
            mIp = ip;
        }
        try {
            int port = Integer.parseInt(mPortEdit.getText().toString());
            if (port > 0 && port <= 65535) {
                mPort = port;
            }
        } catch (NumberFormatException nfe) {
            setInfoMsg("Could not parse port " + nfe);
            return false;
        }
        try {
            int mtu = Integer.parseInt(mMtuEdit.getText().toString());
            if (mtu > 0) {
                mMtu = mtu;
            }
        } catch (NumberFormatException nfe) {
            setInfoMsg("Could not parse port " + nfe);
            return false;
        }
        try {
            int maxLatency = Integer.parseInt(mMaxLatencyEdit.getText().toString());
            if (maxLatency > 0) {
                mMaxLatency = maxLatency;
            }
        } catch (NumberFormatException nfe) {
            setInfoMsg("Could not parse max latency " + nfe);
            return false;
        }

        boolean success = PulseRtpAudioEngine.create(this, mLatencyOption, mIp, mPort, mMtu, mMaxLatency);
        if (!success) {
            setInfoMsg("Could not create PulseRtpAudioEngine");
            return false;
        }

        startUpdateStatusTimer();

        SharedPreferences sharedPref = getSharedPreferences(
            SHARRED_PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putInt(SHARED_PREF_LATENCY, mLatencyOption);
        editor.putString(SHARED_PREF_IP, mIp);
        editor.putInt(SHARED_PREF_PORT, mPort);
        editor.putInt(SHARED_PREF_MTU, mMtu);
        editor.putInt(SHARED_PREF_MAX_LATENCY, mMaxLatency);
        editor.commit();
        return true;
    }

    private void stopPlaying() {
        stopUpdateStatusTimer();
        setInfoMsg("");
        PulseRtpAudioEngine.delete();
    }

    private void startUpdateStatusTimer() {
        if (mHandler == null) {
            mHandler = new Handler();
        }
        if (mStatusChecker == null) {
            mStatusChecker = new Runnable() {
                @Override
                public void run() {
                    updateStatus();
                    mHandler.postDelayed(mStatusChecker, STATUS_CHECK_INTERVAL);
                }
            };
        }
        mStatusChecker.run();
    }

    private void stopUpdateStatusTimer() {
        if (mHandler == null) {
            return;
        }
        mHandler.removeCallbacks(mStatusChecker);
    }

    private void updateStatus() {
        final String infoMsg =
            "sampleRate: " + mSampleRateStr + ", framesPerBurst: " + mFramesPerBurstStr +
                "\naudioBuffer: " + PulseRtpAudioEngine.getAudioBufferSize() +
                ", underRun: " + PulseRtpAudioEngine.getNumUnderrun() +
                "\npktBuffer: " + PulseRtpAudioEngine.getPktBufferSize() +
                "/" + PulseRtpAudioEngine.getPktBufferCapacity() +
                "\nr: " + PulseRtpAudioEngine.getPktBufferHeadMoveReq() +
                "/" + PulseRtpAudioEngine.getPktBufferHeadMove() +
                "\nw: " + PulseRtpAudioEngine.getPktBufferTailMoveReq() +
                "/" + PulseRtpAudioEngine.getPktBufferTailMove();
        setInfoMsg(infoMsg);
    }

    private void setInfoMsg(final String infoMsg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mInfo.setText(infoMsg);
            }
        });
    }

    private void setupLatencySpinner() {
        mLatencySpinner = findViewById(R.id.latencyOptionSpinner);
        mLatencySpinner.setAdapter(new SimpleAdapter(
            this,
            createLatencyOptionsList(),
            R.layout.latency_spinner, // the xml layout
            new String[]{getString(R.string.description_key)}, // field to display
            new int[]{R.id.latencyOption} // View to show field in
        ));

        mLatencySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                mLatencyOption = i;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
    }

    private List<HashMap<String, String>> createLatencyOptionsList() {

        ArrayList<HashMap<String, String>> latencyOptions = new ArrayList<>();

        for (int i = 0; i < LATENCY_OPTIONS.length; i++) {
            HashMap<String, String> option = new HashMap<>();
            option.put(getString(R.string.description_key), LATENCY_OPTIONS[i]);
            option.put(getString(R.string.value_key), String.valueOf(i));
            latencyOptions.add(option);
        }
        return latencyOptions;
    }
}
