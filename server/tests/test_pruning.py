"""Historical migration coverage and shared PostgreSQL/PostGIS vote fixtures."""
import importlib.util
import uuid
from pathlib import Path

from alembic.migration import MigrationContext
from alembic.operations import Operations

from app.access_points import service
from app.access_points.models import AccessPoint, ApPassword, PasswordVote
from app.auth.models import User
from conftest import auth_headers


def ids(sample_ap):
    return uuid.UUID(sample_ap['ap']['id']), uuid.UUID(sample_ap['ap']['passwords'][0]['id'])


def seed_votes(db, password_id, up=0, down=0, voter_id=None, value=None):
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
