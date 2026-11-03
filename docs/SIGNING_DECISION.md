# FORMA signing decision and private delivery

## Current source implementation — 2026-11-02

Release source is maintained in existing PR #6. The application signing certificate remains SHA-256 `a13104307fbd01a38a4e1fbdf7aafdf3f2e76be7cd18a1ac986a9b775d35cf7b`. No real FORMA signing or recipient provisioning has been performed for this change.

### Durable version journal

The same-repository operational branch `ops/forma-release-ledger` contains `release/ledger.json`. Its immutable genesis commit is `b00b8b1d928507db6dcc5cf7d4dcd7dafea82ce7`, pinned in `scripts/release_ledger.py`. Genesis was read back successfully; both counters are zero and there is no reservation. This operational branch is separate from application source.

The controller reads the current branch and validated journal, prepares an explicit snapshot transition with its expected canonical digest, creates a Git commit, and updates the branch through a non-force GitHub update with the exact expected head. A race requires reading the new state and recalculating the transition; never force, reset, or reuse a reserved number. The helper has no initialization/reset CLI.

A reservation binds source SHA, original run ID and attempt 1, version code and name, recipient public-key fingerprint, and an expiry no more than six hours away. Every reservation consumes its number, including failures. An active reserved or signed entry prevents replacement. A failed/expired run must reach a terminal state and be finalized explicitly before a new reservation. The journal retains every transition through Git history.

The protected `forma-release` environment pins `FORMA_RELEASE_LEDGER_COMMIT` to the approved reservation commit. Authorization checks that the live branch matches this pin, its ancestry and transition match the source-pinned genesis, and the exact run, source, version, signer, recipient and expiry match. It rereads the branch before proceeding. `FORMA_LAST_SIGNED_VERSION_CODE` remains a checked mirror; the durable journal is authoritative. Workflow retries cannot sign again.

### Encrypted delivery

`FORMA_DELIVERY_RECIPIENT_PEM` contains only an owner-approved RSA public key. The helper accepts one RSA SPKI key, 3072–8192 bits, exponent 65537; private or concatenated keys fail. The private recipient key stays with the receiver. Generate/read it only in actual `/dev/shm` tmpfs with no swap and a zero soft core limit. Private-key reads reject symlinks, wrong ownership/mode and non-tmpfs descriptors.

After actual APK signature and package verification, the workflow reauthorizes the live reservation and encrypts the APK using a fresh AES-256-GCM key and nonce, wrapping that key with RSA-OAEP-SHA256. Authenticated metadata binds the source, original run, ledger commit/generation, version, signer, recipient and APK hash/size.

The only uploaded delivery files are `forma-release.aesgcm.json`, `APK_IDENTITY.json` and `RECEIPT.json`, retained for three days. A separate job without signing-environment access verifies all three hashes, creates attestations, and verifies their repository, workflow, source and main-branch identity. Plain APKs and signing material are never artifact upload inputs. Gradle caching, configuration caching, daemon and build scans are disabled for release; the keystore uses tmpfs and cleanup runs unconditionally.

Before receiver decryption, obtain expected capsule/identity/receipt hashes from the authenticated GitHub artifact and verify the attestations. Supply expected identities from these verified receipts, never from the capsule header alone. The receiver checks the authenticated metadata and plaintext hash before returning the APK. Final owner output is created exclusively with mode 0600. Private Drive handoff and installation evidence remain controller/owner duties.

### Operational sequence and remaining gates

1. Review and merge the source through the existing PR and protected main process; freeze the exact main SHA and require its ordinary CI.
2. Approve the real recipient public key and configure public environment pins. Preserve environment protection and the adopted signer identity.
3. Only with FORMA-specific signing authorization provision its four signing secrets, use a fresh manual run and atomically reserve its exact run/source/version/recipient in the journal. Update the protected reservation commit pin before the signing authorization step can pass.
4. Verify the original signing job, completed delivery-attestation run, downloaded ciphertext and receipts. Finalize signed/delivered journal transitions only from verified evidence. The helper checks run origin/status; the controller verifies artifact authenticity, receipt content and private delivery evidence.
5. Complete owner decryption/private handoff, phone update smoke test and secret cleanup under their respective gates. Keep counter mirrors consistent after finalization.

At source preparation time: no recipient configured, no version reserved, no four signing secrets provisioned or read, no real signed FORMA APK, no private delivery or phone check. Source tests and PR CI do not constitute a signing authorization.

## Historical decision record

<details>
<summary>Earlier custody and source decisions, retained for audit</summary>

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


</details>
