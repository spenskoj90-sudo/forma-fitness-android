# FORMA — Firebase authorization and Android transport

The Android SDK transport reads server snapshots and compares the target and
its parent inside a Firestore transaction. Existing Room consent and outbox
code supply records. The application currently does not enable account login,
transport scheduling, inbound sync or access to the production project.

- Run `npm run test:rules` for the Rules suite. Android CI runs
  `npm run test:android-sync` on API 34 with both emulators, using synthetic
  email verification, local consent, offline replay, tombstones and account
  switching. Both commands use `demo-forma-rules` and loopback endpoints.
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

Issue #8 tracks account UI, local UID isolation, user consent, inbound sync,
conflict resolution, real provider checks, export, deletion and physical device
verification. Auth identity processing in US data centers remains part of the
PROJECTS privacy gate.
