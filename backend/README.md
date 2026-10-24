# FORMA cloud account infrastructure (unprovisioned)

This folder is for **FORMA only**, isolated from SENTINEL and from any current
Neon/Render application in another project. The schema is an implementation
starting point, **not a deployed backend**.

## What is ready in source

- `migrations/001_account_owned_data.sql` defines account identities,
  separately owned profiles, workouts, measurements and per-account devices.
- Every child record has a compound FK to `(account_id, profile_id)`;
  a workout/measurement belonging to one person cannot be attached to a
  profile owned by someone else.
- Row-level security is enabled **and forced** on every table. Its policy uses
  `forma.verified_account_id`, which MUST come from verified server-side
  identity, not a mobile request field.
- Workouts and measurements have stable UUIDs, revision, timestamps and
  delete tombstones for retry-safe incremental synchronization.
- `../domain/src/main/kotlin/ru/forma/domain/SyncMerge.kt` implements
  local merge/conflict logic; it does not contact this database.

## Prerequisites before executing a migration

1. Create a **new dedicated FORMA** project/database. Do not run scripts on
   any SENTINEL project. A cloud project may incur charges; provisioning has
   intentionally not been performed merely by adding these files.
2. Choose and configure a verified identity provider. The backend must verify
   OIDC token signature, issuer, audience, expiry and subject; map this to a
   server-generated `account_id`. Never use a provided profile or account ID
   as proof of identity.
3. Keep DB credentials strictly on a backend. Android connects **only** to
   the HTTPS API with an access token, never to PostgreSQL directly.
4. Create a restricted, non-owner, non-superuser API DB role without
   `BYPASSRLS`, grant only necessary `forma` schema/table privileges.
   Restrict the API to parameterized prepared statements. Never expose
   arbitrary SQL, a database console or a setter for the account context
   to users; PostgreSQL custom GUCs are not secure if untrusted callers
   can execute SQL.
5. For each authorized request, open a DB transaction, set
   `SET LOCAL forma.verified_account_id = '<verified account UUID>'`
   from validated server-side authentication, run SQL, then commit/rollback.
   Use transaction pooling only with transaction-local identity to avoid
   leaking account identity into another request.
6. Cross-account integration tests are mandatory: read/write/update/delete
   by account A must be denied to account B, even when IDs are guessed.
   Also test revoked tokens and the inability to set account context via API.
7. Implement guarded request body validation, schema revision checks,
   optimistic-concurrency (409/412), idempotent retries, server-side
   account deletion/export, personal-data retention, backups and logging
   without measurements or account secrets.

## Operational status

NO backend deployed, NO cloud records created, NO emails sent, NO credentials
configured and NO synchronized FORMA sign-in available yet. An owner can
currently transfer a single local profile by explicitly exporting and
importing its JSON file without involving any third-party backend.
