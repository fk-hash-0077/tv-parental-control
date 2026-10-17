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

echo "Making TV Guard the Device Owner ..."
adb -s "$TV" shell dpm set-device-owner "$PKG/.AdminReceiver"

adb -s "$TV" shell am start -n "$PKG/.MainActivity"
echo "Done. The TV now shows the pairing code for the phone app."
