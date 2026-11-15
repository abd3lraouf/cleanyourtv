# Camera Pop-up

When a camera on the home recorder (DVR/NVR) sees motion, its picture opens in a small window in the bottom
corner of the TV, over whatever is playing, and closes by itself 30 seconds after the motion stops. The window
never takes focus: the remote keeps driving the app on screen.

- **Motion:** ONVIF events. The add-on opens one pull-point subscription (`CreatePullPointSubscription`) on
  `http://<recorder>/onvif/event`, then long-polls `PullMessages` (20 s), so an idle recorder costs one small
  request every 20 s. Any topic with `Motion` in it counts (`RuleEngine/CellMotionDetector/Motion`,
  `VideoSource/MotionAlarm`); the channel comes from `GetProfiles`. Login is a WS-Security digest stamped with
  the recorder's own clock. A refused login waits 10 minutes, so a wrong password cannot lock the account.
- **Video:** the channel's RTSP sub-stream, `rtsp://<recorder>:554/unicast/c<N>/s1/live` (Uniview XVR/NVR),
  through Media3's RTSP client over TCP, muted, in a 480x270 overlay (a quarter of the screen width).
- **Quiet:** pop-ups on/off, a cooldown per camera after its window closes (none to 30 min, 2 min by default),
  and which cameras may pop up, all on the app's screen. Motion on a second camera switches the window only when
  the first one has gone quiet.

Tried on a Sony BRAVIA (Android TV 11) with a Uniview XVR301-08Q3.

## Install

```bash
ADB="adb -s <tv ip>:5555" ./install.sh
ADB="adb -s <tv ip>:5555" ./provision.sh <recorder ip> <login file> [channels=1-4] [cooldown_s=300]
```

The login file is two lines, user name then password. Keep it outside the repo; a viewer-only account on the
recorder is enough. Test the window without motion:

```bash
adb shell am broadcast -a io.github.abd3lraouf.campopup.TEST --ei channel 1
adb shell am broadcast -a io.github.abd3lraouf.campopup.TEST --ei channel 1 --es url rtsp://<host>:8554/test
adb logcat -s CamPopup
```

Only the shell can send the test broadcast or write the login (both need `WRITE_SECURE_SETTINGS`).

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`: talks to the recorder on the home network only (ONVIF over HTTP, RTSP).
- `SYSTEM_ALERT_WINDOW`: the pop-up window over other apps; `install.sh` grants it with `appops`.
- `RECEIVE_BOOT_COMPLETED`: starts the watcher at boot.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`: the watcher is a background
  service that runs all the time, with a silent notification.

The launcher itself still has no network permission; this is a separate app.
