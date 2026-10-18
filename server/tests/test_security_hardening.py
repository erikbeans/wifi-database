import base64
from datetime import timedelta

from fastapi.testclient import TestClient

from app.config import Settings


def test_consume_single_use_token_second_claim_fails(db):
    import uuid

    from app import security
    from app.auth.models import User

    user = User(
        id=uuid.uuid4(),
        email="consume-race@example.com",
        password_hash=security.hash_password("password123"),
        email_verified_at=None,
    )
    db.add(user)
    db.flush()

    raw = security.generate_token()
    security.store_token(db, user.id, "verify", raw, timedelta(hours=1))
    db.flush()

    first = security.consume_single_use_token(db, raw, "verify")
    assert first is not None
    second = security.consume_single_use_token(db, raw, "verify")
    assert second is None
    db.rollback()


def test_crash_trace_sanitizer_blocks_log_forging():
    from app.main import sanitize_crash_trace

    sanitized = sanitize_crash_trace(
        "java.lang.RuntimeException: boom\nFORGED: injected line\n\tat Fake(Fake.java:1)\r\n"
    )
    assert "FORGED" in sanitized
    assert "\n" not in sanitized
    assert "\r" not in sanitized
    assert sanitized == (
        "java.lang.RuntimeException: boom | FORGED: injected line | \tat Fake(Fake.java:1) | "
    )


def test_secure_secret_validation_rejects_dev_defaults():
    strong_key = base64.b64encode(b"0123456789abcdef0123456789abcdef").decode()

    try:
        Settings(
            require_secure_secrets=True,
            jwt_secret="dev-only-insecure-secret",
            encryption_key=strong_key,
        ).validate_production_secrets()
        raise AssertionError("expected RuntimeError")
    except RuntimeError as exc:
        assert "JWT_SECRET" in str(exc)

    try:
        Settings(
            require_secure_secrets=True,
            jwt_secret="strong-secret",
            encryption_key="",
        ).validate_production_secrets()
        raise AssertionError("expected RuntimeError")
    except RuntimeError as exc:
        assert "ENCRYPTION_KEY" in str(exc)


def test_secure_secret_validation_is_opt_in():
    Settings(
        require_secure_secrets=False,
        jwt_secret="dev-only-insecure-secret",
    ).validate_production_secrets()
