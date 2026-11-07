# FORMA — repository instruction router

This file routes contributors to the current policy and real workflows. Before changing a subtree, read any closer-scoped AGENTS.md, the exact live branch/PR HEAD/base and the existing GitHub issue. Do not copy mutable checkpoints into this file.

## Sources of truth
- FORMA product/engineering specification: https://docs.google.com/document/d/1BaJlUKzFhbzCiUtYvxEJfLUpkd1y9nGCizaiYvnqGUg/edit
- Shared SENTINEL/FORMA regulation: https://docs.google.com/document/d/1furikabuGC5DduHfELkyERXoWiTKbzvDEw5pSojlfvE/edit
- PROJECTS infrastructure/governance: https://docs.google.com/document/d/1hlzUCjZ4LejSBW_x3uAA_Bq7dGZoMJQd_73NjJFdN8Q/edit
- Live source/status/evidence: existing FORMA GitHub Issues #7–#10 and their linked PRs. No duplicate PR/Issue or shadow tracker.

## Repository and checks
- `app/`: Kotlin/Compose, Room/DataStore, services, unit and `src/androidTest` UI/emulator tests; `domain/`: planner/workout rules and JVM tests; `backend/`: existing prototype/security tests (not deployed Firebase sync).
- `scripts/`: release, schema and emulator entrypoints; `.github/workflows/`: authoritative Android/backend and protected release jobs; `docs/`, `verification/`: references and synthetic test evidence.
- Match `README.md` and `.github/workflows/android.yml`: JDK17, Android SDK35, pinned `./gradlew` (Gradle 8.10.2). Create **only disposable FORMA debug signing** per README; never use/recreate a production key for build checks.
- Android verify: `python3 scripts/check-release-workflows.py`; `python3 -m unittest discover -s scripts -p 'test*.py' -v`; `node --test scripts/test_release_delivery.mjs`; `./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`; `python3 scripts/check-room-schema.py`.
- Instrumentation: `.github/workflows/android.yml` runs API26/30/34 using `bash scripts/run-emulator-tests.sh <API>` with preprovisioned Android emulator/debug APK; API34 requires the workflow's legacy build. No emulator/SDK means `NOT_RUN`, never PASS.
- Backend checks in `.github/workflows/backend-security.yml`: `cd backend`, install `requirements-dev.txt`, then `python -m compileall -q auth.py api.py test_auth.py test_api_security.py test_rls.py` and `python -m pytest -q test_auth.py test_api_security.py test_rls.py` against disposable CI PostgreSQL. Passing does not prove Firebase identity/runtime isolation.

## Invariants, writers and gates
- Preserve applicationId `ru.forma.fitness`, Room schema/migrations and IDs, DataStore settings, profiles, measurements, workouts/history, offline-first behavior and compatible permanent signing/update lineage. No destructive fallback or cross-account data mixing. Preserve attribution/media rights evidence.
- Exactly one source writer per branch across CHAT/CODEX/Work. Confirm STOPPED handoff, exact live HEAD/base **before** write; use safe fast-forward/CAS updates and post-write readback. Commit small coherent changes in existing PR; verify touched code and required final-SHA CI. Record PASS/FAIL/NOT_RUN in existing Issue/PR.
- `SOURCE_ONLY`, `CI_PASS`, emulator/runtime evidence and physical `OWNER_ACCEPTED` are different. A green rerun does not establish the cause of a prior failure; a source mock does not replace working integrations.
- Separate owner gate: expenses/budget, production/release/deploy, permanent signing keys/custody, privileged ACL, dangerous migration, force-push/history rewrite, irreversible removal and disclosure of data. No secrets, real workout/health records or recovery material in repo, chat, Docs or CI artifacts.
