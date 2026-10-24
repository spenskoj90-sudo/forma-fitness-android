# FORMA — integrated engineering and visual acceptance

Scope: Native Android 8+, two isolated offline profiles, Owner-approved mockup
layout, four-tab navigation and bundled offline hero artwork.

## Integrated in this candidate
- Three separated strength days on new profiles; individually selectable goals,
  priority muscle groups, equipment and schedule. Muscle-mass plans now include
  back, chest, shoulders, arms, core and legs; alternative goals preserve their
  own muscle mix and rest/cardio distribution.
- Muscle-zone schematic during exercise, instructions on demand, precise
  manual weights/repetitions and a foreground timed runner with safe pain-stop.
- Room transactional profile/history backup/restore, measurements and progress.
- Home reports both all completed workouts and separately the number of distinct
  scheduled plan days completed this week. Optional cardio, a repeated session
  on one scheduled day and stopped workouts cannot inflate the plan indicator.
  Regression tests cover repeated/off-plan workouts, scheduled cardio mode
  changes and week-boundary cases.
- One-tap **explicit manual** weekly text report sharing for the current profile.
  Personal data is not sent anywhere automatically.
- Instrumentation recovery test now actually sends a stop command after
  persisted resume and fresh timer ticks. Previously it was commented out by
  literal backslash-n text, producing false failures on Android 8/11/14.
- **Updated Owner direction (2026-10-24):** no further side-by-side preview
  application IDs. Keep `ru.forma.fitness` stable. The former isolated
  acceptance workflow and suffix were retired in the multi-device branch.
  The existing installed FORMA app is not to be replaced by a disposable
  CI-signed build; real upgrades require the same signer and data migration proof.

## Gated or not fabricated
- Real-device acceptance on Infinix Zero 30 and Realme C31 remains mandatory;
  emulator success cannot prove OEM background audio or battery behavior.
- No exercise-specific video/animated instruction library has been validated;
  the muscle diagram is schematic, never exercise-technique proof.
- No external Render/GPT ingestion: it requires an authenticated owner endpoint,
  explicit personal-data consent, retention and operational privacy policy.
- Stable release signing/cert compatibility with installed 0.1.0 is unresolved;
  CI debug signer is disposable and not an in-place update. Do not uninstall
  an existing app to circumvent Android signature mismatch.
- Nutrition/social tiles are not promised as real features without implementation.

Do not offer an Owner any development/debug APK for installation. First
obtain the actual installed signer and prepare a stable update channel. Only
then verify exact-HEAD CI API26/API30/API34, new APK package, signature and
SHA256 and perform an update-over-install on physical hardware.

## Consolidation with the earlier isolated phone-test draft

Historic draft PR #4 introduces a separate `ru.forma.fitness.physicaltest` package
and shares Gradle, manifest and instrumentation test files with this branch.
Do **not** merge its outdated UI test/Gradle changes over this candidate:
they target the earlier five-tab UI. This branch already provides the
independent `ru.forma.fitness.acceptance` package as `FORMA Проверка`,
with the current four-tab flow. Keep the original `ru.forma.fitness` and
existing `FORMA Тест` untouched. PR #4 can be retired only after its
unique requirements have been checked against this candidate.

## Owner physical acceptance checklist

Use a **successful exact-HEAD** acceptance APK only; record the HEAD SHA,
workflow run, APK SHA-256 and signer fingerprint before any installation.
Installation must offer a **new** `FORMA Проверка` icon, never replacement
of a pre-existing FORMA or FORMA Тест installation. Otherwise stop.

1. Launch without a network connection. Create two fictitious profiles.
   The new default must be Mon/Wed/Fri strength, with optional cardio;
   switching profiles must not reveal another profile's private history.
2. Compare Home, Training, exercise runner, Statistics and Profile against
   the approved image. Check actual typography, cropping, light/dark mode,
   Android system insets, font scaling and accessible touch targets.
3. Complete one strength workout with sets, rest, pause/resume, save and
   relaunch; verify session timing and saved weights/repetitions. On Home,
   redoing the same scheduled session must change completed-workout count
   but **not** the distinct scheduled-day progress count.
4. Complete voluntary cardio on a scheduled strength day; verify cardio
   appears in history while plan adherence does not increase. A scheduled
   cardio day, if enabled in Profile, may count once after completion.
5. Check lock-screen and app-background timers, notification pause/stop,
   Russian offline voice and audio interruptions, with notification
   permission both granted and denied.
6. Enter sample measurements, export a local backup, import into separate
   profiles, close and relaunch. Check editing, data isolation and
   rollback behavior if an invalid import is deliberately selected.
7. Repeat on Infinix Zero 30 and Realme C31, recording OS version,
   screenshots and each outcome as PASS / FAIL / OBSERVED / SKIPPED.

Do not induce actual pain to test pain-stop. Do not clear app data or
uninstall the old version to bypass a certificate mismatch. A disposable
CI debug signing identity cannot prove in-place upgrades of an installed
0.1.0 application. Final visual approval and release-signing custody
require separate Owner decisions.


### Superseding requirement: no additional installed FORMA variants

The user expressly rejected repeated separate application installations.
Future Owners will receive only genuine updates to `ru.forma.fitness` after
certificate compatibility and non-destructive migration are proved. Existing
`FORMA Тест` / `FORMA Проверка` installations, if any, are left untouched;
do not uninstall or clear their data automatically.
