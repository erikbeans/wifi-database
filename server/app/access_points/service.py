import uuid
from datetime import datetime, timezone

from geoalchemy2 import Geography
from sqlalchemy import and_, func, select
from sqlalchemy.orm import Session

from app.access_points import crypto, pruning
from app.access_points.models import AccessPoint, ApPassword, PasswordVote
from app.config import get_settings


def geometry_point(longitude: float, latitude: float):
    return func.ST_SetSRID(func.ST_MakePoint(longitude, latitude), 4326)


def _geography_point(longitude: float, latitude: float):
    return geometry_point(longitude, latitude).cast(Geography(geometry_type="POINT", srid=4326))


def _to_summary(ap: AccessPoint, count, best_score, distance_m=None) -> dict:
    return {
        "id": ap.id,
        "ssid": ap.ssid,
        "bssid": ap.bssid,
        "security": ap.security,
        "latitude": ap.latitude,
        "longitude": ap.longitude,
        "distance_m": None if distance_m is None else float(distance_m),
        "password_count": int(count or 0),
        "best_score": None if best_score is None else int(best_score),
        "created_at": ap.created_at,
        "updated_at": ap.updated_at,
    }


def _active_candidates_join():
    return and_(
        ApPassword.access_point_id == AccessPoint.id,
        ApPassword.status == "active",
    )


def list_near(db: Session, latitude: float, longitude: float, radius_m: int, limit: int) -> list[dict]:
    ap_geography = AccessPoint.geom.cast(Geography(geometry_type="POINT", srid=4326))
    point_geography = _geography_point(longitude, latitude)
    distance = func.ST_Distance(ap_geography, point_geography)
    stmt = (
        select(AccessPoint, func.count(ApPassword.id), func.max(ApPassword.score), distance)
        .outerjoin(ApPassword, _active_candidates_join())
        .where(func.ST_DWithin(ap_geography, point_geography, radius_m))
        .group_by(AccessPoint.id)
        .order_by(distance)
        .limit(limit)
    )
    return [_to_summary(ap, count, best, dist) for ap, count, best, dist in db.execute(stmt).all()]


def list_in_bbox(
    db: Session, min_lat: float, min_lon: float, max_lat: float, max_lon: float, limit: int
) -> list[dict]:
    settings = get_settings()
    if max_lat - min_lat > settings.max_bbox_degrees:
        center = (min_lat + max_lat) / 2
        min_lat = center - settings.max_bbox_degrees / 2
        max_lat = center + settings.max_bbox_degrees / 2
    if max_lon - min_lon > settings.max_bbox_degrees:
        center = (min_lon + max_lon) / 2
        min_lon = center - settings.max_bbox_degrees / 2
        max_lon = center + settings.max_bbox_degrees / 2
    envelope = func.ST_MakeEnvelope(min_lon, min_lat, max_lon, max_lat, 4326)
    stmt = (
        select(AccessPoint, func.count(ApPassword.id), func.max(ApPassword.score))
        .outerjoin(ApPassword, _active_candidates_join())
        .where(AccessPoint.geom.op("&&")(envelope))
        .group_by(AccessPoint.id)
        .order_by(AccessPoint.updated_at.desc())
        .limit(limit)
    )
    return [_to_summary(ap, count, best) for ap, count, best in db.execute(stmt).all()]


def search_by_name(db: Session, query: str, limit: int) -> list[dict]:
    settings = get_settings()
    if len(query) > settings.search_query_max_chars:
        raise ValueError("query too long")
    escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    pattern = f"%{escaped}%"
    stmt = (
        select(AccessPoint, func.count(ApPassword.id), func.max(ApPassword.score))
        .outerjoin(ApPassword, _active_candidates_join())
        .where(AccessPoint.ssid.ilike(pattern, escape="\\"))
        .group_by(AccessPoint.id)
        .order_by(AccessPoint.updated_at.desc())
        .limit(limit)
    )
    return [_to_summary(ap, count, best) for ap, count, best in db.execute(stmt).all()]


def find_by_bssid(db: Session, bssid: str) -> AccessPoint | None:
    return db.execute(select(AccessPoint).where(AccessPoint.bssid == bssid)).scalar_one_or_none()


def ranked_candidates(db: Session, ap_id: uuid.UUID, viewer_id: uuid.UUID) -> list[tuple]:
    stmt = (
        select(ApPassword, PasswordVote.value)
        .outerjoin(
            PasswordVote,
            and_(PasswordVote.password_id == ApPassword.id, PasswordVote.user_id == viewer_id),
        )
        .where(ApPassword.access_point_id == ap_id, ApPassword.status == "active")
        .order_by(ApPassword.score.desc(), ApPassword.created_at.desc())
    )
    return db.execute(stmt).all()


def recompute_vote_counts(db: Session, password_id: uuid.UUID) -> ApPassword:
    row = db.get(ApPassword, password_id)
    counts = db.execute(
        select(PasswordVote.value, func.count())
        .where(PasswordVote.password_id == password_id)
        .group_by(PasswordVote.value)
    ).all()
    by_value = dict(counts)
    row.up_votes = by_value.get(1, 0)
    row.down_votes = by_value.get(-1, 0)
    return row


def set_vote(db: Session, user_id: uuid.UUID, password_id: uuid.UUID, value: int) -> pruning.VoteOutcome:
    vote = db.get(PasswordVote, (user_id, password_id))
    now = datetime.now(timezone.utc)
    if vote is None:
        db.add(PasswordVote(user_id=user_id, password_id=password_id, value=value, created_at=now, updated_at=now))
    elif vote.value != value:
        vote.value = value
        vote.updated_at = now
    db.flush()
    return pruning.prune(db, recompute_vote_counts(db, password_id))


def retract_vote(db: Session, user_id: uuid.UUID, password_id: uuid.UUID) -> pruning.VoteOutcome:
    vote = db.get(PasswordVote, (user_id, password_id))
    if vote is not None:
        db.delete(vote)
        db.flush()
    return pruning.prune(db, recompute_vote_counts(db, password_id))


def add_candidate(
    db: Session, ap: AccessPoint, plaintext: str, user_id: uuid.UUID
) -> tuple[ApPassword, bool]:
    candidates = (
        db.execute(select(ApPassword).where(ApPassword.access_point_id == ap.id)).scalars().all()
    )
    for candidate in candidates:
        try:
            existing = crypto.open(candidate.password_ciphertext)
        except ValueError:
            continue
        if existing == plaintext:
            set_vote(db, user_id, candidate.id, 1)
            return candidate, True
    ciphertext, key_version = crypto.seal(plaintext)
    row = ApPassword(
        access_point_id=ap.id,
        password_ciphertext=ciphertext,
        password_key_version=key_version,
        submitted_by=user_id,
    )
    db.add(row)
    db.flush()
    return row, False
