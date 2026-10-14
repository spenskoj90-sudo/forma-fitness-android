# FORMA work checkpoint
Updated: 2026-10-14 (FORMA engineering continuation, Europe/Moscow)

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

## Current continuation — exact evidence

- Baseline `main` remains `2fff305c22e9fe3f1d32c213777c14e7a2cc3560`, not protected. One open draft integration PR #1, branch `forma/rc-2026-10-14`.
- Run `38556446767` for SHA `2fc27fb5f0196ca92541844be27b70f89ee31f72` is **completed / FAILURE**: verify SUCCESS, API34 instrumentation SUCCESS; API26 and API30 instrumentations FAILURE.
- API26 log: `JourneyTest.kt:52`, 20s timeout inside workout stop/save/nav sequence. API30 log: `JourneyTest.kt:55`, attempted Progress nav before second-profile editor had fully dismissed. Neither failure constitutes a release PASS.
- Actual failed-run artifacts `11890996021` (API26) and `11891750210` (API30) include workout screenshots with numeric IME covering workout controls; late failure screenshots after teardown show the launcher, not a product crash.
- Follow-up fixes in integration lane: `98ae23e` and `b4cbaef` serialize WorkoutService commands, await prior loop teardown, and prevent older startIds from stopping a new workout. `fe08683` requires evidence that a rapidly started second workout receives live background ticks. `6c68fe7` waits for actionable navigation on second profile and saves in-app screenshot + Compose tree if text times out. `a5d054e` dismisses stale numeric focus and resets stage scroll position.
- Previous main checkpoint document (dated 13 October) is historical; this branch checkpoint is the source of current integration state.
- **No exact-HEAD green matrix yet.** After the above commits, pending/active CI must be rechecked for the latest branch SHA and all API26/30/34 results. No merge before all pass, final code review and evidence inspection.
- Earlier APK v0.1.0 was only opened by Owner; device update compatibility, audio audibility, OEM background behaviour and physical/visual acceptance remain unverified.

## Next step

1. Confirm the final branch HEAD and matching Actions workflow run; inspect verify, API26/30/34 jobs and failure evidence, especially API26 workout stop/save.
2. Fix proven failures on the same integration branch, rerun and review final HEAD, integrate PR #1 only with verified gates.
3. Record merged `main` SHA, APK hash/certificate, real app screenshots and exact device-acceptance checklist; require Owner signing custody decision before stable release channel.

## Release boundary
No release candidate claim yet. Stable signing and update path are unverified; do not delete an installed app containing history to bypass signature mismatch. No production secrets created or changed.
