import asyncio
from types import SimpleNamespace

import pytest
from sqlalchemy.orm import sessionmaker

from app import main
from app.access_points import cleanup, pruning, service
from app.access_points.models import AccessPoint, ApPassword
from conftest import engine
from test_delayed_pruning import expire_grace
from test_pruning import ids, seed_votes


def test_worker_drains_batches_and_multiple_expired_siblings(db, sample_ap, monkeypatch):
    first_ap, first_password = ids(sample_ap)
    base = dict(ssid='Cleanup fixture', geom=service.geometry_point(0, 0), latitude=0, longitude=0)
    second, empty = AccessPoint(**base), AccessPoint(**base)
    db.add_all([second, empty])
    db.flush()
    siblings = [ApPassword(access_point_id=first_ap, password_ciphertext=b'x'),
                ApPassword(access_point_id=second.id, password_ciphertext=b'y')]
    db.add_all(siblings)
    db.commit()
    parent_ids = [first_ap, second.id]
    for password_id in [first_password, *(row.id for row in siblings)]:
        seed_votes(db, password_id, down=1)
        expire_grace(db, password_id)
    monkeypatch.setattr(cleanup, 'SessionLocal', sessionmaker(bind=engine))
    monkeypatch.setattr(cleanup, 'BATCH_SIZE', 1)
    cleanup.run_cleanup()
    db.expire_all()
    assert all(db.get(AccessPoint, parent_id) is None for parent_id in parent_ids)
    assert db.get(AccessPoint, empty.id) is not None


def test_failed_batch_rolls_back_and_can_be_retried(db, sample_ap, monkeypatch):
    ap_id, password_id = ids(sample_ap)
    seed_votes(db, password_id, down=1)
    expire_grace(db, password_id)
    monkeypatch.setattr(cleanup, 'SessionLocal', sessionmaker(bind=engine))
    real_prune = pruning.prune
    def fail_after_delete(session, row):
        real_prune(session, row)
        raise RuntimeError('interrupted batch')
    monkeypatch.setattr(pruning, 'prune', fail_after_delete)
    with pytest.raises(RuntimeError, match='interrupted batch'):
        cleanup.run_cleanup()
    db.expire_all()
    assert db.get(ApPassword, password_id) is not None
    assert db.get(AccessPoint, ap_id) is not None
    monkeypatch.setattr(pruning, 'prune', real_prune)
    cleanup.run_cleanup()
    db.expire_all()
    assert db.get(AccessPoint, ap_id) is None


def test_loop_runs_immediately_and_retries_after_failure(monkeypatch):
    runs, intervals = [], []
    def run():
        runs.append(True)
        if len(runs) == 1:
            raise RuntimeError('temporary database outage')
    async def offload(function):
        function()
    async def sleep(seconds):
        intervals.append(seconds)
        if len(intervals) == 2:
            raise asyncio.CancelledError
    monkeypatch.setattr(cleanup, 'run_cleanup', run)
    monkeypatch.setattr(cleanup.asyncio, 'to_thread', offload)
    monkeypatch.setattr(cleanup.asyncio, 'sleep', sleep)
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(cleanup.cleanup_loop())
    assert len(runs) == 2
    assert intervals == [3600, 3600]


@pytest.mark.parametrize('enabled', [True, False])
def test_application_lifespan_starts_and_stops_worker(monkeypatch, enabled):
    async def exercise():
        started, stopped = asyncio.Event(), asyncio.Event()
        async def worker():
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                stopped.set()
        monkeypatch.setattr(main.cleanup, 'cleanup_loop', worker)
        monkeypatch.setattr(main, 'get_settings', lambda: SimpleNamespace(password_cleanup_enabled=enabled))
        async with main.lifespan(main.app):
            if enabled:
                await asyncio.wait_for(started.wait(), timeout=1)
            else:
                assert not started.is_set()
        assert stopped.is_set() is enabled
    asyncio.run(exercise())
