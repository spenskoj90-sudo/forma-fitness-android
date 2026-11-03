"""Cryptographic and account-isolation regressions; no live cloud required."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from auth import Identity, derived_account_id, validate_provider, verify_claims

ISSUER = "https://id.forma.example/"
AUDIENCE = "forma-api"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def signed_token(**changes):
    now = datetime.now(timezone.utc)
    claims = {
        "iss": ISSUER,
        "sub": "human-1",
        "aud": AUDIENCE,
        "iat": now,
        "exp": now + timedelta(minutes=5),
    }
    claims.update(changes)
    return jwt.encode(claims, KEY, algorithm="RS256")


def test_valid_jwt_uses_verified_identity_only():
    token = signed_token()
    assert verify_claims(token, KEY.public_key(), ISSUER, AUDIENCE) == Identity(ISSUER, "human-1")


@pytest.mark.parametrize("claims", [
    {"aud": "other-service"},
    {"iss": "https://attacker.example/"},
    {"exp": datetime.now(timezone.utc) - timedelta(minutes=5)},
    {"sub": ""},
])
def test_wrong_audience_issuer_expiry_or_subject_fails(claims):
    with pytest.raises(jwt.PyJWTError):
        verify_claims(signed_token(**claims), KEY.public_key(), ISSUER, AUDIENCE)


def test_unsigned_token_and_hmac_algorithm_confusion_are_rejected():
    now = datetime.now(timezone.utc)
    claims = dict(iss=ISSUER, sub="human-1", aud=AUDIENCE, iat=now,
                  exp=now + timedelta(minutes=5))
    none_token = jwt.encode(claims, key="", algorithm="none")
    with pytest.raises(jwt.PyJWTError):
        verify_claims(none_token, KEY.public_key(), ISSUER, AUDIENCE)
    hs_token = jwt.encode(claims, key="server-secret", algorithm="HS256")
    with pytest.raises(jwt.PyJWTError):
        verify_claims(hs_token, KEY.public_key(), ISSUER, AUDIENCE)


def test_missing_exp_or_iat_fails_closed():
    now = datetime.now(timezone.utc)
    claims = dict(iss=ISSUER, sub="human-1", aud=AUDIENCE, iat=now,
                  exp=now + timedelta(minutes=5))
    for missing in ("exp", "iat"):
        without = {k: v for k, v in claims.items() if k != missing}
        token = jwt.encode(without, KEY, algorithm="RS256")
        with pytest.raises(jwt.PyJWTError):
            verify_claims(token, KEY.public_key(), ISSUER, AUDIENCE)


def test_account_ids_are_distinct_across_issuers_subjects_and_secrets():
    secret = b"x" * 32
    owner = Identity(ISSUER, "human-1")
    account = derived_account_id(owner, secret)
    assert account == derived_account_id(owner, secret)
    assert account != derived_account_id(Identity(ISSUER, "human-2"), secret)
    assert account != derived_account_id(Identity("https://other.example/", "human-1"), secret)
    assert account != derived_account_id(owner, b"y" * 32)
    with pytest.raises(ValueError):
        derived_account_id(owner, b"short")


def test_oidc_provider_must_be_configured_over_same_hostname_https():
    validate_provider(ISSUER, AUDIENCE, "https://id.forma.example/.well-known/jwks.json")
    for i, jwks in [
        (ISSUER, "http://id.forma.example/jwks"),
        (ISSUER, "https://malicious.example/jwks"),
        (ISSUER, "https://id.forma.example:8080/jwks"),
        ("http://id.forma.example/", "https://id.forma.example/jwks"),
    ]:
        with pytest.raises(ValueError):
            validate_provider(i, AUDIENCE, jwks)


def test_verifier_rejects_warmed_removed_jwks_key_after_ttl_and_accepts_rotation():
    """Exercise TokenVerifier.verify and PyJWT's real two-tier key cache.

    Only synthetic keys/JWKS are used. There is no network request or account.
    """
    from auth import TokenVerifier
    old = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    new = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    def jwk(key, kid):
        item = jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key(), as_dict=True)
        return dict(item, kid=kid, alg="RS256", use="sig")

    live = {"keys": [jwk(old, "old-key")]}
    available = True
    verifier = TokenVerifier(ISSUER, AUDIENCE, ISSUER + ".well-known/jwks.json")
    client = verifier.client
    assert client.jwk_set_cache is not None

    def synthetic_fetch():
        # Mirror PyJWKClient.fetch_data's cache behavior without network calls.
        if not available:
            raise jwt.PyJWKClientConnectionError("Synthetic JWKS outage")
        response = {"keys": list(live["keys"])}
        client.jwk_set_cache.put(response)
        return response

    client.fetch_data = synthetic_fetch
    now = datetime.now(timezone.utc)
    claims = dict(iss=ISSUER, sub="human-1", aud=AUDIENCE,
                  iat=now, exp=now + timedelta(minutes=5))
    old_token = jwt.encode(claims, old, algorithm="RS256", headers={"kid": "old-key"})
    new_token = jwt.encode(claims, new, algorithm="RS256", headers={"kid": "new-key"})

    assert verifier.verify(old_token) == Identity(ISSUER, "human-1")
    live["keys"] = [jwk(new, "new-key")]
    # The old JWKS is still within its permitted 300-second lifetime.
    assert verifier.verify(old_token) == Identity(ISSUER, "human-1")
    # Advance cached-set age deterministically without sleeping or network.
    client.jwk_set_cache.jwk_set_with_timestamp.timestamp -= 301
    with pytest.raises(jwt.PyJWKClientError):
        verifier.verify(old_token)
    assert verifier.verify(new_token) == Identity(ISSUER, "human-1")

    client.jwk_set_cache.jwk_set_with_timestamp.timestamp -= 301
    available = False
    with pytest.raises(jwt.PyJWKClientConnectionError):
        verifier.verify(new_token)
