# FORMA — permanent signing and first installation baseline

Owner status update (2026-10-24): **neither Infinix nor the new Poco
currently has FORMA installed**. Infinix was factory-reset. Therefore
there is NO current app data or installed certificate on those two devices
to preserve or compare. Earlier concerns about migrating a live v0.1.0
installation are historical only. They must not block the FIRST new install.

## Permanent identity

- One product and one Android package: `ru.forma.fitness`.
- One owner-controlled, permanent FORMA-only signing identity.
- Every future update retains the same package/certificate, uses strictly
  increasing `versionCode`, and keeps Room and DataStore data in place.
- No separately installed "FORMA Тест", "FORMA Проверка" or other application
  ID is created as the update mechanism. CI debug APKs remain for CI evidence,
  **not** user distribution.
- On first installation, verify signer SHA-256 and back up its encrypted
  keystore before considering the first build a supported release.

## Secure one-time signing setup

1. From an Owner-controlled computer, run
   `bash scripts/create-release-signer.sh /absolute/private/path/forma-release.p12`.
   This one-time script refuses to put a signing key inside the repository or
   overwrite an existing one. Password entry is interactive and not placed
   in a command-line argument. Do not run it in GitHub CI.
   Generate a new 3072/4096-bit RSA Android release key **locally under the
   Owner's control** using JDK `keytool`; a release password and key material
   must never be included in the repository, issue, ChatGPT, workflow logs,
   Android APK assets, or public release feed.
2. Keep an encrypted offline master copy and a separately protected recovery
   copy (plus documented passphrase custody). Restrict access to the owner.
   If this key is lost, directly updating already-installed APKs will become
   impossible unless a separately validated signing-rotation mechanism exists.
3. **Only after the legacy signing workflow has been removed from live `main`**, configure the four credentials exclusively as **environment-scoped secrets** in a verified Owner-approved `forma-release` environment, not as unrestricted repository secrets. Confirm that protection genuinely requires Owner review before any secret is available to the job. Otherwise block production signing.
   `FORMA_RELEASE_KEYSTORE_B64`,
   `FORMA_RELEASE_STORE_PASSWORD`,
   `FORMA_RELEASE_KEY_ALIAS`,
   `FORMA_RELEASE_KEY_PASSWORD`.
   Base64 is only an encoding, **not** encryption; it must stay protected
   as a secret. Removing existing repository-wide duplicate secrets is a
   separate Owner-controlled custody operation, never a CI or chat action.
4. The legacy `.github/workflows/release-candidate.yml` must be absent
   from live main before configuring real signing secrets. PR #6 deletes this
   route in source, but an unmerged draft PR **does not disable it on main**.
   Only `.github/workflows/signed-release.yml` may consume `FORMA_RELEASE_*`
   secrets. The Android PR CI checks that source-level invariant.
   Once approved changes and tests are merged into `main`, launch the
   **manual** `.github/workflows/signed-release.yml` workflow with a new
   integer `version_code` and a semantic `version_name`. It never pushes
   a public APK automatically. The workflow checks unit tests/lint, signing,
   exact application ID, version and APK SHA-256, then uploads a **private**
   signed APK artifact and verification metadata. No keystore is uploaded.
5. The owner should install only the verified signed APK from the private
   artifact after acceptance. Never use a CI debug APK as the first official
   installation.
6. Configure an Owner-controlled HTTPS file host and signed-release manifest
   only after successful identity checks. The in-app `Обновление` tab will
   remain disabled while `forma_update_feed_url` is empty.
7. Before signing, the Owner must verify the requested versionCode is
   **higher than the last production-signed version**, not merely above 2;
   retain the exact frozen source SHA, APK hash, certificate SHA-256 and
   versionCode in a protected release ledger. Verify a second
   **higher-version, same-key** signed build installs over
   the first one on Infinix and Poco and preserves profile/history/measurement
   records. Record both signers, SHA256, and retained record counts.

The first release signing and first installation are intentionally **not**
reported as completed by merely adding source code or workflows.

## Release gates

- CI: `domain:test`, `app:testDebugUnitTest`, `app:lintDebug`, and
  emulator API26/API30/API34 acceptance at the final SHA.
- Security: a signing certificate on which the Owner holds private custody,
  protected release secrets, and no undeclared permissions or silent install.
- Data continuity: fresh install → create a profile/record set → update over
  install to a higher version → assert exact saved records and independent
  profiles are intact after app process restart.
- Physical: Infinix + Poco, offline workout + optional sync once implemented,
  background training, spoken prompts, visuals and update UI.
- Server: auth/sync and exercise-specific media are separate gates; adding
  them in a source branch does not make them operational.

An update may be initiated from within FORMA, but Android presents its own
installation confirmation. A third-party app cannot silently bypass Android's
package installation and signature controls.


## Source-only release safety hardening (2026-10-31)

The manual release workflow now requires an **exact approved source_sha** equal
to both the workflow-dispatch commit and checked-out protected main commit.
Before restoring any signing secret, the source guard checks completed SUCCESS
results from the GitHub Actions app for all four required Android contexts
(verify and API26/30/34), plus independent backend-security, on that exact SHA. A missing/failed check blocks signing.
The GitHub token needs only read-only contents/checks permissions.

**No signed APK is uploaded to public Actions artifacts.** A public-repo run
cannot honestly label an uploaded signed APK "private". The workflow now
publishes only public package/signature/hash metadata and deletes the signed APK
on its ephemeral runner. This is SOURCE_ONLY readiness, not a deliverable
release pipeline. Before any real signing run, PROJECTS and Owner must approve
an authenticated private delivery mechanism and monotonic production
versionCode ledger, then extend and re-review the workflow. Provider-enforced
reviewers/self-review/bypass restrictions and two E2EE recoveries remain
separate owner gates. Do not dispatch signing just to test this source.


Backend CI source was normalized to one distinct `backend-security` check
(instead of a second ambiguous `verify`) and now runs on every PR and main
push, so a required backend check can be enforced even for Android-only edits.
The existing protected-main provider configuration still requires the four
Android contexts; PROJECTS must independently verify and add the fifth
`backend-security` required context before release acceptance. Neither
branch-protection settings nor environment settings were changed here.
