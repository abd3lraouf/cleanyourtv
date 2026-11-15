# Wi-Fi Rescue

Fixes "after a reboot the TV is on Wi-Fi but has no internet until I switch Wi-Fi off and on". After a power cut
the TV often boots before the router has internet; Android's network check (and strict private DNS) then backs
off for minutes. This add-on watches the Wi-Fi network from boot:

1. Connected without internet (not `VALIDATED`): at 20, 40 and 60 s it asks Android to check again
   (`reportNetworkConnectivity`).
2. Still no internet at 75 s: Wi-Fi off, 4 s, Wi-Fi on.
3. At most one switch every 3 minutes, 3 in a row; a working connection resets the count.

It never changes DNS or any other setting. `targetSdk` is 28 on purpose: newer targets can't switch Wi-Fi.

## Install

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n io.github.abd3lraouf.wifirescue/.StatusActivity   # once, so Android delivers boot events
adb logcat -s WifiRescue
```

The app's screen shows the last action and how many times it switched Wi-Fi.
