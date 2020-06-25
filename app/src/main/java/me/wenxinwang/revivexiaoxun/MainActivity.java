package me.wenxinwang.revivexiaoxun;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
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
    }

    /*
     * Creating engine in onResume() and destroying in onPause() so the stream retains exclusive
     * mode only while in focus. This allows other apps to reclaim exclusive stream mode.
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1){
            AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            String sampleRateStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            String framesPerBurstStr = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            final String infoMsg =
                "Default sampleRate:" + sampleRateStr + ", framesPerBurst:" + framesPerBurstStr + framesPerBurstStr;
            setInfoMsg(infoMsg);
        } else {
            setInfoMsg("Older version start");
        }

        AudioEngine.create(this);
    }

    @Override
    protected void onPause() {
        AudioEngine.delete();
        super.onPause();
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