"""Reconcile vote counts and permanently remove passwords scoring <= -10.

Revision ID: 0002
Revises: 0001
"""
from alembic import op
import sqlalchemy as sa

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Prevent old API workers from racing the one-time reconciliation/cleanup.
    op.execute("LOCK TABLE users, access_points, ap_passwords, password_votes, reports IN SHARE ROW EXCLUSIVE MODE")
    op.execute("""
        UPDATE ap_passwords p SET
          up_votes = (SELECT count(*) FROM password_votes v WHERE v.password_id = p.id AND v.value = 1),
          down_votes = (SELECT count(*) FROM password_votes v WHERE v.password_id = p.id AND v.value = -1)
    """)
    bind = op.get_bind()
    affected = bind.execute(sa.text(
        "DELETE FROM ap_passwords WHERE score <= -10 RETURNING access_point_id"
    )).scalars().all()
    if affected:
        access_points = sa.table("access_points", sa.column("id", sa.Uuid))
        passwords = sa.table("ap_passwords", sa.column("access_point_id", sa.Uuid))
        bind.execute(sa.delete(access_points).where(
            access_points.c.id.in_(set(affected)),
            ~sa.exists(sa.select(1).where(passwords.c.access_point_id == access_points.c.id)),
        ))


def downgrade() -> None:
    # Data deletion is intentionally irreversible; no schema changes to undo.
    pass
