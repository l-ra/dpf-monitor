#!/usr/bin/env bash
set -euo pipefail

APK="$1"
PACKAGE="cz.dpf.monitor"
ACTIVITY="$PACKAGE/.MainActivity"

echo "Installing $APK"
adb install -r "$APK"

# Avoid blocking startup with permission dialogs in an emulator without BLE.
for p in android.permission.BLUETOOTH_CONNECT android.permission.BLUETOOTH_SCAN android.permission.POST_NOTIFICATIONS; do
  adb shell pm grant "$PACKAGE" "$p" || true
done

check_alive() {
  local label="$1"
  local pid
  pid=$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)
  if [ -z "$pid" ]; then
    echo "::error::DPF Monitor died: $label"
    adb logcat -d -v time -s AndroidRuntime:E ActivityTaskManager:E | tail -n 180
    exit 1
  fi
  if adb logcat -d -v time -s AndroidRuntime:E | grep -q "Process: $PACKAGE,"; then
    echo "::error::DPF Monitor AndroidRuntime FATAL exception: $label"
    adb logcat -d -v time -s AndroidRuntime:E | tail -n 180
    exit 1
  fi
  echo "Startup OK ($label), pid=$pid"
}

adb logcat -c
adb shell am start -W -n "$ACTIVITY"
sleep 8
check_alive "cold start"

# The service is deliberately non-exported. ADB shell must not start it
# directly; its notification is exercised only through in-app/CDM flows.

# Open the Graphs tab to exercise view recreation.
size=$(adb shell wm size | head -1 | grep -oE '[0-9]+x[0-9]+' | tail -1)
w=$(printf '%s' "$size" | cut -d x -f1)
h=$(printf '%s' "$size" | cut -d x -f2)
if [ -n "$w" ] && [ -n "$h" ]; then
  adb shell input tap $((w * 3 / 4)) $((h - 110)) || true
  sleep 3
  check_alive "graphs tab"
fi

adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY"
sleep 6
check_alive "second launch"

echo "Android emulator startup smoke test passed"
