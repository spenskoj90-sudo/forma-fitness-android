#!/usr/bin/env bash
# Run as a single shell process. android-emulator-runner invokes multi-line
# action "script" values one line at a time, so compound if/fi cannot go there.
set -euo pipefail
api="${1:?Android API argument required}"
mkdir -p verification
capture_failure() {
  local code=$?
  if ((code != 0)); then
    # Android emulator contains only synthetic test profiles. Preserve visual
    # evidence even if the main journey exits before the normal adb pull.
    adb pull /sdcard/Android/data/ru.forma.fitness/files/evidence "verification/screenshots-api${api}" || true
    adb shell screencap -p /sdcard/forma-test-failure.png || true
    adb pull /sdcard/forma-test-failure.png "verification/failure-api${api}.png" || true
  fi
}
trap capture_failure EXIT
# No app code or training data should require the network.
adb shell settings put global airplane_mode_on 1
adb shell svc wifi disable || echo "Wi-Fi disable unavailable; airplane mode requested"
adb shell svc data disable || echo "Mobile data disable unavailable; airplane mode requested"
if [[ "$api" == "34" ]]; then
  adb install legacy/app/build/outputs/apk/debug/app-debug.apk
  adb install legacy/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w -e class ru.forma.app.LegacySeedTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee legacy-seed.txt
  grep -F "OK (1 test)" legacy-seed.txt
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.forma.app.UpdateTest --console=plain
fi
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.forma.app.JourneyTest --console=plain
adb pull /sdcard/Android/data/ru.forma.fitness/files/evidence "verification/screenshots-api${api}"
adb shell am force-stop ru.forma.fitness
adb shell settings put system font_scale 1.3
if (( api >= 33 )); then
  adb shell pm revoke ru.forma.fitness android.permission.POST_NOTIFICATIONS
fi
adb shell am instrument -w -e class ru.forma.app.RecoveryTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/recovery-api${api}.txt"
grep -F "OK (1 test)" "verification/recovery-api${api}.txt"
