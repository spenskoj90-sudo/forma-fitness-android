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

## Server prototype — implemented in source, NOT deployed

- `auth.py`: verifies bearer JWT with trusted RS256 JWKS, exact OIDC
  issuer, API audience, expiry, issued-at and nonempty subject. It derives
  the database account UUID server-side using an HMAC with a durable secret.
- `api.py`: minimal FastAPI endpoints
  `GET /v1/me`, `GET/POST /v1/profiles`,
  `GET /v1/profiles/{profile_id}/snapshot` and
  `PUT /v1/profiles/{profile_id}/workouts/{record_id}` /
  `PUT /v1/profiles/{profile_id}/measurements/{record_id}`.
  It does not accept an unverified account ID and sets a transaction-local
  verified account context before touching forced-RLS tables.
  Repeat immutable uploads with the same UUID are no-ops; conflicting
  changes return 409 instead of overwriting another device's data.
- `test_auth.py` and `test_api_security.py`: offline negative tests for
  token forgery, audience, expiry, issuer, identity mapping, malformed data,
  duplicate submissions and missing owner access.
- `test_rls.py`: integration test applying the real SQL migration on
  disposable PostgreSQL 16 and confirming a restricted API login cannot
  read, modify or link another account's records. Runs as a service inside
  `.github/workflows/backend-security.yml`. CI success for the current
  commit is **not yet confirmed**.

To run non-cloud security checks:
```sh
cd backend
python -m pip install -r requirements-dev.txt
python -m pytest -q test_auth.py test_api_security.py
```

The integration test requires disposable database DSNs as documented in
`test_rls.py`, and refuses to run against remote/non-test databases.

Production server environment variables (all set only in protected hosting,
never in Git, ChatGPT or Android):
`FORMA_OIDC_ISSUER`, `FORMA_OIDC_AUDIENCE`,
`FORMA_OIDC_JWKS_URL`, `FORMA_DB_DSN`,
`FORMA_ACCOUNT_DERIVATION_KEY_B64` (base64 encoding of a **random,
durably backed-up 32-byte secret**). The identity derivation key must not
change without an explicit migration of all account IDs.

Server launch after security acceptance (example only):
`uvicorn api:app --host 127.0.0.1 --port 8080` from `backend/` with
HTTPS termination / security policy provided by the trusted host.

**Not production ready:** no public ingress/domain, auth provider, privacy
policy, ownership challenge for claiming old local profiles, secure account
recovery, per-user quotas, traffic rate limiting, robust pagination,
updates/deletes or device token revocation have been completed.

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
configured and NO synchronized FORMA sign-in available yet. The API and tests
are present only in version-controlled source. An owner can
currently transfer a single local profile by explicitly exporting and
importing its JSON file without involving any third-party backend.
