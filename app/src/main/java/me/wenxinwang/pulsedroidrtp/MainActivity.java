package me.wenxinwang.pulsedroidrtp;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    private TextView mInfo = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mInfo = findViewById(R.id.info);
        StartPlaying();
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
}