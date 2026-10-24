"""Authenticated FORMA API foundation, disabled unless ALL server settings exist.

The Android app must never receive direct database credentials. Every DB
operation runs under SET LOCAL of a SERVER-DERIVED account ID and forced RLS.
Do not deploy until the DB role, OIDC, privacy and security tests are complete.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import os
from contextlib import asynccontextmanager, contextmanager
from typing import Annotated, Any
from uuid import UUID

import jwt
from fastapi import FastAPI, Header, HTTPException, status
from pydantic import BaseModel, Field
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from auth import Identity, TokenVerifier

DB_POOL: ConnectionPool | None = None
TOKEN_VERIFIER: TokenVerifier | None = None
ACCOUNT_DERIVATION_KEY: bytes | None = None
MAX_PROFILE_JSON = 32768
MAX_RECORD_JSON = 262144


def derived_account_id(identity: Identity, secret: bytes) -> UUID:
    if len(secret) < 32:
        raise ValueError("Account derivation key must be at least 256 bits")
    msg = (identity.issuer + "\x00" + identity.subject).encode("utf-8")
    digest = hmac.new(secret, msg, hashlib.sha256).digest()
    # Deterministic opaque UUID. Its secret must be retained through upgrades.
    return UUID(bytes=digest[:16], version=4)


def validate_record(identity: UUID, payload: dict[str, Any], limit: int) -> dict[str, Any]:
    if not isinstance(payload, dict) or str(payload.get("id", "")) != str(identity):
        raise HTTPException(422, "Record identity does not match URL")
    raw = json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    if len(raw.encode("utf-8")) > limit:
        raise HTTPException(413, "Document is too large")
    return payload


@asynccontextmanager
async def lifespan(app: FastAPI):
    global DB_POOL, TOKEN_VERIFIER, ACCOUNT_DERIVATION_KEY
    issuer = os.environ["FORMA_OIDC_ISSUER"]
    audience = os.environ["FORMA_OIDC_AUDIENCE"]
    jwks_url = os.environ["FORMA_OIDC_JWKS_URL"]
    dsn = os.environ["FORMA_DB_DSN"]
    encoded = os.environ["FORMA_ACCOUNT_DERIVATION_KEY_B64"]
    secret = base64.b64decode(encoded, validate=True)
    if len(secret) < 32:
        raise ValueError("Refusing to run without a durable 256-bit account derivation key")
    TOKEN_VERIFIER = TokenVerifier(issuer, audience, jwks_url)
    ACCOUNT_DERIVATION_KEY = secret
    DB_POOL = ConnectionPool(dsn, open=False, min_size=1, max_size=8, timeout=5)
    try:
        await asyncio.to_thread(DB_POOL.open, wait=True, timeout=15)
        yield
    finally:
        if DB_POOL is not None:
            await asyncio.to_thread(DB_POOL.close)
        DB_POOL = None
        TOKEN_VERIFIER = None
        ACCOUNT_DERIVATION_KEY = None


app = FastAPI(title="FORMA private account API", version="0.1", lifespan=lifespan,
              docs_url=None, redoc_url=None)


def bearer_account(authorization: Annotated[str | None, Header()] = None) -> tuple[Identity, UUID]:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Bearer token required")
    verifier, secret = TOKEN_VERIFIER, ACCOUNT_DERIVATION_KEY
    if verifier is None or secret is None:
        raise HTTPException(503, "Account backend is not configured")
    token = authorization[7:]
    try:
        identity = verifier.verify(token)
        return identity, derived_account_id(identity, secret)
    except (jwt.PyJWTError, ValueError):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or expired token") from None


@contextmanager
def owner_transaction(account_id: UUID):
    pool = DB_POOL
    if pool is None:
        raise HTTPException(503, "Account database unavailable")
    with pool.connection() as conn:
        with conn.transaction():
            conn.execute("SELECT set_config('forma.verified_account_id', %s, true)",
                         (str(account_id),))
            # Never accept account IDs from mobile input; every query uses
            # this verified transaction-local account and forced RLS.
            with conn.cursor(row_factory=dict_row) as cursor:
                yield cursor


def ensure_account(cur: Any, owner: tuple[Identity, UUID]) -> UUID:
    identity, account_id = owner
    cur.execute("""
        INSERT INTO forma.accounts(account_id, auth_issuer, auth_subject)
        VALUES (%s, %s, %s)
        ON CONFLICT (account_id) DO NOTHING
    """, (account_id, identity.issuer, identity.subject))
    cur.execute("""
        SELECT account_id FROM forma.accounts
        WHERE account_id=%s AND auth_issuer=%s AND auth_subject=%s
          AND deleted_at IS NULL
    """, (account_id, identity.issuer, identity.subject))
    if cur.fetchone() is None:
        raise HTTPException(403, "Account disabled or provider identity changed")
    return account_id


def ensure_profile(cur: Any, account: UUID, profile: UUID) -> None:
    cur.execute("""
        SELECT 1 FROM forma.profiles
        WHERE account_id=%s AND profile_id=%s AND deleted_at IS NULL
    """, (account, profile))
    if cur.fetchone() is None:
        raise HTTPException(404, "Profile not found")


class ProfilePayload(BaseModel):
    profile_id: UUID
    profile_document: dict[str, Any] = Field()


class RecordPayload(BaseModel):
    document: dict[str, Any] = Field()


@app.get("/v1/me")
def me(authorization: Annotated[str | None, Header()] = None):
    owner = bearer_account(authorization)
    with owner_transaction(owner[1]) as cur:
        account = ensure_account(cur, owner)
    return {"account_id": str(account)}


@app.get("/v1/profiles")
def profiles(authorization: Annotated[str | None, Header()] = None):
    owner = bearer_account(authorization)
    with owner_transaction(owner[1]) as cur:
        account = ensure_account(cur, owner)
        cur.execute("""
            SELECT profile_id, profile_document, revision FROM forma.profiles
            WHERE account_id=%s AND deleted_at IS NULL ORDER BY created_at,profile_id
            LIMIT 101
        """, (account,))
        rows = cur.fetchall()
        if len(rows) > 100:
            raise HTTPException(409, "Profile pagination required")
    return {"profiles": rows}


@app.post("/v1/profiles", status_code=201)
def create_profile(item: ProfilePayload, authorization: Annotated[str | None, Header()] = None):
    doc = validate_record(item.profile_id, item.profile_document, MAX_PROFILE_JSON)
    if any(doc.get(k) not in (None, [], {}) for k in ("history", "measurements", "active")):
        raise HTTPException(422, "Upload workout history and measurements separately")
    with owner_transaction((owner := bearer_account(authorization))[1]) as cur:
        account = ensure_account(cur, owner)
        cur.execute("""
            INSERT INTO forma.profiles(account_id,profile_id,profile_document)
            VALUES (%s,%s,%s)
            ON CONFLICT DO NOTHING RETURNING profile_id
        """, (account, item.profile_id, Jsonb(doc)))
        if cur.fetchone() is None:
            raise HTTPException(409, "Profile already exists")
    return {"profile_id": str(item.profile_id), "revision": 1}


@app.get("/v1/profiles/{profile_id}/snapshot")
def snapshot(profile_id: UUID, authorization: Annotated[str | None, Header()] = None):
    owner = bearer_account(authorization)
    with owner_transaction(owner[1]) as cur:
        account = ensure_account(cur, owner)
        ensure_profile(cur, account, profile_id)
        cur.execute("""
            SELECT profile_document, revision FROM forma.profiles
            WHERE account_id=%s AND profile_id=%s
        """, (account, profile_id))
        profile = cur.fetchone()
        cur.execute("""
            SELECT workout_id,workout_document,revision FROM forma.workouts
            WHERE account_id=%s AND profile_id=%s AND deleted_at IS NULL
            ORDER BY workout_id LIMIT 10001
        """, (account, profile_id))
        workouts = cur.fetchall()
        cur.execute("""
            SELECT measurement_id,measurement_document,revision FROM forma.measurements
            WHERE account_id=%s AND profile_id=%s AND deleted_at IS NULL
            ORDER BY measurement_id LIMIT 10001
        """, (account, profile_id))
        measurements = cur.fetchall()
        if len(workouts) > 10000 or len(measurements) > 10000:
            raise HTTPException(409, "Incremental sync needed for large histories")
    return {"profile": profile, "workouts": workouts, "measurements": measurements}


def store_immutable_record(
    account: UUID, profile: UUID, item_id: UUID, doc: dict[str, Any],
    table: str, id_column: str, document_column: str, cur: Any
) -> bool:
    # Call sites must supply these hardcoded table names only.
    if (table, id_column, document_column) not in (
        ("forma.workouts", "workout_id", "workout_document"),
        ("forma.measurements", "measurement_id", "measurement_document"),
    ):
        raise ValueError("Invalid record target")
    ensure_profile(cur, account, profile)
    cur.execute(f"""
        INSERT INTO {table}(account_id,profile_id,{id_column},{document_column})
        VALUES (%s,%s,%s,%s)
        ON CONFLICT DO NOTHING
        RETURNING {id_column}
    """, (account, profile, item_id, Jsonb(doc)))
    if cur.fetchone() is not None:
        return True
    cur.execute(f"""
        SELECT {document_column} AS document FROM {table}
        WHERE account_id=%s AND profile_id=%s AND {id_column}=%s
          AND deleted_at IS NULL
    """, (account, profile, item_id))
    existing = cur.fetchone()
    if existing is None or existing["document"] != doc:
        raise HTTPException(409, "Record with same ID differs: resolve conflict")
    return False


@app.put("/v1/profiles/{profile_id}/workouts/{record_id}")
def put_workout(profile_id: UUID, record_id: UUID, item: RecordPayload,
                authorization: Annotated[str | None, Header()] = None):
    doc = validate_record(record_id, item.document, MAX_RECORD_JSON)
    owner = bearer_account(authorization)
    with owner_transaction(owner[1]) as cur:
        account = ensure_account(cur, owner)
        created = store_immutable_record(
            account, profile_id, record_id, doc, "forma.workouts",
            "workout_id", "workout_document", cur
        )
    return {"record_id": str(record_id), "created": created}


@app.put("/v1/profiles/{profile_id}/measurements/{record_id}")
def put_measurement(profile_id: UUID, record_id: UUID, item: RecordPayload,
                    authorization: Annotated[str | None, Header()] = None):
    doc = validate_record(record_id, item.document, MAX_RECORD_JSON)
    owner = bearer_account(authorization)
    with owner_transaction(owner[1]) as cur:
        account = ensure_account(cur, owner)
        created = store_immutable_record(
            account, profile_id, record_id, doc, "forma.measurements",
            "measurement_id", "measurement_document", cur
        )
    return {"record_id": str(record_id), "created": created}
