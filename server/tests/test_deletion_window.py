import inspect
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from queue import Queue
from threading import Event

import pytest
from fastapi import HTTPException
from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from app.access_points import deletion, router
from app.access_points.models import AccessPoint, ApPassword
from app.access_points.schemas import AccessPointCreate, PasswordCandidateCreate
from app.auth.models import User
from conftest import auth_headers
from test_access_points import _create

NOW = datetime(2026, 10, 15, tzinfo=timezone.utc)


@pytest.fixture
def policy_clock(monkeypatch):
    monkeypatch.setattr(deletion, "server_time", lambda db: NOW)


def _set_age(db, ap, age, password_age=None):
    row = db.get(AccessPoint, uuid.UUID(ap["id"]))
    row.created_at = NOW - timedelta(seconds=age)
    for password in db.scalars(select(ApPassword).where(ApPassword.access_point_id == row.id)):
        password.created_at = NOW - timedelta(seconds=age if password_age is None else password_age)
    db.commit()


@pytest.mark.parametrize("target", ["passwords", "access-points"])
@pytest.mark.parametrize("age, expected", [(899.999, 204), (900, 403), (901, 403), (86400, 403)])
def test_deletion_boundary(client, registered_user, db, policy_clock, target, age, expected):
    user = registered_user()
    ap = _create(client, user).json()
    _set_age(db, ap, age)
    target_id = ap["id"] if target == "access-points" else ap["passwords"][0]["id"]
    response = client.delete(f"/api/v1/{target}/{target_id}", headers=auth_headers(user))
    assert response.status_code == expected
    if expected == 403:
        assert response.json()["detail"] == deletion.WINDOW_ENDED
        assert db.scalar(select(func.count()).select_from(ApPassword)) == 1
        assert db.get(AccessPoint, uuid.UUID(ap["id"])) is not None


@pytest.mark.parametrize("target", ["passwords", "access-points"])
def test_non_owner_cannot_delete(client, registered_user, sample_ap, target):
    stranger = registered_user(email="stranger@example.com")
    ap = sample_ap["ap"]
    target_id = ap["id"] if target == "access-points" else ap["passwords"][0]["id"]
    assert client.delete(f"/api/v1/{target}/{target_id}", headers=auth_headers(stranger)).status_code == 403


def test_permissions_and_duplicate_dont_reset_deadline(client, registered_user, db, policy_clock):
    user = registered_user()
    ap = _create(client, user, bssid="aa:bb:cc:11:22:33").json()
    _set_age(db, ap, 901)
    original = db.get(ApPassword, uuid.UUID(ap["passwords"][0]["id"])).created_at
    for response in [
        _create(client, user, bssid="aa:bb:cc:11:22:33"),
        client.post(f"/api/v1/access-points/{ap['id']}/passwords",
                    json={"password": "espresso42", "consent_confirmed": True}, headers=auth_headers(user)),
        client.get(f"/api/v1/access-points/{ap['id']}", headers=auth_headers(user)),
    ]:
        body = response.json()
        assert body["server_time"] == NOW.isoformat().replace("+00:00", "Z")
        assert body["mine"] and not body["can_delete"]
        candidate = body["passwords"][0]
        assert candidate["mine"] and not candidate["can_delete"]
        assert datetime.fromisoformat(candidate["delete_until"]) == original + timedelta(minutes=15)
    assert db.get(ApPassword, uuid.UUID(ap["passwords"][0]["id"])).created_at == original
    stranger = registered_user(email="viewer@example.com")
    detail = client.get(f"/api/v1/access-points/{ap['id']}", headers=auth_headers(stranger)).json()
    assert not detail["can_delete"] and detail["delete_until"] is None
    assert not detail["passwords"][0]["can_delete"] and detail["passwords"][0]["delete_until"] is None


def test_new_password_on_old_access_point_has_own_window(client, registered_user, db, policy_clock):
    user = registered_user()
    ap = _create(client, user).json()
    _set_age(db, ap, 86400, password_age=100)
    detail = client.get(f"/api/v1/access-points/{ap['id']}", headers=auth_headers(user)).json()
    assert not detail["can_delete"]
    assert detail["passwords"][0]["can_delete"]
    assert client.delete(f"/api/v1/passwords/{ap['passwords'][0]['id']}", headers=auth_headers(user)).status_code == 204


@pytest.mark.parametrize("kind", ["foreign", "flagged_foreign", "anonymized", "expired", "flagged_expired"])
def test_access_point_cannot_cascade_protected_candidates(client, registered_user, db, policy_clock, kind):
    owner = registered_user()
    other = registered_user(email="other@example.com")
    ap = _create(client, owner).json()
    foreign = kind in ("foreign", "flagged_foreign")
    added = client.post(f"/api/v1/access-points/{ap['id']}/passwords",
                        json={"password": "another-password", "consent_confirmed": True},
                        headers=auth_headers(other if foreign else owner)).json()
    _set_age(db, ap, 60)
    candidate_id = next(p["id"] for p in added["passwords"] if p["password"] == "another-password")
    row = db.get(ApPassword, uuid.UUID(candidate_id))
    if kind.startswith("flagged"):
        row.status = "flagged"
    if kind == "anonymized":
        row.submitted_by = None
    if "expired" in kind:
        row.created_at = NOW - timedelta(minutes=15)
    db.commit()
    detail = client.get(f"/api/v1/access-points/{ap['id']}", headers=auth_headers(owner)).json()
    assert detail["mine"] and not detail["can_delete"]
    response = client.delete(f"/api/v1/access-points/{ap['id']}", headers=auth_headers(owner))
    assert response.status_code == 403
    assert db.scalar(select(func.count()).select_from(ApPassword)) == 2


def test_empty_access_point_uses_original_window(client, registered_user, db, policy_clock):
    user = registered_user()
    for age, expected in [(60, 204), (900, 403)]:
        ap = _create(client, user).json()
        _set_age(db, ap, age)
        db.delete(db.get(ApPassword, uuid.UUID(ap["passwords"][0]["id"])))
        db.commit()
        assert client.delete(f"/api/v1/access-points/{ap['id']}", headers=auth_headers(user)).status_code == expected


def test_access_point_with_multiple_owned_candidates_can_be_deleted(client, registered_user, db, policy_clock):
    user = registered_user()
    ap = _create(client, user).json()
    client.post(f"/api/v1/access-points/{ap['id']}/passwords",
                json={"password": "another-password", "consent_confirmed": True}, headers=auth_headers(user))
    _set_age(db, ap, 60)
    detail = client.get(f"/api/v1/access-points/{ap['id']}", headers=auth_headers(user)).json()
    assert detail["can_delete"]
    assert all(candidate["can_delete"] for candidate in detail["passwords"])
    assert client.delete(f"/api/v1/access-points/{ap['id']}", headers=auth_headers(user)).status_code == 204
    assert db.scalar(select(func.count()).select_from(ApPassword)) == 0


@pytest.mark.parametrize("target", ["passwords", "access-points"])
def test_expiry_is_checked_after_parent_lock(client, registered_user, db, monkeypatch, target):
    user = registered_user()
    ap = _create(client, user).json()
    _set_age(db, ap, 900)
    clock = [NOW - timedelta(seconds=1)]
    monkeypatch.setattr(deletion, "server_time", lambda session: clock[0])
    original_lock = deletion.lock_access_point

    def lock_after_deadline(session, ap_id):
        row = original_lock(session, ap_id)
        clock[0] = NOW
        return row

    monkeypatch.setattr(deletion, "lock_access_point", lock_after_deadline)
    target_id = ap["id"] if target == "access-points" else ap["passwords"][0]["id"]
    response = client.delete(f"/api/v1/{target}/{target_id}", headers=auth_headers(user))
    assert response.status_code == 403
    assert response.json()["detail"] == deletion.WINDOW_ENDED
    assert db.scalar(select(func.count()).select_from(ApPassword)) == 1


@pytest.mark.parametrize("submission_path", ["passwords", "bssid"])
def test_concurrent_submission_blocks_cascade(client, registered_user, db, monkeypatch, submission_path):
    owner = registered_user()
    other = registered_user(email="other@example.com")
    ap = _create(client, owner, bssid="aa:bb:cc:11:22:33").json()
    ap_id = uuid.UUID(ap["id"])
    owner_id = db.get(AccessPoint, ap_id).submitted_by
    other_id = db.scalar(select(User.id).where(User.email == other["email"]))
    engine = db.get_bind()
    locked, release = Event(), Event()
    delete_pid = Queue()
    original_lock = deletion.lock_access_point

    def controlled_lock(session, target_id):
        row = original_lock(session, target_id)
        if session.info.get("submission"):
            locked.set()
            assert release.wait(10), "submission was not released"
        return row

    monkeypatch.setattr(deletion, "lock_access_point", controlled_lock)

    def submit():
        with Session(engine, expire_on_commit=False) as session:
            session.info["submission"] = True
            user = session.get(User, other_id)
            if submission_path == "passwords":
                return inspect.unwrap(router.add_password_candidate)(
                    request=None, ap_id=ap_id, user=user, db=session,
                    payload=PasswordCandidateCreate(password="another-password", consent_confirmed=True),
                ).status_code
            return inspect.unwrap(router.create_access_point)(
                request=None, user=user, db=session,
                payload=AccessPointCreate(ssid="Cafe", bssid="aa:bb:cc:11:22:33", password="another-password",
                                          latitude=47.6, longitude=-122.3, consent_confirmed=True),
            ).status_code

    def delete():
        with Session(engine) as session:
            user = session.get(User, owner_id)
            delete_pid.put(session.scalar(select(func.pg_backend_pid())))
            try:
                return router.delete_access_point(ap_id=ap_id, user=user, db=session).status_code
            except HTTPException as exc:
                return exc.status_code

    with ThreadPoolExecutor(max_workers=2) as pool:
        addition = pool.submit(submit)
        try:
            assert locked.wait(5), "submission did not lock its parent access point"
            removal = pool.submit(delete)
            pid = delete_pid.get(timeout=5)
            timeout = time.monotonic() + 5
            while time.monotonic() < timeout:
                with engine.connect() as conn:
                    waiting = conn.scalar(text("SELECT wait_event_type = 'Lock' FROM pg_stat_activity WHERE pid = :pid"), {"pid": pid})
                if waiting:
                    break
                time.sleep(0.01)
            assert waiting, "deletion did not wait for the submission's parent lock"
        finally:
            release.set()
        assert addition.result(timeout=5) in (200, 201)
        assert removal.result(timeout=5) == 403
    db.expire_all()
    assert db.get(AccessPoint, ap_id) is not None
    assert db.scalar(select(func.count()).select_from(ApPassword).where(ApPassword.access_point_id == ap_id)) == 2
