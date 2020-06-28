package me.wenxinwang.pulsedroidrtp;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.SimpleAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String[] LATENCY_OPTIONS = {"Low Latency", "None", "Power Saving"};

    private Spinner mLatencySpinner;
    private TextView mInfo = null;
    private Button mButton = null;

    private Boolean mPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mInfo = findViewById(R.id.info);
        // StartPlaying();
        mButton = (Button)findViewById(R.id.play);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePlay(view);
            }
        });

        setupLatencySpinner();
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
    public void togglePlay(View view) {
        // Do something in response to button click
        if (mPlaying) {
            mPlaying = false;
            StopPlaying();
            mButton.setText(R.string.play);
        } else {
            mPlaying = true;
            StartPlaying();
            mButton.setText(R.string.stop);
        }
    }

    private void StartPlaying() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1){
            AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            String sampleRateStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            String framesPerBurstStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            final String infoMsg =
                "Default sampleRate:" + sampleRateStr + ", framesPerBurst:" + framesPerBurstStr;
            setInfoMsg(infoMsg);
        } else {
            setInfoMsg("Older version start");
        }

        PulseRtpAudioEngine.create(this);
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
                PulseRtpAudioEngine.setLatencyOption(i);
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