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
No network permission, backend, paid service, external exercise GIF, bundled font or copied exercise dataset. Original textual technique cues and vector F icon. The earlier Owner-approved icon reference is not available here; this icon is provisional. Each set is stored when confirmed; process recreation pauses unfinished work for explicit resume. Device battery policies and voice availability require physical verification. All pain stops block the next generated program until recovery is explicitly updated. Restrictions block automatic planning rather than pretending to diagnose them. Defaults are not medically validated personalised prescriptions.

GetFitPro (upstream `f349ae0d9d582595f6dca0d1824c58687275862e`) supplies Gradle configuration/wrapper scaffolding under MIT; its notice is included in source and APK. GetFitPro datasets/GIFs/fonts are not imported.

## Verification
See `docs/CHECKPOINT.md` for exact tested SHA, artifacts, results and remaining gates. GitHub workflow is prepared; remote repository creation/CI require authenticated access and are not implied by the existence of this archive.
