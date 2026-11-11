#!/usr/bin/env bash
# Run as a single shell process. android-emulator-runner invokes multi-line
# action "script" values one line at a time, so compound if/fi cannot go there.
set -euo pipefail
api="${1:?Android API argument required}"
mkdir -p verification
# Screenshots live inside the debug app's private storage, so Android's
# /sdcard/Android/data scoped-storage restrictions cannot hide CI evidence.
pull_evidence() {
  local target="verification/screenshots-api${api}"
  mkdir -p "$target"
  adb exec-out run-as ru.forma.fitness tar -cf - -C files evidence | tar -xf - -C "$target" --strip-components=1
}
capture_failure() {
  local code=$?
  if ((code != 0)); then
    # Android emulator contains only synthetic test profiles. Preserve visual
    # evidence even if the main journey exits before the normal adb pull.
    mkdir -p "verification/screenshots-api${api}"
    timeout --kill-after=5s 20s bash -c 'adb exec-out run-as ru.forma.fitness tar -cf - -C files evidence | tar -xf - -C "verification/screenshots-api${1}" --strip-components=1' bash "$api" || true
    timeout --kill-after=5s 20s adb logcat -d -v threadtime -t 4000 > "verification/logcat-failure-api${api}.txt" || true
    timeout --kill-after=5s 20s adb shell screencap -p /sdcard/forma-test-failure.png || true
    timeout --kill-after=5s 20s adb pull /sdcard/forma-test-failure.png "verification/failure-api${api}.png" || true
  fi
}
trap capture_failure EXIT
# Keep evidence collection bounded even when ADB itself is not responding.
# Instrumentation timeouts below fail visibly instead of hanging the runner.
# No app code or training data should require the network.
adb shell settings put global airplane_mode_on 1
adb shell svc wifi disable || echo "Wi-Fi disable unavailable; airplane mode requested"
adb shell svc data disable || echo "Mobile data disable unavailable; airplane mode requested"
if [[ "$api" == "34" ]]; then
  adb install legacy/app/build/outputs/apk/debug/app-debug.apk
  adb install legacy/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.LegacySeedTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee legacy-seed.txt
  grep -F "OK (1 test)" legacy-seed.txt
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.forma.app.UpdateTest --console=plain
fi
# Gradle connected tests uninstall the app at cleanup, which erases screenshots
# from its external files directory. Install and run instrumentations directly.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c || true
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.JourneyTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/journey-api${api}.txt"
grep -F "OK (1 test)" "verification/journey-api${api}.txt"
pull_evidence
adb shell am force-stop ru.forma.fitness
adb shell settings put system font_scale 1.3
if (( api >= 33 )); then
  adb shell pm revoke ru.forma.fitness android.permission.POST_NOTIFICATIONS
fi
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.RecoveryTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/recovery-api${api}.txt"
grep -F "OK (1 test)" "verification/recovery-api${api}.txt"
# Run the unsaved-input restoration regressions on all supported Android APIs.
# These are real Compose UI tests, not just Kotlin compilation.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.FormDraftRestorationTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/draft-restoration-api${api}.txt"
grep -F "OK (3 tests)" "verification/draft-restoration-api${api}.txt"
# Verify on-device AES-GCM/PBKDF2, ciphertext readback and additive Room restore on API26/30/34.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.EncryptedBackupInstrumentedTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/encrypted-backup-api${api}.txt"
grep -F "OK (2 tests)" "verification/encrypted-backup-api${api}.txt"
# Verify actual Compose set/history labels for single/pair dumbbells and bands.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.RecordedLoadUiTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/load-units-api${api}.txt"
grep -F "OK (2 tests)" "verification/load-units-api${api}.txt"
# Offline technique-search/expand accessibility, with no network or exercise video dependency.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.ExerciseTechniqueCatalogTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/technique-catalog-api${api}.txt"
grep -F "OK (4 tests)" "verification/technique-catalog-api${api}.txt"
# OpenGL ES on-demand volume preview must be accessible/offline on API26/30/34.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.OriginalVolumetricPreviewTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/volume-preview-api${api}.txt"
grep -F "OK (1 test)" "verification/volume-preview-api${api}.txt"
# Check real TalkBack semantics for headings, spoken controls and summary charts.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.AccessibleWorkoutUiTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/accessibility-api${api}.txt"
grep -F "OK (5 tests)" "verification/accessibility-api${api}.txt"
# Check home shortcuts and workout hero at 2x system font.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.HomeResponsiveTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/home-responsive-api${api}.txt"
grep -F "OK (3 tests)" "verification/home-responsive-api${api}.txt"
# Active local profile identity is read intact by TalkBack on a 320dp 2x-font layout.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.ProfileHeaderAccessibilityTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/profile-header-api${api}.txt"
grep -F "OK (2 tests)" "verification/profile-header-api${api}.txt"
# Preview and cancel a synthetic per-profile report before any external share.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.ProgressShareConsentTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/report-consent-api${api}.txt"
grep -F "OK (2 tests)" "verification/report-consent-api${api}.txt"
# Verify cancel/back protection and 2x narrow-profile form Save touch on real Android.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.ProfileEditExitTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/profile-edit-exit-api${api}.txt"
grep -F "OK (3 tests)" "verification/profile-edit-exit-api${api}.txt"
# Large offline measurement journal: paging, stable editing identity, no dropped records.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.BodyHistoryPaginationTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/body-history-api${api}.txt"
grep -F "OK (2 tests)" "verification/body-history-api${api}.txt"
# Exercise a populated on-disk Room v1->v2 upgrade on each Android API.
# This test uses a dedicated disposable database; never real owner records.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.RoomMigrationInstrumentedTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/room-migration-api${api}.txt"
grep -F "OK (1 test)" "verification/room-migration-api${api}.txt"

# Synthetic test-only MP4 exercises actual decoder, lifecycle and honest offline fallback.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.TechniqueMediaTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/technique-media-api${api}.txt"
grep -F "OK (5 tests)" "verification/technique-media-api${api}.txt"
