import hashlib
import secrets
import uuid
from datetime import datetime, timedelta, timezone

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import Argon2Error
from fastapi import Depends, HTTPException, Request
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.auth.models import AuthToken, User
from app.config import get_settings
from app.database import get_db

pwd_hasher = PasswordHasher()

INVALID_CREDENTIALS = "invalid credentials"
INVALID_TOKEN = "invalid or expired token"


def hash_password(password: str) -> str:
    return pwd_hasher.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return pwd_hasher.verify(password_hash, password)
    except (Argon2Error, ValueError):
        return False


def create_access_token(user_id: uuid.UUID) -> str:
    settings = get_settings()
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user_id),
        "type": "access",
        "iat": now,
        "exp": now + timedelta(minutes=settings.access_token_minutes),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _token_hash(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def generate_token() -> str:
    return secrets.token_urlsafe(32)


def store_token(
    db: Session,
    user_id: uuid.UUID,
    kind: str,
    raw: str,
    lifetime: timedelta,
    family_id: uuid.UUID | None = None,
) -> AuthToken:
    row = AuthToken(
        user_id=user_id,
        kind=kind,
        family_id=family_id or uuid.uuid4(),
        token_hash=_token_hash(raw),
        expires_at=_now() + lifetime,
    )
    db.add(row)
    return row


def lookup_token(db: Session, raw: str, kind: str) -> AuthToken | None:
    return db.execute(
        select(AuthToken).where(AuthToken.token_hash == _token_hash(raw), AuthToken.kind == kind)
    ).scalar_one_or_none()


def consume_single_use_token(db: Session, raw: str, kind: str) -> AuthToken | None:
    row = lookup_token(db, raw, kind)
    if row is None or row.used_at is not None or row.expires_at <= _now():
        return None
    row.used_at = _now()
    return row


def rotate_refresh_token(db: Session, raw: str) -> AuthToken | str:
    row = lookup_token(db, raw, "refresh")
    if row is None:
        return "invalid"
    if row.used_at is not None:
        db.execute(delete(AuthToken).where(AuthToken.kind == "refresh", AuthToken.family_id == row.family_id))
        return "reuse"
    if row.expires_at <= _now():
        db.delete(row)
        return "invalid"
    row.used_at = _now()
    return row


def revoke_refresh_token(db: Session, raw: str) -> None:
    db.execute(
        delete(AuthToken).where(AuthToken.token_hash == _token_hash(raw), AuthToken.kind == "refresh")
    )


def revoke_all_refresh_tokens(db: Session, user_id: uuid.UUID) -> None:
    db.execute(delete(AuthToken).where(AuthToken.user_id == user_id, AuthToken.kind == "refresh"))


def issue_token_pair(db: Session, user: User, family_id: uuid.UUID | None = None) -> dict[str, str]:
    settings = get_settings()
    refresh_raw = generate_token()
    store_token(
        db, user.id, "refresh", refresh_raw, timedelta(days=settings.refresh_token_days), family_id
    )
    return {"access_token": create_access_token(user.id), "refresh_token": refresh_raw, "token_type": "bearer"}


def is_locked(user: User) -> bool:
    return user.locked_until is not None and user.locked_until > _now()


def register_failed_login(user: User) -> None:
    settings = get_settings()
    user.failed_login_count += 1
    if user.failed_login_count >= settings.lockout_threshold:
        exponent = user.failed_login_count - settings.lockout_threshold
        minutes = min(settings.lockout_base_minutes * (2**exponent), settings.lockout_max_minutes)
        user.locked_until = _now() + timedelta(minutes=minutes)


def get_current_user(request: Request, db: Session = Depends(get_db)) -> User:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        raise HTTPException(status_code=401, detail=INVALID_TOKEN)
    raw = header[len("Bearer "):].strip()
    settings = get_settings()
    try:
        payload = jwt.decode(raw, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
    except jwt.PyJWTError:
        raise HTTPException(status_code=401, detail=INVALID_TOKEN)
    if payload.get("type") != "access":
        raise HTTPException(status_code=401, detail=INVALID_TOKEN)
    try:
        user_id = uuid.UUID(str(payload.get("sub", "")))
    except ValueError:
        raise HTTPException(status_code=401, detail=INVALID_TOKEN)
    # Acquire the voter lock before any access-point lock. Account deletion
    # takes an exclusive lock first, so no new votes can escape its snapshot.
    deleting_account = request.method == "DELETE" and request.url.path == "/api/v1/auth/me"
    user = db.scalar(select(User).where(User.id == user_id).with_for_update(
        read=not deleting_account, key_share=not deleting_account,
    ))
    if user is None:
        raise HTTPException(status_code=401, detail=INVALID_TOKEN)
    request.state.user_id = user.id
    return user


def get_verified_user(user: User = Depends(get_current_user)) -> User:
    if user.email_verified_at is None:
        raise HTTPException(status_code=403, detail="email_unverified")
    return user
