"""FORMA OIDC bearer verification. No debug bypass or caller-chosen identity."""
from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from urllib.parse import urlparse

import jwt
from jwt import InvalidTokenError, PyJWKClient


@dataclass(frozen=True)
class Identity:
    issuer: str
    subject: str


def validate_provider(issuer: str, audience: str, jwks_url: str) -> None:
    a, b = urlparse(issuer), urlparse(jwks_url)
    if not issuer or not audience or not jwks_url:
        raise ValueError("OIDC_ISSUER, OIDC_AUDIENCE and OIDC_JWKS_URL are required")
    if a.scheme != "https" or b.scheme != "https" or not a.netloc or a.hostname != b.hostname:
        raise ValueError("OIDC issuer and JWKS must share an HTTPS hostname")
    if a.username or a.password or b.username or b.password or a.fragment or b.fragment:
        raise ValueError("OIDC URL must not contain credentials or fragment")
    if a.port not in (None, 443) or b.port not in (None, 443):
        raise ValueError("OIDC services must use HTTPS 443")


def verify_claims(token: str, public_key: object, issuer: str, audience: str) -> Identity:
    """Only RS256 is supported. Never derive algorithms from untrusted JWT header."""
    claims = jwt.decode(
        token,
        public_key,
        algorithms=["RS256"],
        issuer=issuer,
        audience=audience,
        options={"require": ["iss", "aud", "sub", "iat", "exp"]},
        leeway=30,
    )
    subject = claims.get("sub")
    if not isinstance(subject, str) or not subject or len(subject) > 512:
        raise InvalidTokenError("Missing or invalid subject")
    return Identity(issuer=issuer, subject=subject)


class TokenVerifier:
    def __init__(self, issuer: str, audience: str, jwks_url: str):
        validate_provider(issuer, audience, jwks_url)
        self.issuer = issuer
        self.audience = audience
        self.client = PyJWKClient(jwks_url, cache_keys=True, lifespan=300)

    def verify(self, token: str) -> Identity:
        if len(token) > 16384 or not token:
            raise InvalidTokenError("Bearer token length invalid")
        signing_key = self.client.get_signing_key_from_jwt(token)
        return verify_claims(token, signing_key.key, self.issuer, self.audience)


@lru_cache(maxsize=1)
def configured_verifier() -> TokenVerifier:
    return TokenVerifier(
        os.environ["FORMA_OIDC_ISSUER"],
        os.environ["FORMA_OIDC_AUDIENCE"],
        os.environ["FORMA_OIDC_JWKS_URL"],
    )
