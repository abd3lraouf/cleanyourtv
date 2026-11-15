# Camera Pop-up

When a camera on the home recorder (DVR/NVR) sees motion, its picture opens in a small window in the bottom
corner of the TV, over whatever is playing, and closes by itself 30 seconds after the motion stops. While it is
up it takes the remote: **Close** is lit first, so OK or Back dismisses it, and right moves to **Full screen**,
which opens that camera in the live view. Play/pause and the other media keys still reach the app underneath,
and volume is untouched. A dismissed camera stays quiet for its cooldown. Its Home tile, **Cameras**, opens every camera
live, full screen.

- **Motion:** ONVIF events. The add-on opens one pull-point subscription (`CreatePullPointSubscription`) on
  `http://<recorder>/onvif/event`, then long-polls `PullMessages` (20 s), so an idle recorder costs one small
  request every 20 s. Any topic with `Motion` in it counts (`RuleEngine/CellMotionDetector/Motion`,
  `VideoSource/MotionAlarm`), and so do person detectors (`HumanShapeDetect`); the channel comes from `GetProfiles`. Login is a WS-Security digest stamped with
  the recorder's own clock. A refused login waits 10 minutes, so a wrong password cannot lock the account.
- **Video:** the channel's RTSP sub-stream, `rtsp://<recorder>:554/unicast/c<N>/s1/live` (Uniview XVR/NVR),
  through Media3's RTSP client over TCP, muted, in a card a third of the screen wide (640x360 on 1080p).
- **Live view:** the cameras listed in `view=` (1-4 by default) in a grid of sub-streams (one camera fills the
  screen, two sit side by side, up to four make 2x2). OK opens the focused camera full screen on its main stream
  (`s0`), left/right steps through the others, Back returns to the grid, Menu opens the settings. Streams retry
  until they play, and no pop-up opens while the live view is on screen.
- **H.265:** Uniview recorders describe an H.265 stream with H.264's `sprop-parameter-sets` and no VPS, which
  Media3 refuses (`missing sprop-vps parameter`). `SdpFix` rewrites the SDP on the RTSP socket into
  `sprop-vps`/`sprop-sps`/`sprop-pps`, building the VPS from the SPS's profile and level.
- **Quiet:** pop-ups on/off, a cooldown per camera after its window closes (none to 30 min, 2 min by default),
  and which cameras may pop up, all on the app's screen. Motion on a second camera switches the window only when
  the first one has gone quiet.

The recorder only sends events for cameras whose own motion detection is on. Uniview recorders ship with it
off: turn it on per camera under Setup > Event > Motion Detection (the whole picture as the area, sensitivity
around 80 of 100 for a hallway). Higher sensitivity catches smaller movement and more false alarms (light
changes, insects at night).

Tried on a Sony BRAVIA (Android TV 11) with a Uniview XVR301-08Q3.

## Install

```bash
ADB="adb -s <tv ip>:5555" ./install.sh
ADB="adb -s <tv ip>:5555" ./provision.sh <recorder ip> <login file> [channels=1-4] [view=2,4] [cooldown_s=300]
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
