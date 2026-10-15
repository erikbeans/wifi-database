"""Integration coverage of atomic score cleanup against PostgreSQL/PostGIS."""
import importlib.util
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Event

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.access_points import pruning, service
from app.access_points.models import AccessPoint, ApPassword, PasswordVote, Report
from app.audit import AuditEvent
from app.auth.models import User
from conftest import auth_headers, engine


def ids(sample_ap):
    return uuid.UUID(sample_ap['ap']['id']), uuid.UUID(sample_ap['ap']['passwords'][0]['id'])


def seed_votes(db, password_id, up=0, down=9, voter_id=None, value=None):
    for vote_value, count in [(1, up), (-1, down)]:
        for _ in range(count):
            user = User(id=uuid.uuid4(), email=f'{uuid.uuid4()}@example.com', password_hash='unused')
            db.add(user)
            db.flush()
            db.add(PasswordVote(user_id=user.id, password_id=password_id, value=vote_value))
    if voter_id:
        db.add(PasswordVote(user_id=voter_id, password_id=password_id, value=value))
    db.flush()
    service.recompute_vote_counts(db, password_id)
    db.commit()


def vote(client, voter, password_id, value=-1):
    return client.post(f'/api/v1/passwords/{password_id}/vote', json={'value': value}, headers=auth_headers(voter))


@pytest.mark.parametrize('down,up,deleted', [(8, 0, False), (9, 0, True), (10, 2, False), (12, 0, True)])
def test_threshold_and_net_score(client, db, registered_user, sample_ap, down, up, deleted):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='threshold@example.com')
    seed_votes(db, password_id, up=up, down=down)
    response = vote(client, voter, password_id)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body['score'] == up - down - 1
    assert body['down_votes'] == down + 1
    assert body['password_deleted'] is deleted
    assert body['access_point_deleted'] is deleted
    db.expire_all()
    assert (db.get(ApPassword, password_id) is None) is deleted
    assert (db.get(AccessPoint, ap_id) is None) is deleted
    if deleted:
        assert vote(client, voter, password_id).status_code == 404
        assert client.get(f'/api/v1/access-points/{ap_id}', headers=auth_headers(voter)).status_code == 404
        assert client.get('/api/v1/access-points/search?q=Cafe', headers=auth_headers(voter)).json() == []


@pytest.mark.parametrize('status', ['active', 'flagged'])
def test_siblings_cascades_and_audit(client, db, registered_user, sample_ap, status):
    ap_id, password_id = ids(sample_ap)
    sibling = ApPassword(access_point_id=ap_id, password_ciphertext=b'unused', submitted_by=None, status=status)
    db.add(sibling)
    voter = registered_user(email='cascade@example.com')
    voter_id = db.scalar(select(User.id).where(User.email == voter['email']))
    db.add(Report(reporter_id=voter_id, password_id=password_id, reason='wrong'))
    db.add(Report(reporter_id=voter_id, ap_id=ap_id, reason='other'))
    db.add(AuditEvent(user_id=voter_id, event_type='password_view', target_id=password_id))
    seed_votes(db, password_id)
    body = vote(client, voter, password_id).json()
    assert body['password_deleted'] is True
    assert body['access_point_deleted'] is False
    db.expire_all()
    assert db.get(ApPassword, password_id) is None
    assert db.get(ApPassword, sibling.id) is not None
    assert db.get(AccessPoint, ap_id) is not None
    assert db.scalar(select(func.count()).select_from(PasswordVote)) == 0
    assert db.scalar(select(func.count()).select_from(Report).where(Report.password_id == password_id)) == 0
    assert db.scalar(select(func.count()).select_from(Report).where(Report.ap_id == ap_id)) == 1
    assert db.scalar(select(func.count()).select_from(AuditEvent).where(AuditEvent.target_id == password_id)) == 2


def test_last_candidate_removes_network_reports(client, db, registered_user, sample_ap):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='last@example.com')
    user_id = db.scalar(select(User.id).where(User.email == voter['email']))
    db.add(Report(reporter_id=user_id, ap_id=ap_id, reason='wrong'))
    seed_votes(db, password_id)
    assert vote(client, voter, password_id).json()['access_point_deleted']
    assert db.scalar(select(func.count()).select_from(Report)) == 0


@pytest.mark.parametrize('operation', ['switch', 'retract', 'account_delete'])
def test_removing_upvote_triggers_cleanup(client, db, registered_user, sample_ap, operation):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='upvoter@example.com')
    user_id = db.scalar(select(User.id).where(User.email == voter['email']))
    seed_votes(db, password_id, down=10, voter_id=user_id, value=1)
    if operation == 'switch':
        response = vote(client, voter, password_id)
        assert response.status_code == 200
        assert response.json()['score'] == -11
        assert response.json()['password_deleted']
    elif operation == 'retract':
        assert client.delete(f'/api/v1/passwords/{password_id}/vote', headers=auth_headers(voter)).status_code == 204
    else:
        assert client.delete('/api/v1/auth/me', headers=auth_headers(voter)).status_code == 204
    db.expire_all()
    assert db.get(ApPassword, password_id) is None
    assert db.get(AccessPoint, ap_id) is None


def test_old_flagged_anonymous_candidate_is_not_exempt(client, db, registered_user, sample_ap):
    ap_id, password_id = ids(sample_ap)
    candidate = db.get(ApPassword, password_id)
    candidate.status = 'flagged'
    candidate.created_at = datetime.now(timezone.utc) - timedelta(days=30)
    candidate.submitted_by = None
    seed_votes(db, password_id)
    voter = registered_user(email='flagged@example.com')
    assert vote(client, voter, password_id).json()['password_deleted']


def test_duplicate_upvote_and_repeated_votes(client, db, registered_user, sample_ap):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='duplicate@example.com')
    seed_votes(db, password_id)
    response = client.post(f'/api/v1/access-points/{ap_id}/passwords',
        json={'password': 'espresso42', 'consent_confirmed': True}, headers=auth_headers(voter))
    assert response.status_code == 200
    assert response.json()['passwords'][0]['score'] == -8
    assert vote(client, voter, password_id, value=1).json()['score'] == -8
    response = vote(client, voter, password_id)
    assert response.json()['score'] == -10
    assert response.json()['password_deleted']


def test_concurrent_votes_do_not_resurrect_candidate(db, registered_user, sample_ap):
    ap_id, password_id = ids(sample_ap)
    first = registered_user(email='concurrent-one@example.com')
    second = registered_user(email='concurrent-two@example.com')
    user_ids = list(db.scalars(select(User.id).where(User.email.in_([first['email'], second['email']]))))
    seed_votes(db, password_id, down=8)
    def cast(user_id):
        with Session(engine) as session:
            assert pruning.lock_candidate(session, password_id) is not None
            outcome = service.set_vote(session, user_id, password_id, -1)
            session.commit()
            return outcome
    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(cast, user_ids))
    assert sorted(outcome.score for outcome in outcomes) == [-10, -9]
    assert sum(outcome.password_deleted for outcome in outcomes) == 1
    db.expire_all()
    assert db.get(AccessPoint, ap_id) is None


@pytest.mark.parametrize('submission_first', [False, True])
def test_concurrent_submission_is_not_lost(db, registered_user, sample_ap, submission_first):
    ap_id, password_id = ids(sample_ap)
    voter = registered_user(email='race@example.com')
    user_id = db.scalar(select(User.id).where(User.email == voter['email']))
    seed_votes(db, password_id)
    locked, proceed = Event(), Event()
    def cast():
        with Session(engine) as session:
            assert pruning.lock_candidate(session, password_id) is not None
            if not submission_first:
                locked.set()
                assert proceed.wait(5)
            result = service.set_vote(session, user_id, password_id, -1)
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
            candidate, _ = service.add_candidate(session, ap, 'replacement-pass', user_id)
            candidate_id = candidate.id
            session.commit()
            return candidate_id
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(submit if submission_first else cast)
        assert locked.wait(5)
        second = pool.submit(cast if submission_first else submit)
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


def test_migration_reconciles_counts_and_only_removes_newly_empty_networks(db, sample_ap):
    ap_id, password_id = ids(sample_ap)
    seed_votes(db, password_id, down=10)
    base = dict(ssid='Migration fixture', geom=service.geometry_point(0, 0), latitude=0, longitude=0)
    empty, with_sibling = AccessPoint(**base), AccessPoint(**base)
    db.add_all([empty, with_sibling])
    db.flush()
    bad = ApPassword(access_point_id=with_sibling.id, password_ciphertext=b'x', status='flagged')
    good = ApPassword(access_point_id=with_sibling.id, password_ciphertext=b'y', down_votes=15)
    db.add_all([bad, good])
    db.commit()
    seed_votes(db, bad.id, down=12)
    db.get(ApPassword, password_id).down_votes = 0
    db.commit()
    empty_id, sibling_ap_id, bad_id, good_id = empty.id, with_sibling.id, bad.id, good.id
    spec = importlib.util.spec_from_file_location('pruning_migration',
        Path(__file__).parents[1] / 'alembic/versions/0002_prune_low_score_passwords.py')
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    db.rollback()
    with Operations.context(MigrationContext.configure(db.connection())):
        migration.upgrade()
        migration.upgrade()
    db.commit()
    db.expire_all()
    assert db.get(AccessPoint, ap_id) is None
    assert db.get(AccessPoint, empty_id) is not None
    assert db.get(AccessPoint, sibling_ap_id) is not None
    assert db.get(ApPassword, bad_id) is None
    assert db.get(ApPassword, good_id).score == 0
