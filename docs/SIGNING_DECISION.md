# FORMA stable update channel — prepared Owner decision

Package: ru.forma.fitness. Candidate 0.2.0/versionCode 2; prior 0.1.0/code 1. Debug CI currently uses a newly generated signer on each run. These artifacts are functional test builds, not a compatible update channel for previously installed builds.

## Concrete proposal
Use one FORMA-only release signing key. Owner retains an encrypted offline master copy plus a separately held recovery copy/password. Configure repository-scoped GitHub Actions secrets: FORMA_RELEASE_KEYSTORE_B64, FORMA_RELEASE_STORE_PASSWORD, FORMA_RELEASE_KEY_ALIAS and FORMA_RELEASE_KEY_PASSWORD. Key material must never enter Git, chat, reports or build artifacts. Release workflow is manual; it verifies all four secrets exist, builds and verifies the release APK, emits only certificate fingerprint/package/version/hash, and deletes runner temporary key files after completion. No public publication or key creation is performed by preparing the workflow.

Gradle consumes FORMА_RELEASE_* environment variables and FORMА_RELEASE_KEYSTORE_PATH (ASCII FORMA in implementation). Missing custody fails closed, not fallback to debug. Public store publication is outside this decision.

## Owner decision still required
Approve this custody arrangement and provide a secure approved destination for encrypted master/recovery copies. Key generation and filling permanent secrets occur only after that decision. No password or key needs to be sent in this chat.

## Existing installation
The exact certificate of the Owner's installed APK must be compared with the new certificate before claiming update compatibility. Previously inspected run 38388029382 used certificate SHA-256 f64c119c6bcc34e33f2061cf8873f6c57e9b186a10c13644c3a0719467605225; this does not establish which APK is currently installed. If the original private signer was discarded, the new signer cannot update it in place.

Safe migration requires export from the old installed app before replacing it. The old 0.1.0 app has no file export, so options must preserve its database first: recover the original key if it exists, or test an Owner-assisted Android backup/extraction path for that debuggable build and restore the validated data into a separately installed migration build. Until such a path is verified, keep the old installation and history intact. Do not suggest uninstalling as a fix. New export/import protects data for all future installations of the new build.

CI update-over-install with a single temporary signer proves the code/version/database behavior under a compatible certificate only; it does not recover old key custody or prove compatibility with the Owner's current installation.
