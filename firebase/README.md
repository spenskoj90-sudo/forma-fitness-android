# FORMA — Firebase authorization and Android transport

The Android SDK transport reads server snapshots and compares the target and
its parent inside a Firestore transaction. Existing Room consent and outbox
code supply records. When a build supplies the default Firebase app configuration,
the account screen supports email registration, sign-in, confirmation, password reset
and sign-out through the Android Auth SDK. A verified account can explicitly link
the selected guest profile. The account address and profile name are shown before
consent. The local binding and initial outbox commit in one Room transaction.
The corresponding account space uses its own profile list, workout commands and
history. A workspace identifier expires when the space closes, the account changes,
or the app process dies. Reopening an unfinished workout pauses it before rendering
and preserves the last recorded sets and duration. Guest profiles keep their existing
workflow. The repository ships without a default Firebase app configuration.
WorkManager and the cloud panel require a build opt-in. Debug uses
`-PformaCloudSyncEnabled=true` for the emulator fixture. The protected signing job
can supply Firebase client settings to the release variant after provider approval.
Live provider activation and App Check enforcement remain behind separate gates.

- The outgoing queue uses unique connected-network work on new pending records
  and an hourly fallback. Room holds the payload. Each bounded batch pins one
  verified account and keeps pending records through retries and process exit.
- The Android client refreshes Firebase Auth at the start of each background
  cloud batch and before interactive cloud reads and conflict decisions. The
  account screen also offers a direct session check. When Firebase rejects a
  current session as invalid, the app closes that account's local workspace and
  retains its Room records and queue. Network failures leave local offline
  access available and background work can retry. This client behavior depends
  on contacting Firebase. Server-wide invalidation of previously issued ID
  tokens is tracked separately in Issue #8. See [Firebase session management](https://firebase.google.com/docs/auth/admin/manage-sessions).
- The account panel reads the verified user's own profiles from the server.
  Restoring a selected profile requires confirmation. A Room transaction preserves
  UUIDs and imports exact server revision receipts. Local divergent records and
  rows awaiting delivery are kept. A cloud tombstone is never restored as a live
  record and does not silently delete an existing local record. The panel
  reports conflicts to the signed-in user for explicit resolution.
- Inbound work runs after sign-in and periodically when connected. It visits only
  present profiles already bound to that UID by consent. Child pages use a
  persisted server timestamp and document ID cursor. Room commits each cursor
  with the records and the conflict markers for that page. An invalid profile
  leaves its cursors intact while other profiles can advance.
- The conflict dialog compares a fresh cloud revision with the device record.
  Confirming either version saves both snapshots and the displaced queue in a
  local account-scoped archive. Taking the device version rebases it on the exact
  verified revision for retryable delivery. A child tombstone requires explicit
  confirmation to remove a local record. A parent tombstone preserves local
  history and blocks changes under the deleted cloud profile. The existing
  encrypted profile backup does not export the synchronization archive.
- An account workspace can create a verified encrypted backup of the selected
  profile using the same offline format as the guest export. Scope is rechecked
  before and after file I/O.

- Run `npm run test:rules` for the Rules suite and the two-account REST smoke against the Auth and Firestore emulators. Android CI runs
  `npm run test:android-sync` on API 34 with both emulators, using synthetic
  registration, email verification, password reset request, sign-in, local
  consent, offline replay, tombstones, account switching, revocation of
  workout commands from an old workspace, disabled-account handling and
  restore into a separate Room
  database with conflicting edits, a stale manual decision, a rebased device
  choice, a cloud deletion choice and an incremental inbound Worker. Both commands
  use `demo-forma-rules` and loopback endpoints.
- No \`.firebaserc\`, credential, \`google-services.json\`, production project ID
  or real user data is required. All profile/workout/measurement values are synthetic.
- Firestore requires a verified email claim and an exact `request.auth.uid`
  match for document-path ownership:
  \`accounts/{uid}/profiles/{profileId}\`, then \`measurements/{measurementId}\`
  and \`workouts/{workoutId}\`. Parent account documents and other collections
  remain denied. Identifiers are stable local UUID-compatible strings.
- Records have a strict immutable identity, schema version 1, positive revision,
  server-side timestamps, a distinct \`opId\` per mutation, and a \`deleted\`
  tombstone instead of hard delete. An exact identical replay is permitted.
  Stale writes and resurrection are forbidden. The uploader must read/reconcile
  after ambiguous network errors; Rules are not a general conflict resolver.
- Source-only Rules validate the full `CloudProfilePayload` projection: known enum values,
  unique equipment/priorities/day selections, expected types and no history/active
  session fields. A finished/STOPPED workout must match bounded
  `CloudWorkoutPayload` and `TrainingDay` fields; the Firestore Rules
  language cannot generally validate every member of arbitrarily long nested
  `sets`/`exercises` lists, which still needs a reviewed transport/schema
  acceptance gate before real account import.
- The payload date contract preserves valid local history at Unix epoch zero
  (`dateMs` and `startedMs` may equal 0, never negative); nonweight body
  measurements must be finite and strictly positive, with no 0.01 rounding or
  rewriting existing Room values.
- Finished or STOPPED workouts only; **active sessions stay local**.
  No automatic guest-profile attachment or cross-profile imports.
- Deploying Rules, creating the real database, registering \`ru.forma.fitness\`,
  enabling Firebase Auth/App Check, or moving local user data each requires
  separate gates. PROJECTS owns the pre-approved isolated Spark project
  \`forma-ee2e9\`, whose \`(default)\` Firestore region remains pending at
  \`europe-west3\` (Frankfurt). **Do not deploy to this project from this PR.**

## Two-account provider smoke

The owner-approved live smoke targets only the existing isolated Spark project
`forma-ee2e9`. Run `npm run test:provider:live` from this directory in a
terminal with an authorized Google Cloud CLI session. It uses the existing
Firebase browser API key and checks that the database region and the published
rules match this checkout before creating fixtures. If the intended browser key
has a custom display name, set `FORMA_FIREBASE_WEB_KEY_RESOURCE` to its Google
Cloud resource name in this project. The CLI session needs to
read the database, rules and existing key and manage the two synthetic Auth
accounts. No service-account key file or real user data is needed.

The probe signs in through the provider's Email/Password API and uses the
returned Firebase ID tokens for Firestore requests. It verifies an unverified
user is denied, both verified UIDs can write and read their own profiles and
measurements, and each is denied access to the other's paths. It disables
the two generated Auth users and verifies cleanup. Synthetic Firestore
fixtures stay under those disabled UIDs. The evidence contains only the
synthetic run ID, result labels and the rules digest.

The live smoke covers the provider REST contract. Native Android, App Check
and physical-device results require their separate checks. CI calls only the
local emulators and supplies no live project credentials.

## Protected release configuration

The release build can opt in with `FORMA_CLOUD_RELEASE_ENABLED=true`. Pass the
registered Android client's `FORMA_FIREBASE_PROJECT_ID`,
`FORMA_FIREBASE_ANDROID_APP_ID` and `FORMA_FIREBASE_ANDROID_API_KEY`
through the protected `forma-release` environment. The client API key must have
provider-side restrictions appropriate to the Android client. A release opt-in
with missing or malformed values fails Gradle configuration. The debug variant
does not package these Firebase resources. Published repositories and CI logs
must not contain the live values.

The build derives the numeric Google Cloud project number from the registered
Android app ID. Play Integrity App Check uses that number as the Firebase
sender ID in its token request. The unsigned resource check in Android CI uses
synthetic configuration.

The opted-in release installs the Play Integrity App Check provider before the
account transport becomes available. For direct APK distribution, link the same
Google Cloud project. Firebase recommends leaving `PLAY_RECOGNIZED` and `LICENSED`
unrequired and selecting Device integrity. Verify the accepted signing certificate
fingerprint and actual device verdicts on Infinix and Poco before enforcement.
Check App Check request metrics before enabling enforcement. Keep credentials for
server or admin APIs outside Android builds. See [Firebase App Check for Android][app-check-doc].

[app-check-doc]: https://firebase.google.com/docs/app-check/android/play-integrity-provider

Issue #8 tracks account-scoped workout access, the consent UI, inbound sync,
conflict resolution, real provider checks, export, deletion and physical
device verification. Auth identity processing in US data centers remains
part of the PROJECTS privacy gate.
