from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import email, security
from app.access_points import pruning, service
from app.access_points.models import AccessPoint, ApPassword, PasswordVote, Report
from app.auth.models import User
from app.auth.schemas import (
    LoginRequest,
    LogoutRequest,
    MessageResponse,
    PasswordResetConfirm,
    PasswordResetRequest,
    RefreshRequest,
    RegisterRequest,
    ResendVerificationRequest,
    TokenPair,
    VerifyRequest,
)
from app.config import get_settings
from app.database import get_db
from app.ratelimits import limiter

router = APIRouter()

VERIFY_TOKEN_TTL = timedelta(hours=24)
RESET_TOKEN_TTL = timedelta(hours=24)

settings = get_settings()
_email_ip_limit = f"{settings.register_ip_limit_per_day}/day"


def _find_user(db: Session, email_addr: str) -> User | None:
    return db.execute(select(User).where(User.email == email_addr)).scalar_one_or_none()


@router.post("/register", status_code=201, response_model=MessageResponse)
@limiter.limit(_email_ip_limit)
def register(request: Request, payload: RegisterRequest, db: Session = Depends(get_db)) -> MessageResponse:
    email_addr = payload.email.lower()
    user = _find_user(db, email_addr)
    if user is None:
        user = User(email=email_addr, password_hash=security.hash_password(payload.password))
        db.add(user)
        db.flush()

    pending: tuple[str, str] | None = None
    if email.auth_email_quota_remaining(db, user.id):
        if user.email_verified_at is None:
            raw = security.generate_token()
            security.store_token(db, user.id, "verify", raw, VERIFY_TOKEN_TTL)
            pending = ("verify", raw)
        else:
            pending = ("notice", "")

    db.commit()
    if pending is not None:
        if pending[0] == "verify":
            email.send_verification_email(email_addr, pending[1])
        else:
            email.send_registration_notice(email_addr)
    return MessageResponse(message="verification email sent")


@router.post("/verify", response_model=TokenPair)
def verify_email(payload: VerifyRequest, db: Session = Depends(get_db)) -> TokenPair:
    row = security.consume_single_use_token(db, payload.token, "verify")
    if row is None:
        raise HTTPException(status_code=400, detail="invalid or expired token")
    user = db.get(User, row.user_id)
    if user is None:
        raise HTTPException(status_code=400, detail="invalid or expired token")
    if user.email_verified_at is None:
        user.email_verified_at = datetime.now(timezone.utc)
    pair = security.issue_token_pair(db, user)
    db.commit()
    return TokenPair(**pair)


@router.post("/resend-verification", status_code=202, response_model=MessageResponse)
@limiter.limit(_email_ip_limit)
def resend_verification(
    request: Request, payload: ResendVerificationRequest, db: Session = Depends(get_db)
) -> MessageResponse:
    user = _find_user(db, payload.email.lower())
    pending: str | None = None
    if user is not None and user.email_verified_at is None and email.auth_email_quota_remaining(db, user.id):
        pending = security.generate_token()
        security.store_token(db, user.id, "verify", pending, VERIFY_TOKEN_TTL)
    db.commit()
    if pending is not None:
        email.send_verification_email(user.email, pending)
    return MessageResponse(message="if the address is registered and unverified, a verification email has been sent")


@router.post("/login", response_model=TokenPair)
def login(payload: LoginRequest, db: Session = Depends(get_db)) -> TokenPair:
    user = _find_user(db, payload.email.lower())
    if user is None:
        raise HTTPException(status_code=401, detail=security.INVALID_CREDENTIALS)
    if security.is_locked(user):
        raise HTTPException(status_code=401, detail=security.INVALID_CREDENTIALS)
    if not security.verify_password(payload.password, user.password_hash):
        security.register_failed_login(user)
        db.commit()
        raise HTTPException(status_code=401, detail=security.INVALID_CREDENTIALS)
    user.failed_login_count = 0
    user.locked_until = None
    pair = security.issue_token_pair(db, user)
    db.commit()
    return TokenPair(**pair)


@router.post("/refresh", response_model=TokenPair)
def refresh(payload: RefreshRequest, db: Session = Depends(get_db)) -> TokenPair:
    result = security.rotate_refresh_token(db, payload.refresh_token)
    if result in {"invalid", "reuse"}:
        db.commit()
        raise HTTPException(status_code=401, detail=security.INVALID_TOKEN)
    user = db.get(User, result.user_id)
    if user is None:
        db.commit()
        raise HTTPException(status_code=401, detail=security.INVALID_TOKEN)
    pair = security.issue_token_pair(db, user, family_id=result.family_id)
    db.commit()
    return TokenPair(**pair)


@router.post("/logout", status_code=204)
def logout(payload: LogoutRequest, db: Session = Depends(get_db)) -> Response:
    security.revoke_refresh_token(db, payload.refresh_token)
    db.commit()
    return Response(status_code=204)


@router.post("/password-reset-request", status_code=202, response_model=MessageResponse)
@limiter.limit(_email_ip_limit)
def password_reset_request(
    request: Request, payload: PasswordResetRequest, db: Session = Depends(get_db)
) -> MessageResponse:
    user = _find_user(db, payload.email.lower())
    pending: str | None = None
    if user is not None and email.auth_email_quota_remaining(db, user.id):
        pending = security.generate_token()
        security.store_token(db, user.id, "reset", pending, RESET_TOKEN_TTL)
    db.commit()
    if pending is not None:
        email.send_password_reset_email(user.email, pending)
    return MessageResponse(message="if the address is registered, a password reset email has been sent")


@router.post("/password-reset", status_code=204)
def password_reset(payload: PasswordResetConfirm, db: Session = Depends(get_db)) -> Response:
    row = security.consume_single_use_token(db, payload.token, "reset")
    if row is None:
        raise HTTPException(status_code=400, detail="invalid or expired token")
    user = db.get(User, row.user_id)
    if user is None:
        raise HTTPException(status_code=400, detail="invalid or expired token")
    user.password_hash = security.hash_password(payload.new_password)
    security.revoke_all_refresh_tokens(db, user.id)
    db.commit()
    return Response(status_code=204)


@router.delete("/me", status_code=204)
def delete_me(
    user: User = Depends(security.get_current_user), db: Session = Depends(get_db)
) -> Response:
    # Block new requests by this voter while collecting the affected candidates.
    db.refresh(user, with_for_update=True)
    affected = db.execute(
        select(ApPassword.id, ApPassword.access_point_id)
        .join(PasswordVote, PasswordVote.password_id == ApPassword.id)
        .where(PasswordVote.user_id == user.id)
    ).all()
    # SET NULL on contributions/reports also writes rows protected by parent locks.
    associated = set(db.scalars(select(AccessPoint.id).where(AccessPoint.submitted_by == user.id)))
    associated.update(db.scalars(select(ApPassword.access_point_id).where(ApPassword.submitted_by == user.id)))
    associated.update(db.scalars(select(Report.ap_id).where(Report.reporter_id == user.id, Report.ap_id.is_not(None))))
    associated.update(db.scalars(select(ApPassword.access_point_id).join(
        Report, Report.password_id == ApPassword.id).where(Report.reporter_id == user.id)))
    for ap_id in sorted(associated | {ap_id for _, ap_id in affected}):
        pruning.lock_access_point(db, ap_id)
    db.delete(user)
    db.flush()  # Foreign-key cascades remove this account's votes.
    for password_id, _ in affected:
        if db.get(ApPassword, password_id, populate_existing=True) is not None:
            pruning.prune(db, service.recompute_vote_counts(db, password_id))
    db.commit()
    return Response(status_code=204)
