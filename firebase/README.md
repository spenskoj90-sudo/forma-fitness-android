# FORMA F4 — local-only Firestore authorization contract

This is a **security rules + emulator test** unit, not a Firebase deployment,
Android sign-in, account import or a working cloud synchronization client.

- Use only \`npm run test:rules\` from this directory. The npm script pins
  \`--project demo-forma-rules\`, uses the Firestore emulator and has **no deploy**
  command. \`firebase.json\` explicitly loads \`firestore.rules\`; tests load it again.
- No \`.firebaserc\`, credential, \`google-services.json\`, production project ID
  or real user data is required. All profile/workout/measurement values are synthetic.
- Only Firebase-verified \`request.auth.uid\` controls document-path ownership:
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

This initial Rules contract is deliberately incomplete as a product:
Room-first durable outbox, user consent, client auth, real two-user provider
tests, rate/size strategy, export/deletion and physical device checks remain
OPEN in existing GitHub Issue #8. Auth identity processing in US data centers
is already recorded in the PROJECTS privacy gate; EU Firestore does not make
the whole system EU-only.
