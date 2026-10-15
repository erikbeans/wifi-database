"""Persistent grace periods and atomic cleanup under the parent access-point lock."""

import uuid
from dataclasses import dataclass
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.access_points import deletion
from app.access_points.models import AccessPoint, ApPassword

DELETE_SCORE = -1
DELETION_DELAY = timedelta(hours=24)


@dataclass(frozen=True)
class VoteOutcome:
    password_id: uuid.UUID
    up_votes: int
    down_votes: int
    score: int
    password_deleted: bool = False
    access_point_deleted: bool = False


def lock_access_point(db: Session, ap_id: uuid.UUID) -> AccessPoint | None:
    return deletion.lock_access_point(db, ap_id)


def lock_candidate(db: Session, password_id: uuid.UUID) -> ApPassword | None:
    ap_id = db.scalar(select(ApPassword.access_point_id).where(ApPassword.id == password_id))
    if ap_id is None or lock_access_point(db, ap_id) is None:
        return None
    return db.get(ApPassword, password_id, populate_existing=True)


def prune(db: Session, row: ApPassword) -> VoteOutcome:
    # Snapshot counts before DELETE; generated score may not yet be refreshed.
    score = row.up_votes - row.down_votes
    outcome = VoteOutcome(row.id, row.up_votes, row.down_votes, score)
    if score > DELETE_SCORE:
        row.low_score_since = None
        return outcome
    now = deletion.server_time(db)
    if row.low_score_since is None:
        row.low_score_since = now
        return outcome
    if now < row.low_score_since + DELETION_DELAY:
        return outcome
    ap_id = row.access_point_id
    db.delete(row)
    db.flush()
    remaining = db.scalar(select(ApPassword.id).where(ApPassword.access_point_id == ap_id).limit(1))
    if remaining is None:
        db.delete(db.get(AccessPoint, ap_id))
        db.flush()
    return VoteOutcome(
        outcome.password_id, outcome.up_votes, outcome.down_votes,
        score, password_deleted=True, access_point_deleted=remaining is None,
    )


def cleanup_due(db: Session, batch_size: int = 100) -> int:
    """Process a bounded batch; skip parents being mutated by another worker/request.

    The caller commits the batch. Returning the number of parents processed lets
    the periodic worker drain a backlog without waiting another polling interval.
    """
    cutoff = deletion.server_time(db) - DELETION_DELAY
    parents = db.scalars(
        select(AccessPoint)
        .where(AccessPoint.id.in_(
            select(ApPassword.access_point_id).where(ApPassword.low_score_since <= cutoff)
        ))
        .order_by(AccessPoint.id)
        .limit(batch_size)
        .with_for_update(skip_locked=True)
        .execution_options(populate_existing=True)
    ).all()
    for parent in parents:
        candidates = db.scalars(
            select(ApPassword)
            .where(ApPassword.access_point_id == parent.id, ApPassword.low_score_since <= cutoff)
            .execution_options(populate_existing=True)
        ).all()
        for candidate in candidates:
            prune(db, candidate)
    return len(parents)
