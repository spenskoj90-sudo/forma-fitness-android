# FORMA work checkpoint
Updated: 2026-10-14 Europe/Moscow

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

## Current finding
TimingRegressionTest added first: existing advanceTimed drops fractional time at each tick; pause does not credit interval since last tick; late cardio tick overcredits past completion. CI red evidence required before implementation.

## Next step
Run new timer regressions on branch CI, implement fixes and backup tests, then remaining blocks above in one integration PR. Never merge failing/unexecuted runtime work.

## Release boundary
No release candidate claim yet. Stable signing and update path are unverified; do not delete an installed app containing history to bypass signature mismatch. No production secrets created or changed.
