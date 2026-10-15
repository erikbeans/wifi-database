"""Atomic score-based cleanup. Callers hold the parent access-point lock."""

import uuid
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.access_points import deletion
from app.access_points.models import AccessPoint, ApPassword

DELETE_SCORE = -10


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
