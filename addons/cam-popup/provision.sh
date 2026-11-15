#!/usr/bin/env bash
# Hand Camera Pop-up the recorder's address and login.
#   ADB="adb -s <tv ip>:5555" addons/cam-popup/provision.sh <recorder ip> <login file> [key=value ...]
# The login file holds the user name on its first line and the password on its second. The two go to the TV
# over ADB's stdin, into the app's write-only provider: never on a command line, never printed, never in a file
# on the TV that outlives the import. Extra key=value settings: enabled=1|0, channels=1-4,7, cooldown_s=120,
# hold_s=30, stream=s1 (sub-stream) or s0 (main).
set -euo pipefail
HOST="${1:?usage: $0 <recorder ip> <login file> [key=value ...]}"
LOGIN="${2:?usage: $0 <recorder ip> <login file> [key=value ...]}"
shift 2
ADB=${ADB:-adb}
PKG=io.github.abd3lraouf.campopup
[ -r "$LOGIN" ] || { echo "cannot read $LOGIN" >&2; exit 1; }
{
  printf 'host=%s\n' "$HOST"
  awk 'NR==1 { sub(/\r$/, ""); printf "user=%s\n", $0 } NR==2 { sub(/\r$/, ""); printf "password=%s\n", $0 }' "$LOGIN"
  for kv in "$@"; do printf '%s\n' "$kv"; done
} | $ADB shell "content write --uri content://$PKG.config/provision.txt" >/dev/null
sleep 2
# The app logs how many settings it took, never their values.
$ADB shell "logcat -d -s CamPopup:I" | grep -E -o 'provisioned [0-9]+ settings|provisioning failed.*' | tail -n 1
