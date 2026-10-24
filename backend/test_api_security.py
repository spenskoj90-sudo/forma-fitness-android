"""No database or external auth provider required for API guard tests."""
from uuid import uuid4

import jwt
import pytest
from fastapi import HTTPException

import api
from auth import Identity, derived_account_id

ISSUER = "https://id.forma.example/"


class FakeVerifier:
    def verify(self, token: str) -> Identity:
        if token != "valid":
            raise jwt.InvalidTokenError("invalid test token")
        return Identity(ISSUER, "verified-owner")


def test_bearer_is_required_and_account_is_derived_from_verified_subject(monkeypatch):
    monkeypatch.setattr(api, "TOKEN_VERIFIER", FakeVerifier())
    monkeypatch.setattr(api, "ACCOUNT_DERIVATION_KEY", b"k" * 32)
    with pytest.raises(HTTPException) as missing:
        api.bearer_account(None)
    assert missing.value.status_code == 401
    with pytest.raises(HTTPException) as fake:
        api.bearer_account("Bearer forged")
    assert fake.value.status_code == 401
    identity, owner = api.bearer_account("Bearer valid")
    assert owner == derived_account_id(identity, b"k" * 32)


def test_missing_server_configuration_fails_closed(monkeypatch):
    monkeypatch.setattr(api, "TOKEN_VERIFIER", None)
    monkeypatch.setattr(api, "ACCOUNT_DERIVATION_KEY", None)
    with pytest.raises(HTTPException) as missing:
        api.bearer_account("Bearer valid")
    assert missing.value.status_code == 503


def test_record_id_validation_size_cap_and_nonfinite_number():
    owner = uuid4()
    accepted = {"id": str(owner), "sets": []}
    assert api.validate_record(owner, accepted, 1000) == accepted
    for bad in ({"id": str(uuid4())}, {"id": str(owner), "weight": float("nan")}):
        with pytest.raises(HTTPException) as rejected:
            api.validate_record(owner, bad, 1000)
        assert rejected.value.status_code == 422
    with pytest.raises(HTTPException) as too_large:
        api.validate_record(owner, {"id": str(owner), "notes": "a" * 5000}, 100)
    assert too_large.value.status_code == 413


class FakeCursor:
    """Only simulates the 3 queries used by store_immutable_record."""
    def __init__(self, *, profile_exists=True, created=False, stored=None):
        self.profile_exists = profile_exists
        self.created = created
        self.stored = stored
        self.current = None
    def execute(self, query, args):
        if "SELECT 1 FROM forma.profiles" in query:
            self.current = {"exists": True} if self.profile_exists else None
        elif "INSERT INTO forma." in query:
            self.current = {"id": "inserted"} if self.created else None
        elif "AS document FROM forma." in query:
            self.current = {"document": self.stored} if self.stored is not None else None
        else:
            raise AssertionError("Unexpected query")
    def fetchone(self):
        return self.current


def test_record_upload_is_idempotent_and_conflicts_do_not_overwrite():
    owner, profile, record = uuid4(), uuid4(), uuid4()
    doc = {"id": str(record), "sets": [1]}
    kwargs = ("forma.workouts", "workout_id", "workout_document")
    assert api.store_immutable_record(owner, profile, record, doc,
                                      *kwargs, FakeCursor(created=True)) is True
    assert api.store_immutable_record(owner, profile, record, doc,
                                      *kwargs, FakeCursor(stored=doc)) is False
    with pytest.raises(HTTPException) as conflict:
        api.store_immutable_record(owner, profile, record, doc,
                                   *kwargs, FakeCursor(stored={"id": str(record), "sets": [2]}))
    assert conflict.value.status_code == 409


def test_upload_to_missing_or_unowned_profile_fails_before_insert():
    owner, profile, record = uuid4(), uuid4(), uuid4()
    with pytest.raises(HTTPException) as denied:
        api.store_immutable_record(
            owner, profile, record, {"id": str(record)},
            "forma.measurements", "measurement_id", "measurement_document",
            FakeCursor(profile_exists=False)
        )
    assert denied.value.status_code == 404
