import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import BigInteger, DateTime, ForeignKey, Text, delete, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, Session

from app.config import get_settings
from app.database import Base


class AuditEvent(Base):
    __tablename__ = "audit_events"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    event_type: Mapped[str] = mapped_column(Text, nullable=False)
    target_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


def record(db: Session, user_id: uuid.UUID | None, event_type: str, target_id: uuid.UUID | None = None) -> None:
    db.add(AuditEvent(user_id=user_id, event_type=event_type, target_id=target_id))


def purge(db: Session) -> None:
    settings = get_settings()
    cutoff = datetime.now(timezone.utc) - timedelta(days=settings.audit_retention_days)
    db.execute(delete(AuditEvent).where(AuditEvent.created_at < cutoff))
