# FORMA — engineering checkpoint

Updated: 2026-10-14, Europe/Moscow
Status: offline Android 0.2.0 integration merged; physical acceptance and permanent signing **not** complete.

## Source of truth
- Production repository: `spenskoj90-sudo/forma-fitness-android`; SENTINEL repositories and infrastructure are out of scope.
- Main after merge of PR #1: `35bfd14df619af91f9bcee1f95c0dab67ad96c72`.
- Reviewed PR #1 exact HEAD: `21ebf658c64b1fa03fafab07a4fd68ebdb89ae41`; squash merged as above.
- GitHub Actions exact-HEAD run: [38562497275](https://github.com/spenskoj90-sudo/forma-fitness-android/actions/runs/38562497275), completed SUCCESS across verify and instrumentation API26/API30/API34.
- No additional FORMA writer or FORMA/SENTINEL recurring schedule activated. Main branch protection not previously enabled; CI proof, not the absence of protection, justified integration.
- Application scope is offline-first; no cloud upload of profiles or measurements.

## Confirmed checks on PR HEAD
- `verify`: build, `:domain:test` (39 passed), `:app:testDebugUnitTest` (8 passed), `lintDebug` (zero lint errors; nonblocking warnings), debug APK and AndroidTest assembly, manifest/identity/signing evidence.
- `instrumentation (26)`, `instrumentation (30)`, `instrumentation (34)`: each completed SUCCESS. Each executes one expanded Compose journey plus recovery checks (one test each) including two profiles, isolation, training/pause/stop/finish, extended strength/cardio, backup, themes, process recovery.
- API34 additionally exercises a synthetic 0.1.0-to-0.2.0 same-certificate upgrade and checks retained legacy DB data. **This is not a test of updating the Owner's differently signed installed APK.**
- Real emulator screens (light/dark) from the final run: Actions artifacts `instrumentation-api{26,30,34}-21ebf658c64b1fa03fafab07a4fd68ebdb89ae41`. Some emulator screenshots show launcher/System UI ANR dialogs unrelated to asserted app crash; use clean API34 renders for design comparison.
- Final PR review recorded on exact head, review ID 5519826489.
- PR#1 merged on 2026-10-14, no release or production secrets changed.

## APK evidence
- Artifact: `forma-debug-21ebf658c64b1fa03fafab07a4fd68ebdb89ae41` from run 38562497275.
- File `app-debug.apk`; package `ru.forma.fitness`; versionName `0.2.0`; versionCode `2`; minSdk 26; targetSdk 35.
- APK SHA-256: `3e3fb816278ab2aeeaa0896f26aa2b57e8d4e779cdd392ccd6dd3de09fbda588`.
- Debug signer cert SHA-256: `81aea2aad430472cdeec9bf7108399f1e1ac9673fc6b76c417146940deb65bc8`. Verified APK Signature Scheme v2, disposable CI key.
- Do **not** install this development APK over a live 0.1.0 instance with data unless matching signer and update compatibility are confirmed. Never uninstall to bypass an update failure. A backup/export pathway is implemented but also needs device acceptance.

## Implemented in merged integration
- Offline two-profile setup, personal goals and strength/cardio planning, 20 text-only exercises with guidance and manual alternatives.
- Session-aware workout state and fractional timers; pause/resume, rest, work, skip, pain stop, idempotent finish, Room persistence, explicit recovery after process death.
- Session-scoped notification actions and foreground service; audio focus, Android offline TTS fallback and adjustable voice/signal/metronome settings.
- Measurements, charts and progress, editing, versioned bounded JSON import/export through Android SAF, transactional import as isolated copies and rollback on validation errors.
- Graphite/green Compose theme with working light theme, five destinations, navigation palette, adaptive/monochrome launcher imagery.
- Regression for rapid resume→stop: controls bind to expected workout ID rather than stale stage; stale set/skip still guarded.

## Evidence limits and next engineering/acceptance
1. Final Owner physical acceptance on Infinix Zero 30 Android 14, plus Realme C31 OS confirmation; include two-profile flow, full strength/cardio sessions, pause, pain, background/lock, notification permission on/off, speech audible/offline voice, import/export/relaunch, display/font scaling, TalkBack.
2. Owner signing custody decision per `docs/SIGNING_DECISION.md`: permanent key generated/stored outside GitHub/chat, appropriate secure CI transfer, recovery backup, then two successively signed versions and real update-over-install with preserved data. Existing old signer may be unavailable.
3. Compare final real screenshots to `Фитнес-приложение FORMA — путь к силе.png` with Owner's visual acceptance. Confirm Android 8 screen/keyboard insets and emulator launcher dialogues do not occur on physical devices.
4. Any future fixes must start from new live `main`, be grouped coherently, tested at exact PR HEAD and reviewed; avoid reusing historical statuses.

**Release gate:** this is an integrated candidate for owner testing, **not** a signed release or publicly published application.
