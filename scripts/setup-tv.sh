#!/usr/bin/env bash
# One-time TV setup over Wi-Fi: installs TV Guard, grants usage access and
# makes it Device Owner.
#
# Usage:
#   scripts/setup-tv.sh <tv-ip:port> [tv-debug.apk] [pair-ip:port pairing-code]
#
# The TV must be freshly reset with no Google account and no extra profiles,
# otherwise the Device Owner step fails.
set -euo pipefail

if [ $# -lt 1 ]; then
  sed -n '2,10p' "$0"
  exit 1
fi

TV="$1"
APK="${2:-tv-debug.apk}"
PKG=com.tvguard.tv

if [ $# -ge 4 ]; then
  echo "Pairing with $3 ..."
  adb pair "$3" "$4"
fi

echo "Connecting to $TV ..."
adb connect "$TV"
adb -s "$TV" wait-for-device

echo "Installing $APK ..."
adb -s "$TV" install -r "$APK"

echo "Granting usage access ..."
adb -s "$TV" shell appops set "$PKG" GET_USAGE_STATS allow

echo "Allowing the 5-minute warning to show over other apps ..."
adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

# TCL TVs block apps from starting in the background (at boot, after an update, or for title
# access) unless they have this app-op. Other TVs don't have it, so a failure is ignored.
echo "Letting TV Guard start by itself (TCL TVs) ..."
# Older TCL models (Android 11) call it APP_AUTO_START and also check APP_ASSOC_START.
for op in AUTO_START APP_AUTO_START APP_ASSOC_START; do
  adb -s "$TV" shell appops set "$PKG" "$op" allow 2>/dev/null || true
done

echo "Letting TV Guard see what's playing, for the phone's Now playing card ..."
adb -s "$TV" shell cmd notification allow_listener "$PKG/$PKG.MediaListener" || true

echo "Making TV Guard the Device Owner ..."
adb -s "$TV" shell dpm set-device-owner "$PKG/.AdminReceiver"

adb -s "$TV" shell am start -n "$PKG/.MainActivity"
echo "Done. The TV now shows the pairing code for the phone app."
