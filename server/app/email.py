from datetime import datetime, timedelta, timezone

import smtplib
from email.message import EmailMessage

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.auth.models import AuthToken
from app.config import get_settings


def send_email(to: str, subject: str, body: str) -> None:
    settings = get_settings()
    message = EmailMessage()
    message["From"] = settings.smtp_from
    message["To"] = to
    message["Subject"] = subject
    message.set_content(body)
    with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=15) as smtp:
        if settings.smtp_user:
            smtp.starttls()
            smtp.login(settings.smtp_user, settings.smtp_password)
        smtp.send_message(message)


def send_verification_email(to: str, token: str) -> None:
    settings = get_settings()
    link = settings.verify_url.format(token=token)
    send_email(
        to,
        "Verify your WiFi Database account",
        "Welcome to WiFi Database!\n\n"
        "Confirm your email address by opening this link in the app:\n\n"
        f"{link}\n\n"
        "The link expires in 24 hours and can only be used once.\n\n"
        "If you didn't create this account, you can safely ignore this email.",
    )


def send_registration_notice(to: str) -> None:
    send_email(
        to,
        "New registration attempt for your address",
        "Someone tried to register a WiFi Database account with this email address.\n\n"
        "If this was you, you can log in or request a password reset. "
        "If not, you can safely ignore this email.",
    )


def send_password_reset_email(to: str, token: str) -> None:
    settings = get_settings()
    link = settings.reset_url.format(token=token)
    send_email(
        to,
        "Reset your WiFi Database password",
        "A password reset was requested for your WiFi Database account.\n\n"
        f"Open this link in the app to choose a new password:\n\n{link}\n\n"
        "The link expires in 24 hours and can only be used once.\n\n"
        "If you didn't request this, you can safely ignore this email.",
    )


def auth_email_quota_remaining(db: Session, user_id) -> bool:
    settings = get_settings()
    since = datetime.now(timezone.utc) - timedelta(hours=24)
    sent = db.execute(
        select(func.count())
        .select_from(AuthToken)
        .where(
            AuthToken.user_id == user_id,
            AuthToken.kind.in_(["verify", "reset"]),
            AuthToken.created_at > since,
        )
    ).scalar_one()
    return sent < settings.auth_email_quota_per_day
