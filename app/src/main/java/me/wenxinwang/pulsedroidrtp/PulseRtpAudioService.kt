package me.wenxinwang.pulsedroidrtp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver


class PulseRtpAudioService : MediaBrowserServiceCompat() {
    private lateinit var mMediaSession: MediaSessionCompat
    private lateinit var mStateBuilder: PlaybackStateCompat.Builder
    private lateinit var mWifiLock: WifiLock

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) {
                return
            }
            if (!BOOT_ACTIONS.contains(intent.action)) {
                return
            }
            val wasPlaying = PulseRtpAudioEngine.restorePlayState(context)
            if (!wasPlaying) {
                return
            }
            startServiceWithIntent(context, null, true)
        }
    }

    private val mNoisyReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            PulseRtpAudioEngine.destroy()
        }
    }

    private val mMediaSessionCallback: MediaSessionCompat.Callback =
        object : MediaSessionCompat.Callback() {
            override fun onStop() {
                super.onStop()
                stopPlay()
                PulseRtpAudioEngine.savePlayState(false, this@PulseRtpAudioService)
            }

            override fun onPause() {
                super.onPause()
                pausePlay()
                PulseRtpAudioEngine.savePlayState(false, this@PulseRtpAudioService)
            }

            override fun onPlay() {
                super.onPlay()
                PulseRtpAudioEngine.restoreUri(this@PulseRtpAudioService)?.let { uri ->
                    val success = startPlay(uri)
                    PulseRtpAudioEngine.savePlayState(success, this@PulseRtpAudioService)
                    if (!success) {
                        PulseRtpAudioEngine.unstoreUri(this@PulseRtpAudioService)
                    }
                }
            }
        }

    private fun startPlay(uri: Uri): Boolean {
        Log.e(MEDIA_SESSION_LOG_TAG, "start play ${uri.toString()}")
        if (PulseRtpAudioEngine.isPlaying()) {
            return true
        }
        PulseRtpAudioEngine.Params().let { params ->
            params.fromUri(uri)
            if (!PulseRtpAudioEngine.create(params)) {
                Log.e(MEDIA_SESSION_LOG_TAG, "Failed to create PulseRtpAudioEngine")
                return false
            }
        }
        initNoisyReceiver()
        acquireWifiLock()
        setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        return true
    }

    private fun pausePlay() {
        Log.e(MEDIA_SESSION_LOG_TAG, "pause play")
        if (!PulseRtpAudioEngine.isPlaying()) {
            return
        }
        PulseRtpAudioEngine.destroy()
        unregisterReceiver(mNoisyReceiver)
        stopForeground(false)
        releaseWifiLock()
        setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED)
    }

    private fun stopPlay() {
        Log.e(MEDIA_SESSION_LOG_TAG, "stop play")
        stopSelf()
        if (!PulseRtpAudioEngine.isPlaying()) {
            return
        }
        PulseRtpAudioEngine.destroy()
        unregisterReceiver(mNoisyReceiver)
        stopForeground(true)
        releaseWifiLock()
        setMediaPlaybackState(PlaybackStateCompat.STATE_STOPPED)
    }

    override fun onCreate() {
        super.onCreate()
        initMediaSession()
        initNotificationChannel()
        initWifiLock()

        // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
        setMediaPlaybackState(PlaybackStateCompat.STATE_STOPPED)
    }

    override fun onDestroy() {
        stopPlay()
        mMediaSession.isActive = false
        mMediaSession.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).apply {
                deleteNotificationChannel(NOTIFICATION_CHANNEL_ID)
            }
        }
        super.onDestroy()
    }

    private fun initMediaSession() {
        val mediaButtonReceiver = ComponentName(
            applicationContext,
            MediaButtonReceiver::class.java
        )
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
        mediaButtonIntent.setClass(this, MediaButtonReceiver::class.java)
        val pendingIntent =
            PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0)

        mStateBuilder = PlaybackStateCompat.Builder()
        mMediaSession = MediaSessionCompat(
            baseContext, MEDIA_SESSION_LOG_TAG, mediaButtonReceiver, pendingIntent).apply {
            // mMediaSessionCallback has methods that handle callbacks from a media controller
            setCallback(mMediaSessionCallback)
            // Set the session's token so that client activities can communicate with it.
            setSessionToken(sessionToken)
            isActive = true
        }
    }

    private fun initNoisyReceiver() {
        //Handles headphones coming unplugged. cannot be done through a manifest receiver
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(mNoisyReceiver, filter)
    }

    private fun initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel
            val name = getString(R.string.notification_channel_name)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance)
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).apply {
                createNotificationChannel(channel)
            }
        }
    }

    private fun initWifiLock() {
        mWifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                MEDIA_SESSION_WIFI_LOCK_TAG)
    }

    private fun acquireWifiLock() {
        mWifiLock.acquire()
    }

    private fun releaseWifiLock() {
        if (mWifiLock.isHeld) {
            mWifiLock.release()
        }
    }

    private fun setMediaPlaybackState(state: Int) {
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            startForeground(NOTIFICATION_ID, getNotificationBuilder(state).build())
            mStateBuilder.setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PAUSE)
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).apply {
                notify(NOTIFICATION_ID, getNotificationBuilder(state).build())
            }
            mStateBuilder.setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY)
        }
        mStateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
        mMediaSession.setPlaybackState(mStateBuilder.build())
    }

    private fun getNotificationBuilder(state: Int): NotificationCompat.Builder {
        val action = when (state) {
            PlaybackStateCompat.STATE_PLAYING -> NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                getString(R.string.pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PAUSE
                )
            )
            else -> NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                getString(R.string.play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PLAY
                )
            )
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentIntent(mMediaSession.controller.sessionActivity)
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(action)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mMediaSession.sessionToken)
                .setShowActionsInCompactView(0)
                // Add a cancel button
                .setShowCancelButton(true)
                .setCancelButtonIntent(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_STOP
                    )
                )
            )
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return if (TextUtils.equals(clientPackageName, packageName)) {
            BrowserRoot(getString(R.string.app_name), null)
        } else null
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem?>?>
    ) {
        result.sendResult(null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.data?.let { uri -> PulseRtpAudioEngine.commitUri(uri, this) }
        MediaButtonReceiver.handleIntent(mMediaSession, intent)
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        private const val MEDIA_SESSION_LOG_TAG = "pulsedroidrtp_session"
        private const val MEDIA_SESSION_WIFI_LOCK_TAG = "pulsedroidrtp_wifilock"
        private const val NOTIFICATION_CHANNEL_ID = "PulseDroidRtpMediaSessionNotificationChannel"
        private const val NOTIFICATION_ID = 1
        private val BOOT_ACTIONS = arrayOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON")

        fun startServiceWithIntent(context: Context, uri: Uri?, foreground: Boolean) {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON, uri, context, PulseRtpAudioService::class.java)
                .putExtra(Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        }
    }
}
