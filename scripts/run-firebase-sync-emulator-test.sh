#!/usr/bin/env bash
set -euo pipefail

test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb shell settings put global airplane_mode_on 0
adb shell svc wifi enable
adb reverse tcp:9099 tcp:9099
adb reverse tcp:8080 tcp:8080
trap 'adb reverse --remove tcp:9099; adb reverse --remove tcp:8080' EXIT

evidence=../verification/firebase-sync-api34.txt
timeout --signal=TERM --kill-after=15s 4m adb shell am instrument -w \
  -e class ru.forma.app.FirebaseSyncEmulatorTest \
  ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "$evidence"
grep -F 'OK (1 test)' "$evidence"
