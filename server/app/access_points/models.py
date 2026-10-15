import uuid
from datetime import datetime
from typing import Any

from geoalchemy2 import Geometry
from sqlalchemy import (
    CheckConstraint,
    DateTime,
    Double,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    SmallInteger,
    Text,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.schema import Computed

from app.database import Base


class AccessPoint(Base):
    __tablename__ = "access_points"
    __table_args__ = (
        Index("uq_access_points_bssid", "bssid", unique=True, postgresql_where=text("bssid IS NOT NULL")),
        Index(
            "idx_access_points_ssid_trgm",
            "ssid",
            postgresql_using="gin",
            postgresql_ops={"ssid": "gin_trgm_ops"},
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    ssid: Mapped[str] = mapped_column(Text, nullable=False)
    bssid: Mapped[str | None] = mapped_column(Text)
    security: Mapped[str | None] = mapped_column(Text)
    geom: Mapped[Any] = mapped_column(Geometry(geometry_type="POINT", srid=4326), nullable=False)
    latitude: Mapped[float] = mapped_column(Double, nullable=False)
    longitude: Mapped[float] = mapped_column(Double, nullable=False)
    submitted_by: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class ApPassword(Base):
    __tablename__ = "ap_passwords"
    __table_args__ = (
        CheckConstraint("status IN ('active', 'flagged')", name="ck_ap_passwords_status"),
        Index(
            "idx_ap_passwords_rank",
            "access_point_id",
            text("score DESC"),
            text("created_at DESC"),
            postgresql_where=text("status = 'active'"),
        ),
        Index(
            "idx_ap_passwords_low_score_since",
            "low_score_since",
            postgresql_where=text("low_score_since IS NOT NULL"),
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    access_point_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("access_points.id", ondelete="CASCADE"), nullable=False
    )
    password_ciphertext: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    password_key_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1, server_default="1")
    up_votes: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    down_votes: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    score: Mapped[int] = mapped_column(Integer, Computed("up_votes - down_votes", persisted=True), nullable=False)
    low_score_since: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    status: Mapped[str] = mapped_column(Text, nullable=False, default="active", server_default="active")
    submitted_by: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class PasswordVote(Base):
    __tablename__ = "password_votes"
    __table_args__ = (CheckConstraint("value IN (-1, 1)", name="ck_password_votes_value"),)

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    password_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("ap_passwords.id", ondelete="CASCADE"), primary_key=True
    )
    value: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class Report(Base):
    __tablename__ = "reports"
    __table_args__ = (CheckConstraint("ap_id IS NOT NULL OR password_id IS NOT NULL", name="ck_reports_target"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    reporter_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    ap_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("access_points.id", ondelete="CASCADE")
    )
    password_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("ap_passwords.id", ondelete="CASCADE")
    )
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
