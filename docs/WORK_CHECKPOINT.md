# FORMA work checkpoint

Updated: 2026-10-13 17:10 Europe/Moscow

## Exact source

- Recovered source HEAD: `42766072f7be6e66da2f5fc66befcb67dc2cf82d`
- Parent: `8e45c1cee4d194039afd7e22c397a226dde23660`
- Durable source archive SHA-256: `9822da4e025c8cffe665c42bdc446781976a0b956ff17ec93489643333a80059`
- Source archive provenance: Library checkpoint `FORMA_Source_Checkpoint.zip`
- Upstream scaffolding provenance: GetFitPro `f349ae0d9d582595f6dca0d1824c58687275862e`, MIT notice retained.
- Remote `main` before this checkpoint update: `a058dee690b0fd06a514974be021b466d50c4bf3`.

## Current block

Block A is in progress: restore the existing source into the empty private
`spenskoj90-sudo/forma-fitness-android` repository, preserve provenance, and
obtain a reproducible GitHub Actions build with unit/Room/lint/APK evidence.

The recovered bundle is clean and contains no `AGENTS.md`. At the start of
this block the remote repository was empty and had no open pull requests, so
no competing writer was detected.

## Verification evidence

- Remote import commit: `95bfc78e358edd73321db833990f3f4e52ad21bc`.
- GitHub Actions run `38386131003` failed before Gradle at
  `android-actions/setup-android@v3`: the action requested the obsolete SDK
  package `tools`. No code/build tests ran in that workflow attempt. The
  redundant action has been removed; GitHub's Ubuntu Android image supplies
  the SDK used by Gradle.
- Remote CI-fix HEAD: `fbf8e0c89116f0cbad660e55244d6f2e3a78c2e5`.
- GitHub Actions run `38386276683` completed successfully: domain/app tests,
  lint and debug assembly all ran; build duration was 6m49s.
- APK artifact `11806632582` was published with archive digest
  `sha256:73da5ccb7d17bb4fec57514f6355bdca893e413c79d15066c4740f70f18f8ac7`.
  The contained `app-debug.apk` SHA-256 is
  `ae31453d4b3fdc1fc85f5136cbe95398d124dab9052fa2b8cbf6440e2e2aa507`;
  metadata identifies `ru.forma.fitness` 0.1.0, versionCode 1, minSdk 26.
- GitHub Actions run `38388029382`, verify job `115264536734`, passed the
  domain/app tests, lint, assembly, APK identity and signature checks.
- APK artifact `11807343335` has archive digest
  `sha256:8c2e0154ef16f46cf4dd4fee929041e44438a8beebddb57bbde3ca36f190ec81`.
  Its `app-debug.apk` SHA-256 is
  `6b3b74b9430a92a3dfc6656450d35022c89b2a3881362a85b18797ac8c159b23`.
  AAPT confirms `ru.forma.fitness`, versionName 0.1.0, versionCode 1,
  minSdk 26 and targetSdk 35. Apksigner confirms one RSA-2048 signer, valid
  APK Signature Scheme v2, certificate DN `CN=FORMA Development,O=FORMA,C=RU`
  and certificate SHA-256
  `f64c119c6bcc34e33f2061cf8873f6c57e9b186a10c13644c3a0719467605225`.
- The same run's instrumentation job `115266290452` did not execute tests:
  emulator 37.2.12 exited before ADB registration because the Pixel 6 AVD
  requested a 7372.80 MB userdata partition while only 5738.94 MB remained.
  The workflow now caps the AVD disk at 4096 MB. This is an infrastructure
  correction, not a runtime PASS; the follow-up run must provide that evidence.
- Durable baseline at recovered HEAD: domain tests 25 passed; app/Room tests
  3 passed; lint 0 errors and 3 warnings; debug APK and instrumentation APK
  assembled.
- A fresh local rerun was attempted on 2026-10-13 but could not download
  Gradle 8.10.2 because the execution environment had no route to
  `services.gradle.org`. This is an environment gate and is not a fresh PASS.
- API 34 instrumentation remains unexecuted. KVM was enabled successfully in
  run `38388029382`; the confirmed blocker was runner disk capacity.
- Follow-up run `38394512778` verify job `115286204722` passed. Its API 34
  emulator booted with KVM and executed `JourneyTest`, proving the runner-disk
  blocker fixed. Instrumentation job `115287639581` then failed the single
  journey before UI interaction: Android 14 notification permission launched
  before Compose content, so the test found no Compose hierarchy. The app now
  installs Compose content before requesting notification permission, and the
  journey grants that runtime permission before launching `MainActivity`.
  This correction is pending CI and is not yet a runtime PASS.
- Run `38402187095` verify job `115312345638` passed, but instrumentation job
  `115315096082` did not reach Gradle or execute the corrected journey. On a
  lower-capacity runner, emulator 37.2.12 again applied the Pixel 6 profile's
  7372.80 MB userdata requirement despite the appended 4096 MB setting and
  exited before ADB registration. CI now uses the smaller Pixel 2 hardware
  profile with API 34/x86_64/KVM and 2048 MB userdata; screen geometry is not
  an assertion of this functional journey. A follow-up run remains required.
- Runtime/device acceptance is limited to the Owner reporting that the APK
  opened. Full physical acceptance has not occurred.

## Writer and next step

- Current writer: FORMA engineering continuation automation, single integration lane.
- CI concurrency is serialized per ref with `cancel-in-progress: false`, so a
  later push does not cancel an already executing FORMA run. A repository-wide
  fenced writer lease for non-CI mutations remains required and must not be
  represented by this checkpoint alone.
- Next step: obtain an executed API 34 Compose journey, then finish Block A and
  implement the repository-wide fenced writer lease before starting Block B.

## Evidence boundaries

CODE, BUILD, RUNTIME, and OWNER acceptance are tracked separately. A compiled
instrumentation APK is not an executed UI test; an emulator is not a physical
device; opening the app is not full acceptance. Stable update signing is not
implemented, and no signing secret is stored in Git.
