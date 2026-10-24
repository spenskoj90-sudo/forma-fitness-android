# FORMA: safe continuous updates and independent multi-device accounts
Date: 2026-10-24. Status: architectural contract and first Android implementation, **not a production service**.

## Non-negotiables

- A single production app ID: `ru.forma.fitness`, never a new app ID per release.
- Existing installed app history must not be uninstalled, overwritten by an
  incompatible certificate, or lost through destructive Room migrations.
- Higher monotonically increasing versionCode; stable owner-controlled signing
  certificate (or verifiable Android signing lineage) across all updates.
- Offline workout logging first; syncing must never prevent a workout, and
  only the signed-in account's records may be synchronized.
- No silent app install, automatic upload of medical-adjacent measurements,
  shared profile access, password in logs, or commingling with SENTINEL.

## Current state versus next required gates

1. Android Update tab now checks a configured HTTPS JSON release feed and
   validates package name, higher versionCode and installed signer fingerprint.
   It downloads the APK to a private cache, checks SHA-256, signer and APK
   package/version before handing it to the Android install UI. User approval
   is mandatory. The feed URL is **deliberately empty** until a stable signing
   channel exists. This is not yet a functioning production update service.
2. The direct-APK update permission is suitable only for an approved
   sideload distribution; a future Google Play build must remove
   REQUEST_INSTALL_PACKAGES and use the Play-supported update flow.
3. **Owner update 2026-10-24:** neither Infinix nor Poco currently has
   FORMA installed. Infinix was reset, Poco is new. The earlier private signer
   and missing old data are not first-installation blockers on these devices.
   We can establish one fresh stable production signing identity now.
4. The release key MUST be generated and retained securely by its owner,
   with a private protected copy, using the manual signed-release workflow.
   Then test first install and a genuine next-version update on both phones.
   A disposable CI signer is still unacceptable for that first real install.
   See `docs/SIGNING_DECISION.md`.


## Release JSON contract (same HTTPS origin)

```json
{
  "applicationId": "ru.forma.fitness",
  "versionCode": 3,
  "versionName": "0.3.0",
  "apkUrl": "https://YOUR-OWNER-CONTROLLED-HOST/releases/forma-0.3.0.apk",
  "sha256": "64 lowercase hexadecimal characters",
  "certificateSha256": "64 lowercase hexadecimal characters"
}
```

The update host and the APK are both HTTPS and same-origin. Never put credentials,
session tokens, health data or personal profiles in this public release feed.
The client checks byte-level SHA-256 and the actual APK signer and refuses a
different identity. Set `FORMA_UPDATE_FEED_URL` as a protected release GitHub Actions variable
(which Gradle injects into `R.string.forma_update_feed_url`) only after the signing decision,
hosting and an end-to-end update-over-install test. The manifest publisher
must publish atomically: upload immutable APK, verify hash, then publish feed.

## Owner first-install baseline (confirmed)

On 2026-10-24 the Owner confirmed that FORMA is not installed on either Poco
(new device) or Infinix (factory-reset). No old on-device FORMA records need
recovery. We can create a first permanent signing identity **before** the
first installation and validate later in-place updates using that exact key.
No key or complete signed APK has yet been generated/configured by this branch.

## Signed-APK workflow (prepared, not executed)

`.github/workflows/signed-release.yml` provides an owner-triggered
`main`-only signed build gated by protected secrets. It checks tests,
lint, signer, package identity, version and APK fingerprint, then uploads a
private release artifact. Before using it, the owner must generate and safely
custody the permanent key and provide the GitHub release secrets. The signed
APK is not yet built and no phone installation has taken place.

## Account model: one human, one authenticated identity

**Local profiles are not accounts.** Current multiple-profile switching does
not secure each local profile against other people using the same unlocked
phone. Until sign-in is implemented, make that limit explicit in the UI.

Target data ownership:
- `account_id`: opaque verified server-side identity, never a user-provided
  email string or profile name.
- `profile_id`: stable UUID; belongs to one account or to explicitly granted
  family/shared-workspace membership. The initial product should have no
  automatic cross-account sharing.
- `workout_id` and `measurement_id`: stable client-generated UUIDs, unique
  per account/profile and idempotent across offline retries.
- `device_id`: random per-install pseudonymous identifier, used for sync
  bookkeeping and revocation only; no IMEI or advertising ID.
- `revision`: server monotonic version per profile; changes have
  `updated_at`, `deleted_at` tombstones, idempotency keys and etags.
- `active_session`: local-only until an explicit safe handoff policy exists;
  two phones must not both advance the same active workout.

Authentication requirements:
- OIDC / passkeys or verified email login through a vetted provider,
  authorization-code + PKCE for mobile, short-lived access credentials,
  refresh tokens stored via Android Keystore-backed storage.
- The service verifies token signature, issuer, audience and expiry on every
  request and derives account_id server-side. Never trust account_id passed
  by a caller to authorize record access.
- Data isolation at both authorization middleware **and** database row-level
  security; test cross-account read/write/delete as forbidden.
- Recovery, device logout, session revocation, data export/deletion,
  privacy notice and explicit opt-in before initial upload.
- Backups imported on another phone first remain local/unclaimed; an import
  must never assign the old cloud owner identity simply from JSON contents.
- No profile/measurement/training detail in analytics, crash reports or
  unauthenticated logs.

## Suggested API (versioned, behind auth)

```
GET    /v1/me
GET    /v1/profiles
POST   /v1/profiles
GET    /v1/profiles/{profile_id}/changes?cursor=<opaque>
PUT    /v1/profiles/{profile_id}/workouts/{workout_id}  (If-Match + Idempotency-Key)
PUT    /v1/profiles/{profile_id}/measurements/{measurement_id}
PATCH  /v1/profiles/{profile_id}                       (If-Match)
DELETE /v1/profiles/{profile_id}                       (tombstone)
GET    /v1/me/export
DELETE /v1/me                                          (confirmed)
```

Conflict handling: append-only completed workouts are idempotent by workout
UUID; duplicate uploads are no-ops. Independent measurement IDs merge; a
conflicting edit to the same measurement must be surfaced for explicit
resolution, never silently overwritten.

The pure `domain/SyncMerge.kt` groundwork now implements this narrow
**local calculation** for validated same-account/same-profile snapshots:
identical workout/measurement UUIDs are idempotent, distinct UUIDs merge and
the same UUID with different values returns a conflict instead of silently
overwriting. Tests cover retries, two-phone divergent histories and cross-
account mismatch. This is NOT an authenticated backend, live sync queue,
server-side ownership test or usable sign-in: remote records cannot be trusted
until the API enforces verified account identity.
 Goal/equipment/settings profile edits
use optimistic concurrency (409/412) and show differences. Partial sync is
transactional; no complete-profile last-write-wins overwrite.

Implementation order:
1. Choose a FORMA-only hosting/auth/data environment; do not reuse SENTINEL.
2. Auth and data isolation threat model, privacy and recovery flows.
3. API migrations + backend authorization/RLS tests.
4. Android sign-in/sign-out, local account partition and encrypted tokens.
5. Opt-in local-to-cloud claim, incremental offline queue, two-device sync.
6. Infinix and Poco cross-login tests including simultaneous edit conflict,
   clock skew, interrupted networks, logout/remote revocation, restore,
   user data export/delete.
7. Permanent signing, verified updates, device physical acceptance.
Do not mark cross-device login working before end-to-end tests of the live service.

## Current iteration: safe local-user export and workout location (2026-10-24)

- New preferences key `training_place` stores HOME, GYM or COMBINED;
  existing DataStore records default safely to HOME and retain all previous
  preferences without database reset. The selection affects the main dashboard
  CTA, workout plan, exercise catalog and substitution choices consistently.
- HOME permits only selected home items + unconditional bodyweight movement.
  GYM includes selected machine equipment and usable freeweights;
  COMBINED opts into all selected user equipment. Saved exercise results are
  unaffected when the preference changes.
- Room profile contents and legacy serializable enum values remain intact.
- The in-app file-export control now defaults to the currently selected
  local profile. Export of **all** local profiles requires a separate warning
  and explicit confirmation, because measurements and limitations may belong
  to other people. Existing repository-wide backup/export remains available
  only behind that explicit action.
- Tests assert that a scoped export omits other profiles but retains its own
  measurements. This local file process is NOT cloud authentication.
- Android updater shares a strict and unit-tested update identity policy:
  same package, SHA-256 fingerprints, unchanged signing certificate and
  monotonic versionCode; unknown certificate or different app ID fails closed.
  HTTPS feeds and APKs are constrained to standard port 443. The channel is
  still disabled pending permanent signing custody and release hosting.

## Per-exercise visualization and body model

The Android candidate includes a **schematic** silhouette whose proportions
respond to latest shoulder/chest/waist/hip measurements and a modest
height/weight fallback. Circumference and BMI cannot reconstruct anatomy or
body-fat distribution; never advertise it as a 3D body scan.

Every exercise needs an authored, exercise-ID keyed instructional package:
- primary and secondary muscle zones (front/back highlights);
- front/side movement illustration or short filmed/animated sequence;
- setup, start, movement and end states; range-of-motion safety;
- common errors, contraindication notes and regressions;
- independent technical review and copyright/license evidence;
- offline asset fallback and accessible text alternative;
- tests ensuring displayed media matches the **exact** exercise ID.
Do not reuse a decorative bench-press photo to depict unrelated exercises,
or label the generic anatomy diagram as correct motion instruction.
These verified exercise-specific media assets are **not yet delivered**.

## Equipment and training safety

Legacy enum names BODYWEIGHT/DUMBBELL/BARBELL are preserved in stored JSON.
New equipment is additive; HOME and GYM categories may be combined per profile.
Exercise generation filters by selected equipment, always retaining accessible
bodyweight fallback. No pain diagnosis, no implicit permission to overtrain.
Before an exercise is marked production-ready, review technique cues and
safety with a qualified fitness professional.
