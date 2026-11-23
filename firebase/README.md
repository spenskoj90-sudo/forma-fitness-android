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
WorkManager and the cloud panel also require the build opt-in
`-PformaCloudSyncEnabled=true`. Live provider activation and App Check remain behind
the applicable production gates.

- The outgoing queue uses unique connected-network work on new pending records
  and an hourly fallback. Room holds the payload. Each bounded batch pins one
  verified account and keeps pending records through retries and process exit.
- The account panel reads the verified user's own profiles from the server.
  Restoring a selected profile requires confirmation. A Room transaction preserves
  UUIDs and imports exact server revision receipts. Local divergent records and
  rows awaiting delivery are kept. A cloud tombstone is never restored as a live
  record and does not silently delete an existing local record. The panel
  reports conflicts to the owner for separate resolution.
- An account workspace can create a verified encrypted backup of the selected
  profile using the same offline format as the guest export. Scope is rechecked
  before and after file I/O.

- Run `npm run test:rules` for the Rules suite. Android CI runs
  `npm run test:android-sync` on API 34 with both emulators, using synthetic
  registration, email verification, password reset request, sign-in, local
  consent, offline replay, tombstones, account switching, revocation of
  workout commands from an old workspace, and restore into a separate Room
  database with conflicting edits. Both commands
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

Issue #8 tracks account-scoped workout access, the consent UI, inbound sync,
conflict resolution, real provider checks, export, deletion and physical
device verification. Auth identity processing in US data centers remains
part of the PROJECTS privacy gate.
