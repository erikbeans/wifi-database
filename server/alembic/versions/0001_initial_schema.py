"""Initial schema: users, auth tokens, access points, candidates, votes, reports, audit.

Revision ID: 0001
Revises:
Create Date: 2026-10-11
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from geoalchemy2 import Geometry
from sqlalchemy.dialects import postgresql

revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS postgis")
    op.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")

    op.create_table(
        "users",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("email", sa.Text(), nullable=False),
        sa.Column("password_hash", sa.Text(), nullable=False),
        sa.Column("email_verified_at", sa.DateTime(timezone=True)),
        sa.Column("failed_login_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("locked_until", sa.DateTime(timezone=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("email", name="uq_users_email"),
    )
    op.create_index("ix_users_email", "users", ["email"])

    op.create_table(
        "auth_tokens",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("kind", sa.Text(), nullable=False),
        sa.Column("family_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("token_hash", sa.Text(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("kind IN ('refresh', 'verify', 'reset')", name="ck_auth_tokens_kind"),
        sa.UniqueConstraint("token_hash", name="uq_auth_tokens_token_hash"),
    )
    op.create_index("ix_auth_tokens_user_id", "auth_tokens", ["user_id"])

    op.create_table(
        "access_points",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("ssid", sa.Text(), nullable=False),
        sa.Column("bssid", sa.Text()),
        sa.Column("security", sa.Text()),
        sa.Column("geom", Geometry(geometry_type="POINT", srid=4326), nullable=False),
        sa.Column("latitude", sa.Double(), nullable=False),
        sa.Column("longitude", sa.Double(), nullable=False),
        sa.Column(
            "submitted_by", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="SET NULL")
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    )
    op.create_index(
        "uq_access_points_bssid",
        "access_points",
        ["bssid"],
        unique=True,
        postgresql_where=sa.text("bssid IS NOT NULL"),
    )
    op.create_index(
        "idx_access_points_ssid_trgm",
        "access_points",
        ["ssid"],
        postgresql_using="gin",
        postgresql_ops={"ssid": "gin_trgm_ops"},
    )

    op.create_table(
        "ap_passwords",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "access_point_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("access_points.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("password_ciphertext", sa.LargeBinary(), nullable=False),
        sa.Column("password_key_version", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("up_votes", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("down_votes", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("score", sa.Integer(), sa.Computed("up_votes - down_votes", persisted=True), nullable=False),
        sa.Column("status", sa.Text(), nullable=False, server_default="active"),
        sa.Column(
            "submitted_by", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="SET NULL")
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("status IN ('active', 'flagged')", name="ck_ap_passwords_status"),
    )
    op.create_index(
        "idx_ap_passwords_rank",
        "ap_passwords",
        ["access_point_id", sa.text("score DESC"), sa.text("created_at DESC")],
        postgresql_where=sa.text("status = 'active'"),
    )
    op.create_index("ix_ap_passwords_access_point_id", "ap_passwords", ["access_point_id"])

    op.create_table(
        "password_votes",
        sa.Column(
            "user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
        ),
        sa.Column(
            "password_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("ap_passwords.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("value", sa.SmallInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("value IN (-1, 1)", name="ck_password_votes_value"),
    )

    op.create_table(
        "reports",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("reporter_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="SET NULL")),
        sa.Column("ap_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("access_points.id", ondelete="CASCADE")),
        sa.Column(
            "password_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("ap_passwords.id", ondelete="CASCADE")
        ),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("ap_id IS NOT NULL OR password_id IS NOT NULL", name="ck_reports_target"),
    )
    op.create_index("ix_reports_ap_id", "reports", ["ap_id"])
    op.create_index("ix_reports_password_id", "reports", ["password_id"])

    op.create_table(
        "audit_events",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="SET NULL")),
        sa.Column("event_type", sa.Text(), nullable=False),
        sa.Column("target_id", postgresql.UUID(as_uuid=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    )
    op.create_index("ix_audit_events_user_id", "audit_events", ["user_id"])
    op.create_index("ix_audit_events_created_at", "audit_events", ["created_at"])


def downgrade() -> None:
    op.drop_table("audit_events")
    op.drop_table("reports")
    op.drop_table("password_votes")
    op.drop_table("ap_passwords")
    op.drop_table("access_points")
    op.drop_table("auth_tokens")
    op.drop_table("users")
