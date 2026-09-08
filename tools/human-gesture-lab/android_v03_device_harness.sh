#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
APK="${1:-apps/mobile/app/build/outputs/apk/debug/app-debug.apk}"
COMPONENT="com.cyclone.mobile/com.cyclone.mobile.debug.HumanGestureTestActivity"

mapfile -t DEVICES < <("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ ${#DEVICES[@]} -ne 1 ]]; then
  echo "Expected exactly one authorized Android device; found ${#DEVICES[@]}." >&2
  "$ADB_BIN" devices -l >&2
  exit 2
fi
SERIAL="${DEVICES[0]}"
ADB=("$ADB_BIN" -s "$SERIAL")

echo "serial=$SERIAL"
echo "model=$(${ADB[@]} shell getprop ro.product.model | tr -d '\r')"
echo "api=$(${ADB[@]} shell getprop ro.build.version.sdk | tr -d '\r')"
echo "build=$(${ADB[@]} shell getprop ro.build.fingerprint | tr -d '\r')"

if [[ -f "$APK" ]]; then
  echo "Installing debug APK: $APK"
  "${ADB[@]}" install -r "$APK" >/dev/null
else
  echo "Debug APK not found at $APK; using the already installed package." >&2
fi

"${ADB[@]}" shell am force-stop com.cyclone.mobile
"${ADB[@]}" shell am start -W -n "$COMPONENT"

echo
echo "Human Gesture V0.3 device surface is active."
echo "Verify Cyclone Accessibility is enabled, then execute the matrix from docs/HUMAN_GESTURE_RUNTIME_V03.md."
echo "The surface exposes large/small taps, long press, vertical/horizontal scroll regions, edge-near target, counters and touch coordinates."
