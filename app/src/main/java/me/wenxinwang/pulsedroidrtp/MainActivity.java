package me.wenxinwang.pulsedroidrtp;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
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

    private static final String STATE_PLAYING = "playing";

    private Spinner mLatencySpinner = null;
    private EditText mIpEdit = null;
    private EditText mPortEdit = null;
    private EditText mMtuEdit = null;
    private TextView mInfo = null;
    private Button mButton = null;

    private int mLatencyOption = 0;
    private String mIp = "224.0.0.56";
    private int mPort = 4010;
    private int mMtu = 320;

    private Boolean mPlaying = false;

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
        mButton = (Button)findViewById(R.id.play);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePlay();
            }
        });

        mIpEdit = (EditText)findViewById(R.id.ipEdit);
        mPortEdit = (EditText)findViewById(R.id.portEdit);
        mMtuEdit = (EditText)findViewById(R.id.mtuEdit);

        setupLatencySpinner();

        SharedPreferences sharedPref = getSharedPreferences(
            SHARRED_PREF_NAME, Context.MODE_PRIVATE);
        mLatencyOption = sharedPref.getInt(SHARED_PREF_LATENCY, 0);
        mIp = sharedPref.getString(SHARED_PREF_IP, mIp);
        mPort = sharedPref.getInt(SHARED_PREF_PORT, mPort);
        mMtu = sharedPref.getInt(SHARED_PREF_MTU, mMtu);

        mLatencySpinner.setSelection(mLatencyOption);
        mIpEdit.setText(mIp);
        mPortEdit.setText(String.valueOf(mPort));
        mMtuEdit.setText(String.valueOf(mMtu));

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
        StopPlaying();
        super.onDestroy();
    }

    /** Called when the user touches the button */
    public void togglePlay() {
        // Do something in response to button click
        if (mPlaying) {
            mPlaying = false;
            StopPlaying();
            mButton.setText(R.string.play);
            return;
        }
        boolean success = StartPlaying();
        if (success) {
            mPlaying = true;
            mButton.setText(R.string.stop);
        }
    }

    private boolean StartPlaying() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1){
            AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            String sampleRateStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            String framesPerBurstStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            final String infoMsg =
                "Default sampleRate: " + sampleRateStr + ", framesPerBurst: " + framesPerBurstStr;
            setInfoMsg(infoMsg);
        } else {
            setInfoMsg("Older version start");
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
        } catch(NumberFormatException nfe) {
            setInfoMsg("Could not parse port " + nfe);
            return false;
        }
        try {
            int mtu = Integer.parseInt(mMtuEdit.getText().toString());
            if (mtu > 0) {
                mMtu = mtu;
            }
        } catch(NumberFormatException nfe) {
            setInfoMsg("Could not parse port " + nfe);
            return false;
        }

        boolean success = PulseRtpAudioEngine.create(this, mLatencyOption, mIp, mPort, mMtu);
        if (!success) {
            setInfoMsg("Could not create PulseRtpAudioEngine");
            return false;
        }

        SharedPreferences sharedPref = getSharedPreferences(
            SHARRED_PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putInt(SHARED_PREF_LATENCY, mLatencyOption);
        editor.putString(SHARED_PREF_IP, mIp);
        editor.putInt(SHARED_PREF_PORT, mPort);
        editor.putInt(SHARED_PREF_MTU, mMtu);
        editor.commit();
        return true;
    }

    private void StopPlaying() {
        setInfoMsg("");
        PulseRtpAudioEngine.delete();
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