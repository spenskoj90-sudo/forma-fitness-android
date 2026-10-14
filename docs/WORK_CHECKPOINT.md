# FORMA work checkpoint
Updated: 2026-10-14 14:24 Europe/Moscow

## Source and coordination
- Live main verified once at start: `2fff305c22e9fe3f1d32c213777c14e7a2cc3560`.
- Open PRs: none at start; main is not protected.
- Working lane: `forma/rc-2026-10-14`, single executor in this chat.
- Separate workspace: `/workspace/scratch/d63f10f33469/forma`.
- Previous hourly automations remain disabled. No Markdown lock or global lease required for this single writer. SENTINEL untouched.
- MIT GetFitPro notice retained. No AGENTS.md in remote tree.

## Confirmed baseline evidence
- Actions run `38427526988`, source main above: verify `115400723271` SUCCESS and instrumentation `115402506460` SUCCESS.
- Verify ran domain/app tests, lint, debug APK assembly, package and certificate inspection.
- API34 executed one Compose JourneyTest: profile A, generated strength program, warmup skip, one completed set, rest pause/resume, stopped workout saved, theme change, profile B empty history, A history retained.
- This does NOT prove complete workout, cardio, process recovery, background/lock, import, API30/26 or physical audio.
- Owner only confirmed opening old 0.1.0 APK on Infinix Zero 30; physical/visual acceptance pending.
- Local Gradle download attempted today: services.gradle.org unreachable. Use GitHub CI for fresh evidence, not old local PASS.

## Current block / plan
1. Reliability: fractional timer accounting, pause and finish, stale session commands, explicit process recovery; regression tests.
2. Data preservation: versioned bounded JSON backup, strict validation, transactional import as new isolated profiles; measurement editing; test rollback/reopen.
3. Design: actual reference inspected (`Фитнес-приложение FORMA — путь к силе.png`); graphite/green UI, home cards, accessible themes/navigation, vector adaptive/monochrome icon; actual screenshot evidence.
4. Trainer: audio focus, offline TTS fallback, adjustable tempo/volume, notification session binding; API26/30/34 journeys.
5. Final review, exact HEAD CI, APK evidence; stable signer custody proposal remains an Owner gate.

## Latest exact-HEAD evidence and correction
- PR #1 is open and DRAFT. Its pre-continuation HEAD was `aaafde3c04cd4381aadb3ebe345cee815d2383d8`.
- Actions run `38530992443` completed FAILURE. Verify job `115742726470` and API26 instrumentation `115744199640` PASSED; API30 `115744199778` and API34 `115744199754` FAILED on `JourneyTest.kt:40`, waiting for the text "Тренировки" after Room showed `active==null`.
- This is not evidence that the workout save failed. The hidden NavigationBar labels are not a stable text assertion; use an actual actionable `contentDescription="Прогресс"` to establish UI readiness, while retaining the database assertion. Failure screenshots obtained only after test teardown show the Android launcher and cannot establish a product crash.
- Capture timeout screenshots and Compose tree **inside** instrumentation before teardown. Store evidence in the debuggable app-private directory and retrieve it via `run-as` / tar, including on API30; include logcat on failures.
- API34 compatibility update test executed in `38530992443`, but does **not** establish a stable cross-build signing channel because disposable debug keys are generated per CI run. Private custody remains the Owner gate.

## Current continuation
- Latest pre-checkpoint integration commit: `e446edd6790c75c1aaeda0bd66ccf75a1837349a` (keyboard dismissal and per-profile editor field state). PR #1 owns the current branch; the actual post-checkpoint commit SHA is recorded in the PR and GitHub Actions.
- Real API34 screenshots from run `38530992443` showed the IME still covering Home/Training after onboarding and a purple default Material3 nav indicator. UI now dismisses the IME after saving and uses explicit green FORMA secondary/navigation colors. Visual result on corrected build is **pending** CI screenshots.
- No claims of a physical release or compatible owner APK update.
 
## Next step
Wait for exact-HEAD jobs after the assertions/evidence changes. If runtime remains red, inspect in-app timeout screenshot and logcat before changing product logic. Continue reliability review and meaningful tests. Do not merge while red or pending.

## Release boundary
No release candidate claim yet. Stable signing and update path are unverified; do not delete an installed app containing history to bypass signature mismatch. No production secrets created or changed.
