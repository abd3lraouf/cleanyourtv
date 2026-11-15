#!/usr/bin/env bash
# Build Camera Pop-up, install it on the TV, let it draw over other apps and start it.
#   ADB="adb -s <tv ip>:5555" addons/cam-popup/install.sh
# Then hand it the recorder's login with provision.sh.
set -euo pipefail
cd "$(dirname "$0")"
ADB=${ADB:-adb}
PKG=io.github.abd3lraouf.campopup
[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=$ANDROID_HOME" > local.properties; } || true
./gradlew assembleRelease -q --console=plain
$ADB install -r app/build/outputs/apk/release/app-release.apk
# The pop-up is an overlay; TV settings rarely show "Display over other apps", so ADB grants it.
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# Opened once so Android delivers BOOT_COMPLETED to it from now on.
$ADB shell am start -n $PKG/.StatusActivity >/dev/null
echo "installed; now: $0/../provision.sh <recorder ip> <login file>"
