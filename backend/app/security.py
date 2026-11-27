from datetime import datetime, timedelta, timezone
from uuid import uuid4

import jwt
from pwdlib import PasswordHash

from .config import get_settings

_password_hash = PasswordHash.recommended()
DUMMY_HASH = _password_hash.hash("not-a-real-user-password")


def hash_password(password: str) -> str:
    return _password_hash.hash(password)


def verify_password(password: str, encoded: str) -> bool:
    return _password_hash.verify(password, encoded)


def create_access_token(subject: str) -> tuple[str, int, str]:
    settings = get_settings()
    lifetime = settings.access_token_minutes * 60
    now = datetime.now(timezone.utc)
    token_id = uuid4().hex
    payload = {
        "sub": subject,
        "jti": token_id,
        "iat": now,
        "exp": now + timedelta(seconds=lifetime),
        "iss": "godhead-api",
        "aud": "godhead-android",
        "typ": "access",
    }
    token = jwt.encode(payload, settings.jwt_secret, algorithm="HS256")
    return token, lifetime, token_id


def decode_access_token_claims(token: str) -> dict:
    payload = jwt.decode(
        token,
        get_settings().jwt_secret,
        algorithms=["HS256"],
        audience="godhead-android",
        issuer="godhead-api",
        options={"require": ["sub", "jti", "exp", "iat", "iss", "aud"]},
    )
    if payload.get("typ") != "access":
        raise jwt.InvalidTokenError("Wrong token type")
    if not isinstance(payload.get("jti"), str) or not payload["jti"]:
        raise jwt.InvalidTokenError("Missing token identifier")
    return payload


def decode_access_token(token: str) -> str:
    """Compatibility helper for callers which only need the token subject."""
    return str(decode_access_token_claims(token)["sub"])
