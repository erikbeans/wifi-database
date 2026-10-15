import importlib.util
from pathlib import Path

from alembic.migration import MigrationContext
from alembic.operations import Operations

from app.access_points import deletion
from app.access_points.models import ApPassword
from test_pruning import ids, seed_votes


def test_migration_reconciles_counts_and_grants_full_24_hours(db, sample_ap):
    ap_id, password_id = ids(sample_ap)
    seed_votes(db, password_id, down=1)
    flagged = ApPassword(access_point_id=ap_id, password_ciphertext=b'x', status='flagged')
    stale_count = ApPassword(access_point_id=ap_id, password_ciphertext=b'y', down_votes=15)
    db.add_all([flagged, stale_count])
    db.commit()
    seed_votes(db, flagged.id, down=2)
    db.get(ApPassword, password_id).down_votes = 0
    db.commit()
    password_ids = [password_id, flagged.id, stale_count.id]
    spec = importlib.util.spec_from_file_location('delayed_pruning_migration',
        Path(__file__).parents[1] / 'alembic/versions/0003_delay_low_score_deletion.py')
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    before = deletion.server_time(db)
    # Recreate the old schema, then test the actual migration including its DDL.
    with Operations.context(MigrationContext.configure(db.connection())):
        migration.downgrade()
        migration.upgrade()
    db.commit()
    db.expire_all()
    after = deletion.server_time(db)
    negative, flagged_row, corrected = [db.get(ApPassword, value) for value in password_ids]
    assert negative.score == -1
    assert flagged_row.score == -2
    assert corrected.score == 0
    assert corrected.low_score_since is None
    assert before <= negative.low_score_since <= after
    assert before <= flagged_row.low_score_since <= after
    assert negative.low_score_since == flagged_row.low_score_since
