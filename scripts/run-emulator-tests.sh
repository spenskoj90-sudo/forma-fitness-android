#!/usr/bin/env bash
# Run as a single shell process. android-emulator-runner invokes multi-line
# action "script" values one line at a time, so compound if/fi cannot go there.
set -euo pipefail
api="${1:?Android API argument required}"
offline=.forma-emulator/offline
cloud=.forma-emulator/cloud
[[ "$(adb get-serialno)" == emulator-* ]]
test -s "$offline/app.apk" && test -s "$offline/test.apk"
if [[ "$api" == "34" ]]; then
  test -s "$cloud/app.apk" && test -s "$cloud/test.apk"
fi
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
  adb install -r "$offline/app.apk"
  adb install -r "$offline/test.apk"
  timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.UpdateTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/update-over-install-api${api}.txt"
  grep -F "OK (1 test)" "verification/update-over-install-api${api}.txt"
  # Isolate subsequent journeys from the synthetic legacy profile and preferences.
  adb shell pm clear ru.forma.fitness | grep -Fx "Success"
else
  adb install -r "$offline/app.apk"
  adb install -r "$offline/test.apk"
fi
adb logcat -c || true
recovery_args=()
if [[ "$api" == "34" ]]; then
  recovery_args=(-e formaRecoveryBoundary reboot)
fi
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w "${recovery_args[@]}" -e class ru.forma.app.JourneyTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/journey-api${api}.txt"
grep -F "OK (1 test)" "verification/journey-api${api}.txt"
if [[ "$api" == "34" ]]; then
  previous_boot=$(adb shell settings get global boot_count | tr -d '\r')
  [[ "$previous_boot" =~ ^[0-9]+$ ]]
  adb reboot
  timeout --signal=TERM --kill-after=5s 2m bash -c '
    previous_boot=$1
    adb wait-for-device
    while true; do
      ready=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")
      boot=$(adb shell settings get global boot_count 2>/dev/null | tr -d "\r")
      if [[ "$ready" == 1 && "$boot" =~ ^[0-9]+$ ]] && (( boot > previous_boot )); then
        printf "Android boot_count advanced: %s -> %s\n" "$previous_boot" "$boot"
        break
      fi
      sleep 1
    done
  ' bash "$previous_boot" | tee verification/reboot-api34.txt
  adb shell input keyevent KEYCODE_MENU
else
  adb shell am force-stop ru.forma.fitness
fi
pull_evidence
adb shell settings put system font_scale 1.3
if (( api >= 33 )); then
  adb shell pm revoke ru.forma.fitness android.permission.POST_NOTIFICATIONS
fi
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w "${recovery_args[@]}" -e class ru.forma.app.RecoveryTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/recovery-api${api}.txt"
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
grep -F "OK (2 tests)" "verification/volume-preview-api${api}.txt"
# Preserve the synthetic *actual GPU surface* PixelCopy frames after
# the preview test: the earlier pull occurred before these frames existed.
# This is evidence only; any ADB/pixel-copy failure remains a hard CI failure.
pull_evidence
# Original layered glass and solid accessibility fallback across small/double-font viewports.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.FormaGlassUiTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/glass-ui-api${api}.txt"
grep -F "OK (4 tests)" "verification/glass-ui-api${api}.txt"
# Verify signed-update offer/check/loading and network-error UX with an injected
# synthetic gateway: no external feed, APK, permanent key or installer launch.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.UpdateCenterUiTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/update-center-api${api}.txt"
grep -F "OK (1 test)" "verification/update-center-api${api}.txt"
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
# Exercise populated on-disk Room v1 and v2 upgrades on each Android API.
# This test uses a dedicated disposable database; never real owner records.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.RoomMigrationInstrumentedTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/room-migration-api${api}.txt"
grep -F "OK (2 tests)" "verification/room-migration-api${api}.txt"

# Synthetic test-only MP4 exercises actual decoder, lifecycle and honest offline fallback.
timeout --signal=TERM --kill-after=15s 12m adb shell am instrument -w -e class ru.forma.app.TechniqueMediaTest ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "verification/technique-media-api${api}.txt"
grep -F "OK (5 tests)" "verification/technique-media-api${api}.txt"

# Run the native SDK against two disposable Firebase emulators after the
# offline journeys. One Android API is enough to exercise the transport.
if [[ "$api" == "34" ]]; then
  # Upgrade the offline v2 app over HTTPS through Android's confirmation,
  # then switch to the same-version Firebase emulator build.
  bash scripts/run-update-https-emulator-test.sh
  # Exercise the real scheduler and screen against the demo provider.
  adb install -r "$cloud/app.apk"
  adb install -r "$cloud/test.apk"
  (
    cd firebase
    export JAVA_HOME="${JAVA_HOME_21_X64:?Firestore emulator requires Java 21}"
    export PATH="$JAVA_HOME/bin:$PATH"
    npm run test:android-sync
  )
fi
