"""Integration test for the actual PostgreSQL RLS migration.

Runs against an ephemeral test PostgreSQL only; never against personal data.
CI must provide FORMA_TEST_ADMIN_DSN and FORMA_TEST_API_DSN.
"""
from __future__ import annotations

import os
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg.errors import ForeignKeyViolation, InsufficientPrivilege
from psycopg.types.json import Jsonb

ADMIN = os.environ.get("FORMA_TEST_ADMIN_DSN")
API = os.environ.get("FORMA_TEST_API_DSN")


@pytest.fixture(scope="module")
def prepared():
    if not ADMIN or not API:
        pytest.skip("Ephemeral PostgreSQL test DSNs not configured")
    # Test database is created by GitHub Actions as a disposable Postgres service.
    # Never allow this fixture to run against arbitrary production credentials.
    if "localhost:5432/forma_test" not in ADMIN:
        raise RuntimeError("RLS tests refuse non-local or non-test database")
    script = (Path(__file__).parent / "migrations/001_account_owned_data.sql").read_text()
    with psycopg.connect(ADMIN, autocommit=True) as admin:
        admin.execute(script, prepare=False)
        admin.execute("CREATE ROLE forma_api LOGIN PASSWORD 'ephemeral_forma_ci_only' NOSUPERUSER NOBYPASSRLS")
        admin.execute("GRANT USAGE ON SCHEMA forma TO forma_api")
        admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA forma TO forma_api")
        admin.execute("GRANT EXECUTE ON FUNCTION forma.verified_account_id() TO forma_api")
        forced = admin.execute("""
            SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='forma' AND c.relkind='r'
              AND c.relrowsecurity AND c.relforcerowsecurity
        """).fetchone()[0]
        assert forced == 5
    return True


def test_rls_rejects_cross_account_reads_writes_and_profile_links(prepared):
    owner_a, owner_b, profile, workout, measurement = (uuid4() for _ in range(5))

    with psycopg.connect(API) as conn:
        with conn.transaction():
            conn.execute("SELECT set_config('forma.verified_account_id', %s, true)", (str(owner_a),))
            conn.execute("INSERT INTO forma.accounts(account_id,auth_issuer,auth_subject) VALUES(%s,%s,%s)",
                         (owner_a, "https://test.example", "human-a"))
            conn.execute("INSERT INTO forma.profiles(account_id,profile_id,profile_document) VALUES(%s,%s,%s)",
                         (owner_a, profile, Jsonb({"id": str(profile), "name": "A"})))
            conn.execute("INSERT INTO forma.workouts(account_id,profile_id,workout_id,workout_document) VALUES(%s,%s,%s,%s)",
                         (owner_a, profile, workout, Jsonb({"id": str(workout)})))
            conn.execute("INSERT INTO forma.measurements(account_id,profile_id,measurement_id,measurement_document) VALUES(%s,%s,%s,%s)",
                         (owner_a, profile, measurement, Jsonb({"id": str(measurement)})))

    with psycopg.connect(API) as conn:
        with conn.transaction():
            conn.execute("SELECT set_config('forma.verified_account_id', %s, true)", (str(owner_b),))
            assert conn.execute("SELECT count(*) FROM forma.accounts").fetchone()[0] == 0
            assert conn.execute("SELECT count(*) FROM forma.profiles").fetchone()[0] == 0
            assert conn.execute("SELECT count(*) FROM forma.workouts").fetchone()[0] == 0
            assert conn.execute("SELECT count(*) FROM forma.measurements").fetchone()[0] == 0
            assert conn.execute("UPDATE forma.profiles SET revision=revision+1 WHERE profile_id=%s", (profile,)).rowcount == 0
            assert conn.execute("DELETE FROM forma.workouts WHERE workout_id=%s", (workout,)).rowcount == 0

        with pytest.raises(InsufficientPrivilege):
            with conn.transaction():
                conn.execute("SELECT set_config('forma.verified_account_id', %s, true)", (str(owner_b),))
                conn.execute("INSERT INTO forma.profiles(account_id,profile_id,profile_document) VALUES(%s,%s,%s)",
                             (owner_a, uuid4(), Jsonb({"id": str(uuid4())})))
        with pytest.raises(ForeignKeyViolation):
            with conn.transaction():
                conn.execute("SELECT set_config('forma.verified_account_id', %s, true)", (str(owner_b),))
                conn.execute("INSERT INTO forma.accounts(account_id,auth_issuer,auth_subject) VALUES(%s,%s,%s)",
                             (owner_b, "https://test.example", "human-b"))
                conn.execute("INSERT INTO forma.workouts(account_id,profile_id,workout_id,workout_document) VALUES(%s,%s,%s,%s)",
                             (owner_b, profile, uuid4(), Jsonb({"id": str(uuid4())})))

    # A later transaction with no verified account must not inherit account A/B.
    with psycopg.connect(API) as conn:
        assert conn.execute("SELECT count(*) FROM forma.profiles").fetchone()[0] == 0
