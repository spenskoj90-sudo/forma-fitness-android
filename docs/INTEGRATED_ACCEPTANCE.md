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
- One-tap **explicit manual** weekly text report sharing for the current profile.
  Personal data is not sent anywhere automatically.
- Instrumentation recovery test now actually sends a stop command after
  persisted resume and fresh timer ticks. Previously it was commented out by
  literal backslash-n text, producing false failures on Android 8/11/14.
- Separate preview package `ru.forma.fitness.acceptance`, visible as
  `FORMA Проверка`, via `.github/workflows/owner-acceptance.yml`.
  This preserves existing FORMA, FORMA Тест and FORMA Дизайн installations.

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

Before an Owner installs the acceptance APK, confirm exact-head GitHub
verify and emulator API26/API30/API34 runs, APK package, signer, SHA256.
