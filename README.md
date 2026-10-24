# FORMA — Personal Fitness Trainer
Native Android, offline-first, Russian UI, minimum Android 8 (API26). Independent from SENTINEL.

Local profiles include age, height, weight, experience, equipment, limitations, primary/secondary goals, muscle emphasis, training days and recovery. The planner gives separated strength days and adjustable cardio. A guided session includes warmup, working sets, rest, pause, skip, manual weights/repetitions/effort, pain-stop and history. Room stores each profile document separately and mutates it transactionally; DataStore stores selection and presentation/audio preferences. Body measurements, charts and weekly/monthly reports use stored results. TTS uses an available Russian offline voice; optional tones and a 60 BPM metronome run in a foreground service. Missing TTS remains an external device setup requirement.

## Build
JDK17, Android SDK35, Gradle8.10.2 (wrapper), Kotlin2.0.21, AGP8.7.2. Create a FORMA-only development debug identity before assembly:

```sh
mkdir -p .forma-signing
keytool -genkeypair -keystore .forma-signing/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=FORMA Development,O=FORMA,C=RU' -keyalg RSA -keysize 2048 -validity 3650
./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
The debug identity is disposable, local and excluded from git; no SENTINEL key or secret is used. Changing it breaks in-place APK updates. Stable install/update signing and key custody are unfinished and require a separate decision before regular distribution. Do not uninstall a data-bearing app merely to work around a signature mismatch.

## Scope and limits
The evolving Android branch requests network access only for an explicit release-update check/download. No account backend or automatic profile upload has been deployed. No paid service, external exercise GIF, bundled font or copied exercise dataset. Original textual technique cues and vector F icon. The earlier Owner-approved icon reference is not available here; this icon is provisional. Each set is stored when confirmed; process recreation pauses unfinished work for explicit resume. Device battery policies and voice availability require physical verification. All pain stops block the next generated program until recovery is explicitly updated. Restrictions block automatic planning rather than pretending to diagnose them. Defaults are not medically validated personalised prescriptions.

GetFitPro (upstream `f349ae0d9d582595f6dca0d1824c58687275862e`) supplies Gradle configuration/wrapper scaffolding under MIT; its notice is included in source and APK. GetFitPro datasets/GIFs/fonts are not imported.

## Verification
See `docs/CHECKPOINT.md` for exact tested SHA, artifacts, results and remaining gates. GitHub workflow is prepared; remote repository creation/CI require authenticated access and are not implied by the existence of this archive.


## Continuity / multi-device evolution (draft PR #6)

- Same Owner production application ID `ru.forma.fitness` for successive real
  releases; separate acceptance application IDs have been retired.
- `Профиль → Обновление` provides a user-initiated HTTPS update checker and
  APK identity/certificate/SHA verification, with Android's installer approval.
  The release feed is intentionally unset and no APK update can be proposed
  until permanent signer custody and hosting are ready.
- Local multi-user management is searchable; backup/import accepts up to 100
  profiles (32 MiB) and preserves original Room storage without a DB reset.
- Equipment may be selected from Home and Gym and combined. The exercise
  catalog contains matching movement descriptions and primary/secondary muscle
  labels, and the measurements screen includes a **schematic**, data-driven
  front/back mannequin. This is not a body scan or vetted motion animation.
- Personal account registration, protected cloud records and cross-phone sync
  are planned in `docs/MULTIDEVICE_UPDATES_ARCHITECTURE.md`. They are NOT
  working yet; existing profiles remain on their original phone. Do not
  promise that signing into a different phone restores workouts at this stage.
- Current Owner devices for future physical acceptance: Infinix and Poco.

**Do not install a disposable CI/debug APK over the user's existing app.**
The Owner confirmed (2026-10-24) that FORMA is not installed on
either the reset Infinix or the new Poco. No old installed certificate or
personal data needs migration from those devices. The FIRST installed APK must
be signed using the one permanent, recoverably backed-up FORMA release key;
future APKs must use the SAME key. See `docs/SIGNING_DECISION.md`.


## Release preparation: one first install, all later versions are updates

The manual `.github/workflows/signed-release.yml` workflow targets only
`main` and requires a protected GitHub `forma-release` environment with
one permanent keystore and four secret values. It outputs only a signed APK,
its hash, signer fingerprint, package identity and version, not the keystore.
The owner must create/back up the key and configure protected secrets before
running the workflow. No release APK has been signed or installed yet.

Gradle release parameters:
`-PformaVersionCode=3 -PformaVersionName=0.3.0` (example only);
`-PformaUpdateFeedUrl=https://OWNER-HOST/releases/latest.json` is an
optional build-time value normally supplied by the Actions variable
`FORMA_UPDATE_FEED_URL`; empty disables the update feed safely.

`domain/SyncMerge.kt` now provides a unit-tested, account-scoped **merge
algorithm**, NOT account registration, verified authentication, an API or
working cross-phone sync. Cloud identity and server provisioning are still
required and will remain separate from SENTINEL.
