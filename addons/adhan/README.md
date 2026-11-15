# Adhan

Plays the adhan on the TV at the five prayer times. At Fajr, Dhuhr, Asr, Maghrib and Isha (not Sunrise), if the
TV's screen is on, it shows the prayer's name and time in large type over whatever is playing and plays the adhan.
SmartTube, VLC and other players pause and carry on after it, because it takes transient audio focus. OK or Back
closes the notice and stops the sound; the notice closes by itself after 3 minutes. If the TV is off or asleep
nothing happens, and an alarm that arrives more than 10 minutes late is not rung.

The times come from the [Prayer Times](../prayer-times/) add-on (`com.majnioui.tv.prayer`), read from the same
content provider the launcher's prayer panel reads, so all three agree. Prayer Times keeps its own small reminder
card and chime; this add-on adds the full notice and the adhan itself.

## How it works

- **Times.** `content://com.majnioui.tv.prayer.times/today`, columns `key`, `label`, `minutes` (after local
  midnight), `day` (`yyyy-MM-dd`), `place`. The last times read are kept, and used for up to 3 days while
  Prayer Times has none for today (it is offline, say).
- **Alarms.** One exact alarm (`setExactAndAllowWhileIdle`) for the next enabled prayer, one more alarm a few
  minutes after midnight to read the new day, every 20 minutes while there are no times. Both are set again at
  boot, after an update, when the clock or time zone changes, after each alarm, and when Prayer Times announces
  new times (a `JobScheduler` content-URI trigger on its provider).
- **Notice.** Android 10+ does not let an app open a screen from the background unless it may draw over other
  apps, so the overlay permission is granted with `appops` (below). Without it the notice comes as a full-screen
  notification, which many TV launchers show only as a heads-up.
- **Sound.** The built-in recording is in [AUDIO_SOURCE.md](AUDIO_SOURCE.md). A recording of your own wins:
  put `adhan.mp3` (and, for Fajr, `fajr.mp3`; `.m4a` and `.ogg` work too) in `/sdcard/Adhan/`. If nothing can
  play, three short tones sound instead.

The app's screen shows the next adhan and has the choices: sound on or off, which of the five prayers, the volume
(a share of the TV's media volume), and a button to try it now.

## Permissions

| Permission | Why |
|---|---|
| `SCHEDULE_EXACT_ALARM` | The alarm at each prayer time, to the minute. |
| `RECEIVE_BOOT_COMPLETED` | Start at boot: alarms do not survive a restart, so the next one is set again. |
| `SYSTEM_ALERT_WINDOW` | Display over other apps: what lets Android open the notice over the picture from the background. Granted with `appops`. |
| `USE_FULL_SCREEN_INTENT`, `POST_NOTIFICATIONS` | The fallback notice when the overlay grant is missing, and the "adhan playing" notification with its Stop button. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The adhan plays from a short foreground service so Android does not cut it off. It stops when the adhan ends. |
| `WAKE_LOCK` | Keeps the player running to the end of the recording. |
| `READ_EXTERNAL_STORAGE` (Android 12 and older), `READ_MEDIA_AUDIO` (Android 13+) | Only for your own recordings in `/sdcard/Adhan/`; granted with `pm grant` if you use them. |

Package visibility is limited to `com.majnioui.tv.prayer`. No internet permission: nothing leaves the TV. No
accounts, telemetry, ads or libraries.

## Build and install

JDK 17 and Android SDK platform 34, as for the launcher. From this folder, with ADB on:

```bash
./gradlew testReleaseUnitTest lintRelease assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appops set io.github.abd3lraouf.adhan SYSTEM_ALERT_WINDOW allow
adb shell am start -n io.github.abd3lraouf.adhan/.StatusActivity   # once, so Android delivers boot events
# Your own recordings (optional):
adb shell mkdir -p /sdcard/Adhan && adb push adhan.mp3 fajr.mp3 /sdcard/Adhan/
adb shell pm grant io.github.abd3lraouf.adhan android.permission.READ_EXTERNAL_STORAGE
```

Try it without waiting for the next prayer (only the ADB shell can send this):

```bash
adb shell am broadcast -a io.github.abd3lraouf.adhan.TEST -p io.github.abd3lraouf.adhan --es prayer Fajr
adb shell dumpsys alarm | grep -A1 adhan     # the next FIRE alarm and the after-midnight REFRESH
adb logcat -s Adhan
```

Tested on a Sony BRAVIA (Android 11), with Prayer Times 1.0.
