# PulseDroid Rtp

Similar to [PulseDroid](https://github.com/dront78/PulseDroid), but
using `module-rtp-send` instead of `module-simple-protocol-tcp`. It
turns out that my WIFI network is lossy and UDP works better. The code
references a lot from the official
[hello-oboe](https://github.com/google/oboe/blob/master/samples/hello-oboe/)
example.

The icon can be found
[here](https://thenounproject.com/term/headphones/2494847/),
"Headphones by Crystal Gordon from the Noun Project", licensed with
Creative Commons. It looks good, and I am not using it as a
trademark. If this causes you trouble, please contact me.

The following is the script I use to setup my desktop:

```bash
pactl unload-module module-null-sink
pactl unload-module module-rtp-send
pactl load-module module-null-sink sink_name=rtp format=s16be channels=2 rate=48000
pactl load-module module-rtp-send source=rtp.monitor destination=224.0.0.56 port=4010 mtu=320
```

If the app is granted permission to start on boot, and was playing music before last shutdown,
it will continue playing music upon next boot.

The following could be used to start playing using adb:

```bash
# start activity
adb shell am start -n 'me.wenxinwang.pulsedroidrtp/.MainActivity'

# setup params
adb shell am startservice -n 'me.wenxinwang.pulsedroidrtp/.PulseRtpAudioService' -a 'android.intent.action.MEDIA_BUTTON' -d 'udp://224.0.0.56:4010/?latency=0\&mtu=320\&max_latency=300\&num_channel=2\&mask_channel=0'
# or use start-foreground-service instead of startservice if things don't work

# toggle playing
adb shell input keyevent 85
```

Here's something that still confuses me:

- If mtu is set to 1280, there is noticable delay between audio and
  video (audio lags behind lips movement); if it's set to 320, I
  cannot notice the delay. 1280B contains 320 samples, which should be
  less than 7ms long. I wonder why this makes the delay noticable.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/me.wenxinwang.pulsedroidrtp/)
