from datetime import datetime, timedelta, timezone

from conftest import auth_headers, extract_token


def test_register_sends_verification_email(client, sent_emails, db):
    response = client.post(
        "/api/v1/auth/register", json={"email": "new@example.com", "password": "password123"}
    )
    assert response.status_code == 201
    assert response.json() == {"message": "verification email sent"}
    assert len(sent_emails) == 1
    to, subject, body = sent_emails[0]
    assert to == "new@example.com"
    assert "verify" in subject.lower()
    assert "wifidatabase://verify?token=" in body


def test_register_with_existing_email_is_indistinguishable(client, registered_user, sent_emails):
    first = client.post(
        "/api/v1/auth/register", json={"email": "dup@example.com", "password": "password123"}
    )
    registered_user(email="dup@example.com")
    second = client.post(
        "/api/v1/auth/register", json={"email": "dup@example.com", "password": "different123"}
    )
    assert second.status_code == first.status_code == 201
    assert second.json() == first.json()
    assert len(sent_emails) >= 2


def test_register_short_password_rejected(client):
    response = client.post(
        "/api/v1/auth/register", json={"email": "short@example.com", "password": "short"}
    )
    assert response.status_code == 422


def test_verify_round_trip_and_login(client, registered_user, sent_emails):
    user = registered_user(email="roundtrip@example.com")
    response = client.post(
        "/api/v1/auth/login", json={"email": "roundtrip@example.com", "password": "password123"}
    )
    assert response.status_code == 200
    body = response.json()
    assert body["access_token"]
    assert body["refresh_token"]
    assert body["token_type"] == "bearer"


def test_verify_token_is_single_use(client, sent_emails):
    client.post("/api/v1/auth/register", json={"email": "once@example.com", "password": "password123"})
    token = extract_token(sent_emails[0][2])
    first = client.post("/api/v1/auth/verify", json={"token": token})
    assert first.status_code == 200
    second = client.post("/api/v1/auth/verify", json={"token": token})
    assert second.status_code == 400


def test_verify_bad_token(client):
    response = client.post("/api/v1/auth/verify", json={"token": "garbage"})
    assert response.status_code == 400


def test_login_uniform_401_for_wrong_email_and_wrong_password(client, registered_user):
    registered_user(email="known@example.com")
    wrong_email = client.post(
        "/api/v1/auth/login", json={"email": "unknown@example.com", "password": "password123"}
    )
    wrong_password = client.post(
        "/api/v1/auth/login", json={"email": "known@example.com", "password": "wrongpassword"}
    )
    assert wrong_email.status_code == wrong_password.status_code == 401
    assert wrong_email.json() == wrong_password.json() == {"detail": "invalid credentials"}


def test_login_lockout_after_five_failures(client, registered_user, db):
    from app.auth.models import User

    registered_user(email="locked@example.com")
    for _ in range(5):
        response = client.post(
            "/api/v1/auth/login", json={"email": "locked@example.com", "password": "wrongpassword"}
        )
        assert response.status_code == 401

    user = db.execute(User.__table__.select().where(User.email == "locked@example.com")).mappings().first()
    assert user["locked_until"] is not None

    correct = client.post(
        "/api/v1/auth/login", json={"email": "locked@example.com", "password": "password123"}
    )
    assert correct.status_code == 401
    assert correct.json() == {"detail": "invalid credentials"}

    db.query(User).filter(User.email == "locked@example.com").update(
        {"locked_until": datetime.now(timezone.utc) - timedelta(minutes=1)}
    )
    db.commit()
    after = client.post(
        "/api/v1/auth/login", json={"email": "locked@example.com", "password": "password123"}
    )
    assert after.status_code == 200


def test_refresh_rotation_and_family_revocation(client, registered_user):
    user = registered_user(email="rotate@example.com")
    first = client.post("/api/v1/auth/refresh", json={"refresh_token": user["refresh_token"]})
    assert first.status_code == 200
    second_token = first.json()["refresh_token"]

    replay = client.post("/api/v1/auth/refresh", json={"refresh_token": user["refresh_token"]})
    assert replay.status_code == 401

    family_dead = client.post("/api/v1/auth/refresh", json={"refresh_token": second_token})
    assert family_dead.status_code == 401


def test_logout_revokes_refresh_token(client, registered_user):
    user = registered_user(email="bye@example.com")
    response = client.post("/api/v1/auth/logout", json={"refresh_token": user["refresh_token"]})
    assert response.status_code == 204
    refresh = client.post("/api/v1/auth/refresh", json={"refresh_token": user["refresh_token"]})
    assert refresh.status_code == 401


def test_password_reset_flow(client, registered_user, sent_emails, db):
    user = registered_user(email="resetflow@example.com")
    request = client.post(
        "/api/v1/auth/password-reset-request", json={"email": "resetflow@example.com"}
    )
    assert request.status_code == 202
    token = extract_token(sent_emails[-1][2])

    old_refresh = user["refresh_token"]
    confirm = client.post(
        "/api/v1/auth/password-reset", json={"token": token, "new_password": "newpassword456"}
    )
    assert confirm.status_code == 204

    stale = client.post("/api/v1/auth/login", json={"email": "resetflow@example.com", "password": "password123"})
    assert stale.status_code == 401
    fresh = client.post("/api/v1/auth/login", json={"email": "resetflow@example.com", "password": "newpassword456"})
    assert fresh.status_code == 200
    revoked = client.post("/api/v1/auth/refresh", json={"refresh_token": old_refresh})
    assert revoked.status_code == 401


def test_password_reset_bad_token(client):
    response = client.post(
        "/api/v1/auth/password-reset", json={"token": "nope", "new_password": "newpassword456"}
    )
    assert response.status_code == 400


def test_delete_account_anonymizes_contributions(client, registered_user, db, sample_ap):
    from app.access_points.models import AccessPoint, ApPassword, PasswordVote

    owner = sample_ap["user"]
    other = registered_user(email="other@example.com")
    password_id = sample_ap["ap"]["passwords"][0]["id"]
    vote = client.post(
        f"/api/v1/passwords/{password_id}/vote",
        json={"value": 1},
        headers=auth_headers(other),
    )
    assert vote.status_code == 200

    response = client.delete("/api/v1/auth/me", headers=auth_headers(owner))
    assert response.status_code == 204

    ap = db.get(AccessPoint, sample_ap["ap"]["id"])
    assert ap is not None
    assert ap.submitted_by is None
    candidates = db.query(ApPassword).filter(ApPassword.access_point_id == ap.id).all()
    assert len(candidates) == 1
    assert candidates[0].submitted_by is None
    assert db.query(PasswordVote).count() == 1

    login = client.post("/api/v1/auth/login", json={"email": owner["email"], "password": owner["password"]})
    assert login.status_code == 401


def test_unverified_account_can_browse_but_not_act(client, registered_user, sample_ap):
    unverified = registered_user(email="unverified@example.com", verify=False)

    near = client.get(
        "/api/v1/access-points/near", params={"lat": 47.6062, "lon": -122.3321},
        headers=auth_headers(unverified),
    )
    assert near.status_code == 200

    detail = client.get(
        f"/api/v1/access-points/{sample_ap['ap']['id']}", headers=auth_headers(unverified)
    )
    assert detail.status_code == 403
    assert detail.json()["detail"] == "email_unverified"

    create = client.post(
        "/api/v1/access-points",
        json={
            "ssid": "Any", "password": "whatever", "latitude": 0, "longitude": 0,
            "consent_confirmed": True,
        },
        headers=auth_headers(unverified),
    )
    assert create.status_code == 403

    password_id = sample_ap["ap"]["passwords"][0]["id"]
    vote = client.post(
        f"/api/v1/passwords/{password_id}/vote", json={"value": 1}, headers=auth_headers(unverified)
    )
    assert vote.status_code == 403
    report = client.post(
        "/api/v1/reports",
        json={"ap_id": sample_ap["ap"]["id"], "reason": "wrong"},
        headers=auth_headers(unverified),
    )
    assert report.status_code == 403


def test_resend_verification(client, sent_emails):
    client.post("/api/v1/auth/register", json={"email": "resend@example.com", "password": "password123"})
    response = client.post("/api/v1/auth/resend-verification", json={"email": "resend@example.com"})
    assert response.status_code == 202
    assert len(sent_emails) == 2
    token = extract_token(sent_emails[-1][2])
    verify = client.post("/api/v1/auth/verify", json={"token": token})
    assert verify.status_code == 200


def test_auth_email_quota(client, sent_emails):
    client.post("/api/v1/auth/register", json={"email": "quota@example.com", "password": "password123"})
    for _ in range(15):
        response = client.post("/api/v1/auth/resend-verification", json={"email": "quota@example.com"})
        assert response.status_code == 202
    assert len(sent_emails) == 10
