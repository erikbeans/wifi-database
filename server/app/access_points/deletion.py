"""Contributor deletion policy; all times come from the database wall clock."""

import uuid
from datetime import datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.access_points.models import AccessPoint, ApPassword

DELETION_WINDOW = timedelta(minutes=15)
WINDOW_ENDED = "The 15-minute deletion window has ended."
OTHER_CONTRIBUTIONS = "This access point contains passwords contributed by other people."


def server_time(db: Session) -> datetime:
    # now() is the transaction start time and can be stale after a lock wait.
    return db.scalar(select(func.clock_timestamp()))


def lock_access_point(db: Session, ap_id: uuid.UUID) -> AccessPoint | None:
    return db.scalar(
        select(AccessPoint)
        .where(AccessPoint.id == ap_id)
        .with_for_update()
        .execution_options(populate_existing=True)
    )


def password_deadline(password: ApPassword, viewer_id: uuid.UUID) -> datetime | None:
    if password.submitted_by != viewer_id:
        return None
    return password.created_at + DELETION_WINDOW


def access_point_deadline(
    db: Session, ap: AccessPoint, viewer_id: uuid.UUID
) -> datetime | None:
    if ap.submitted_by != viewer_id:
        return None
    # Include flagged and anonymized candidates, not just the ranked list.
    passwords = db.scalars(select(ApPassword).where(ApPassword.access_point_id == ap.id)).all()
    if any(password.submitted_by != viewer_id for password in passwords):
        return None
    return min([ap.created_at + DELETION_WINDOW] + [
        password.created_at + DELETION_WINDOW for password in passwords
    ])
