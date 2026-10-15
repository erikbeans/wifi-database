"""Persist a 24-hour grace period for passwords scoring <= -1.

Revision ID: 0003
Revises: 0002
"""
from alembic import op
import sqlalchemy as sa

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("LOCK TABLE users, access_points, ap_passwords, password_votes, reports IN SHARE ROW EXCLUSIVE MODE")
    op.add_column("ap_passwords", sa.Column("low_score_since", sa.DateTime(timezone=True), nullable=True))
    op.create_index(
        "idx_ap_passwords_low_score_since", "ap_passwords", ["low_score_since"],
        postgresql_where=sa.text("low_score_since IS NOT NULL"),
    )
    op.execute("""
        UPDATE ap_passwords p SET
          up_votes = (SELECT count(*) FROM password_votes v WHERE v.password_id = p.id AND v.value = 1),
          down_votes = (SELECT count(*) FROM password_votes v WHERE v.password_id = p.id AND v.value = -1)
    """)
    # Historical crossing times are unknown: give every existing negative score
    # a full grace period starting at migration, without deleting any rows.
    op.execute("UPDATE ap_passwords SET low_score_since = statement_timestamp() WHERE score <= -1")


def downgrade() -> None:
    op.drop_index("idx_ap_passwords_low_score_since", table_name="ap_passwords")
    op.drop_column("ap_passwords", "low_score_since")
