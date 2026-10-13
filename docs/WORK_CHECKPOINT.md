# FORMA work checkpoint

Updated: 2026-10-13 13:55 Europe/Moscow

## Exact source

- Recovered source HEAD: `42766072f7be6e66da2f5fc66befcb67dc2cf82d`
- Parent: `8e45c1cee4d194039afd7e22c397a226dde23660`
- Durable source archive SHA-256: `9822da4e025c8cffe665c42bdc446781976a0b956ff17ec93489643333a80059`
- Source archive provenance: Library checkpoint `FORMA_Source_Checkpoint.zip`
- Upstream scaffolding provenance: GetFitPro `f349ae0d9d582595f6dca0d1824c58687275862e`, MIT notice retained.

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
- The next CI revision records apksigner/aapt evidence and executes the
  existing Compose journey on an API 34 KVM-backed GitHub emulator. Until that
  run completes, signature details and UI runtime remain unconfirmed.
- Durable baseline at recovered HEAD: domain tests 25 passed; app/Room tests
  3 passed; lint 0 errors and 3 warnings; debug APK and instrumentation APK
  assembled.
- A fresh local rerun was attempted on 2026-10-13 but could not download
  Gradle 8.10.2 because the execution environment had no route to
  `services.gradle.org`. This is an environment gate and is not a fresh PASS.
- API 34 instrumentation remains unexecuted. The earlier software emulator
  timed out without KVM.
- Runtime/device acceptance is limited to the Owner reporting that the APK
  opened. Full physical acceptance has not occurred.

## Writer and next step

- Current writer: FORMA engineering continuation automation, single integration lane.
- Next step: import this source as one repository block, run GitHub Actions,
  record exact remote HEAD/run/artifact results, then continue the next
  measured product block.

## Evidence boundaries

CODE, BUILD, RUNTIME, and OWNER acceptance are tracked separately. A compiled
instrumentation APK is not an executed UI test; an emulator is not a physical
device; opening the app is not full acceptance. Stable update signing is not
implemented, and no signing secret is stored in Git.
