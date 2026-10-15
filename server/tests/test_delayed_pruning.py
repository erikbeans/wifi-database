"""Persistent grace periods, boundary times, and concurrent cleanup."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from threading import Event

import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.access_points import deletion, pruning, service
from app.access_points.models import AccessPoint, ApPassword, PasswordVote, Report
from app.audit import AuditEvent
from app.auth.models import User
from conftest import auth_headers, engine
from test_pruning import ids, seed_votes, vote


def expire_grace(db, password_id):
    db.get(ApPassword, password_id).low_score_since = deletion.server_time(db) - pruning.DELETION_DELAY
    db.commit()


def sweep(db, batch_size=100):
    processed = pruning.cleanup_due(db, batch_size=batch_size)
    db.commit()
    db.expire_all()
    return processed


@pytest.mark.parametrize('down,up', [(0, 0), (0, 1), (0, 2), (9, 0), (12, 0), (10, 2)])
def test_threshold_starts_timer_without_immediate_deletion(client, db, registered_user, sample_ap, down, up):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='threshold@example.com')
    seed_votes(db, password_id, up=up, down=down)
    response = vote(client, voter, password_id)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body['score'] == up - down - 1
    assert body['password_deleted'] is False
    assert body['access_point_deleted'] is False
    db.expire_all()
    row = db.get(ApPassword, password_id)
    assert (row.low_score_since is not None) is (body['score'] <= -1)
    assert sweep(db) == 0
    assert db.get(AccessPoint, ap_id) is not None


def test_further_downvotes_and_repeated_votes_preserve_timer(client, db, registered_user, sample_ap):
    _, password_id = ids(sample_ap)
    first = registered_user(email='first@example.com')
    second = registered_user(email='second@example.com')
    vote(client, first, password_id)
    row = db.get(ApPassword, password_id)
    row.low_score_since -= timedelta(hours=12)
    original = row.low_score_since
    db.commit()
    assert vote(client, second, password_id).json()['score'] == -2
    assert vote(client, second, password_id).json()['score'] == -2
    db.expire_all()
    assert db.get(ApPassword, password_id).low_score_since == original


@pytest.mark.parametrize('recovery', ['upvote', 'retract', 'duplicate'])
def test_recovery_cancels_and_later_drop_restarts_timer(client, db, registered_user, sample_ap, recovery):
    ap_id, password_id = ids(sample_ap)
    first = registered_user(email='negative@example.com')
    second = registered_user(email='positive@example.com')
    vote(client, first, password_id)
    row = db.get(ApPassword, password_id)
    row.low_score_since -= timedelta(hours=23)
    original = row.low_score_since
    db.commit()
    if recovery == 'retract':
        assert client.delete(f'/api/v1/passwords/{password_id}/vote', headers=auth_headers(first)).status_code == 204
    elif recovery == 'duplicate':
        response = client.post(f'/api/v1/access-points/{ap_id}/passwords',
            json={'password': 'espresso42', 'consent_confirmed': True}, headers=auth_headers(second))
        assert response.status_code == 200
    else:
        assert vote(client, second, password_id, value=1).json()['score'] == 0
    db.expire_all()
    assert db.get(ApPassword, password_id).low_score_since is None
    third = registered_user(email='third@example.com')
    assert vote(client, third, password_id).json()['score'] == -1
    db.expire_all()
    assert db.get(ApPassword, password_id).low_score_since > original + timedelta(hours=22)
    assert sweep(db) == 0


@pytest.mark.parametrize('offset,deleted', [(timedelta(microseconds=-1), False), (timedelta(), True)])
def test_exact_24_hour_boundary(db, sample_ap, monkeypatch, offset, deleted):
    ap_id, password_id = ids(sample_ap)
    seed_votes(db, password_id, down=1)
    row = pruning.lock_candidate(db, password_id)
    started = datetime(2026, 10, 15, tzinfo=timezone.utc)
    row.low_score_since = started
    monkeypatch.setattr(deletion, 'server_time', lambda _: started + pruning.DELETION_DELAY + offset)
    result = pruning.prune(db, row)
    db.commit()
    assert result.password_deleted is deleted
    assert result.access_point_deleted is deleted
    assert (db.get(ApPassword, password_id) is None) is deleted
    assert (db.get(AccessPoint, ap_id) is None) is deleted


def test_idle_password_cleanup_survives_restart_without_more_votes(client, db, registered_user, sample_ap):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='idle@example.com')
    vote(client, voter, password_id)
    expire_grace(db, password_id)
    with Session(engine) as restarted:
        assert pruning.cleanup_due(restarted) == 1
        restarted.commit()
    db.expire_all()
    assert db.get(ApPassword, password_id) is None
    assert db.get(AccessPoint, ap_id) is None
    assert vote(client, voter, password_id).status_code == 404
    assert client.get(f'/api/v1/access-points/{ap_id}', headers=auth_headers(voter)).status_code == 404
    assert client.get('/api/v1/access-points/search?q=Cafe', headers=auth_headers(voter)).json() == []


@pytest.mark.parametrize('status', ['active', 'flagged'])
def test_siblings_survive_and_password_data_cascades(client, db, registered_user, sample_ap, status):
    ap_id, password_id = ids(sample_ap)
    sibling = ApPassword(access_point_id=ap_id, password_ciphertext=b'unused', status=status)
    db.add(sibling)
    voter = registered_user(email='cascade@example.com')
    voter_id = db.scalar(select(User.id).where(User.email == voter['email']))
    db.add(Report(reporter_id=voter_id, password_id=password_id, reason='wrong'))
    db.add(Report(reporter_id=voter_id, ap_id=ap_id, reason='other'))
    db.add(AuditEvent(user_id=voter_id, event_type='password_view', target_id=password_id))
    db.commit()
    vote(client, voter, password_id)
    expire_grace(db, password_id)
    assert sweep(db) == 1
    assert db.get(ApPassword, password_id) is None
    assert db.get(ApPassword, sibling.id) is not None
    assert db.get(AccessPoint, ap_id) is not None
    assert db.scalar(select(func.count()).select_from(PasswordVote)) == 0
    assert db.scalar(select(func.count()).select_from(Report).where(Report.password_id == password_id)) == 0
    assert db.scalar(select(func.count()).select_from(Report).where(Report.ap_id == ap_id)) == 1
    assert db.scalar(select(func.count()).select_from(AuditEvent).where(AuditEvent.target_id == password_id)) == 2


def test_last_password_removes_network_reports(client, db, registered_user, sample_ap):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='last@example.com')
    user_id = db.scalar(select(User.id).where(User.email == voter['email']))
    db.add(Report(reporter_id=user_id, ap_id=ap_id, reason='wrong'))
    db.commit()
    vote(client, voter, password_id)
    expire_grace(db, password_id)
    sweep(db)
    assert db.scalar(select(func.count()).select_from(Report)) == 0


@pytest.mark.parametrize('operation', ['switch', 'retract', 'account_delete'])
def test_removing_upvote_starts_timer(client, db, registered_user, sample_ap, operation):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='upvoter@example.com')
    user_id = db.scalar(select(User.id).where(User.email == voter['email']))
    seed_votes(db, password_id, down=1, voter_id=user_id, value=1)
    if operation == 'switch':
        result = vote(client, voter, password_id)
        assert result.json()['score'] == -2
        assert result.json()['password_deleted'] is False
    elif operation == 'retract':
        assert client.delete(f'/api/v1/passwords/{password_id}/vote', headers=auth_headers(voter)).status_code == 204
    else:
        assert client.delete('/api/v1/auth/me', headers=auth_headers(voter)).status_code == 204
    db.expire_all()
    assert db.get(ApPassword, password_id).low_score_since is not None
    assert sweep(db) == 0
    expire_grace(db, password_id)
    sweep(db)
    assert db.get(ApPassword, password_id) is None
    assert db.get(AccessPoint, ap_id) is None


def test_removing_downvoter_account_cancels_timer(client, db, registered_user, sample_ap):
    _, password_id = ids(sample_ap)
    voter = registered_user(email='downvoter@example.com')
    vote(client, voter, password_id)
    assert client.delete('/api/v1/auth/me', headers=auth_headers(voter)).status_code == 204
    db.expire_all()
    row = db.get(ApPassword, password_id)
    assert row.score == 0
    assert row.low_score_since is None


def test_old_flagged_anonymous_password_still_gets_full_grace(client, db, registered_user, sample_ap):
    _, password_id = ids(sample_ap)
    row = db.get(ApPassword, password_id)
    row.status = 'flagged'
    row.created_at = datetime.now(timezone.utc) - timedelta(days=30)
    row.submitted_by = None
    db.commit()
    voter = registered_user(email='flagged@example.com')
    assert vote(client, voter, password_id).json()['password_deleted'] is False
    assert sweep(db) == 0
    expire_grace(db, password_id)
    assert sweep(db) == 1
    assert db.get(ApPassword, password_id) is None


def test_vote_after_deadline_can_delete(client, db, registered_user, sample_ap):
    _, password_id = ids(sample_ap)
    voter = registered_user(email='negative@example.com')
    vote(client, voter, password_id)
    expire_grace(db, password_id)
    result = vote(client, voter, password_id)
    assert result.json()['password_deleted'] is True
    assert result.json()['access_point_deleted'] is True


def test_concurrent_workers_do_not_double_delete(db, sample_ap):
    ap_id, password_id = ids(sample_ap)
    seed_votes(db, password_id, down=1)
    expire_grace(db, password_id)
    def clean(_):
        with Session(engine) as session:
            processed = pruning.cleanup_due(session)
            session.commit()
            return processed
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sum(pool.map(clean, range(2))) == 1
    db.expire_all()
    assert db.get(AccessPoint, ap_id) is None


def test_concurrent_downvotes_share_one_grace_period(db, registered_user, sample_ap):
    _, password_id = ids(sample_ap)
    voters = [registered_user(email=f'concurrent-{number}@example.com') for number in range(2)]
    user_ids = list(db.scalars(select(User.id).where(User.email.in_([voter['email'] for voter in voters]))))
    def cast(user_id):
        with Session(engine) as session:
            row = pruning.lock_candidate(session, password_id)
            result = service.set_vote(session, user_id, password_id, -1)
            started = row.low_score_since
            session.commit()
            return result, started
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(cast, user_ids))
    assert sorted(result.score for result, _ in results) == [-2, -1]
    assert all(not result.password_deleted for result, _ in results)
    assert results[0][1] == results[1][1]


def test_locked_parent_is_skipped_and_recovery_is_respected(db, registered_user, sample_ap):
    _, password_id = ids(sample_ap)
    voter = registered_user(email='recover@example.com')
    user_id = db.scalar(select(User.id).where(User.email == voter['email']))
    seed_votes(db, password_id, down=1)
    expire_grace(db, password_id)
    with Session(engine) as recovering:
        assert pruning.lock_candidate(recovering, password_id) is not None
        with Session(engine) as worker:
            assert pruning.cleanup_due(worker) == 0
            worker.commit()
        assert service.set_vote(recovering, user_id, password_id, 1).score == 0
        recovering.commit()
    assert sweep(db) == 0
    assert db.get(ApPassword, password_id).low_score_since is None


@pytest.mark.parametrize('submission_first', [False, True])
def test_concurrent_submission_is_not_lost(db, registered_user, sample_ap, submission_first):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='race@example.com')
    user_id = db.scalar(select(User.id).where(User.email == voter['email']))
    seed_votes(db, password_id, down=1)
    expire_grace(db, password_id)
    locked, proceed = Event(), Event()
    def remove():
        with Session(engine) as session:
            row = pruning.lock_candidate(session, password_id)
            assert row is not None
            if not submission_first:
                locked.set()
                assert proceed.wait(5)
            result = pruning.prune(session, row)
            session.commit()
            return result
    def submit():
        with Session(engine) as session:
            ap = pruning.lock_access_point(session, ap_id)
            if submission_first:
                locked.set()
                assert proceed.wait(5)
            if ap is None:
                return None
            row, _ = service.add_candidate(session, ap, 'replacement-pass', user_id)
            candidate_id = row.id
            session.commit()
            return candidate_id
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(submit if submission_first else remove)
        assert locked.wait(5)
        second = pool.submit(remove if submission_first else submit)
        proceed.set()
        first_result, second_result = first.result(timeout=10), second.result(timeout=10)
    db.expire_all()
    if submission_first:
        assert not second_result.access_point_deleted
        assert db.get(ApPassword, first_result) is not None
    else:
        assert first_result.access_point_deleted
        assert second_result is None
        assert db.get(AccessPoint, ap_id) is None
