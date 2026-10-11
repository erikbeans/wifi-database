import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session

from app import security
from app.access_points import crypto, service
from app.access_points.moderation import file_report
from app.access_points.models import AccessPoint, ApPassword
from app.access_points.schemas import (
    AccessPointCreate,
    AccessPointDetail,
    AccessPointSummary,
    PasswordCandidate,
    PasswordCandidateCreate,
    ReportRequest,
    ReportResponse,
    VoteRequest,
    VoteResponse,
)
from app.audit import record
from app.auth.models import User
from app.config import get_settings
from app.database import get_db
from app.ratelimits import limiter, user_key

settings = get_settings()

access_points_router = APIRouter()
passwords_router = APIRouter()
reports_router = APIRouter()


def _detail_payload(
    db: Session, ap: AccessPoint, viewer_id: uuid.UUID, duplicate: bool | None = None
) -> AccessPointDetail:
    rows = service.ranked_candidates(db, ap.id, viewer_id)
    candidates = [
        PasswordCandidate(
            id=password_row.id,
            password=crypto.open(password_row.password_ciphertext),
            up_votes=password_row.up_votes,
            down_votes=password_row.down_votes,
            score=password_row.score,
            my_vote=my_vote,
        )
        for password_row, my_vote in rows
    ]
    return AccessPointDetail(
        id=ap.id,
        ssid=ap.ssid,
        bssid=ap.bssid,
        security=ap.security,
        latitude=ap.latitude,
        longitude=ap.longitude,
        passwords=candidates,
        duplicate_password=duplicate,
    )


def _summaries(rows: list[dict]) -> list[AccessPointSummary]:
    return [AccessPointSummary(**row) for row in rows]


@access_points_router.get("/near", response_model=list[AccessPointSummary])
def near(
    lat: float = Query(ge=-90, le=90),
    lon: float = Query(ge=-180, le=180),
    radius_m: int | None = Query(default=None, ge=0),
    limit: int = Query(default=50, ge=1),
    user: User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
) -> list[AccessPointSummary]:
    radius = settings.default_radius_m if radius_m is None else radius_m
    if radius > settings.max_radius_m:
        raise HTTPException(status_code=422, detail=f"radius_m must not exceed {settings.max_radius_m}")
    limit = min(limit, settings.max_list_limit)
    return _summaries(service.list_near(db, lat, lon, radius, limit))


@access_points_router.get("/in-bbox", response_model=list[AccessPointSummary])
def in_bbox(
    min_lat: float = Query(ge=-90, le=90),
    min_lon: float = Query(ge=-180, le=180),
    max_lat: float = Query(ge=-90, le=90),
    max_lon: float = Query(ge=-180, le=180),
    limit: int = Query(default=50, ge=1),
    user: User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
) -> list[AccessPointSummary]:
    if min_lat > max_lat or min_lon > max_lon:
        raise HTTPException(status_code=422, detail="bbox min values must not exceed max values")
    limit = min(limit, settings.max_list_limit)
    return _summaries(service.list_in_bbox(db, min_lat, min_lon, max_lat, max_lon, limit))


@access_points_router.get("/search", response_model=list[AccessPointSummary])
def search(
    q: str = Query(min_length=1, max_length=settings.search_query_max_chars),
    limit: int = Query(default=50, ge=1),
    user: User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
) -> list[AccessPointSummary]:
    limit = min(limit, settings.max_list_limit)
    return _summaries(service.search_by_name(db, q, limit))


@access_points_router.get("/{ap_id}", response_model=AccessPointDetail)
@limiter.limit(f"{settings.detail_limit_per_minute}/minute", key_func=user_key)
@limiter.limit(f"{settings.detail_limit_per_day}/day", key_func=user_key)
def ap_detail(
    request: Request,
    ap_id: uuid.UUID,
    user: User = Depends(security.get_verified_user),
    db: Session = Depends(get_db),
) -> AccessPointDetail:
    ap = db.get(AccessPoint, ap_id)
    if ap is None:
        raise HTTPException(status_code=404, detail="access point not found")
    payload = _detail_payload(db, ap, user.id)
    record(db, user.id, "password_view", ap.id)
    db.commit()
    return payload


@access_points_router.post("", status_code=201, response_model=AccessPointDetail)
@limiter.limit(f"{settings.submission_limit_per_day}/day", key_func=user_key)
def create_access_point(
    request: Request,
    payload: AccessPointCreate,
    user: User = Depends(security.get_verified_user),
    db: Session = Depends(get_db),
) -> Response:
    existing_ap = service.find_by_bssid(db, payload.bssid) if payload.bssid else None
    ap = existing_ap
    if ap is None:
        ap = AccessPoint(
            ssid=payload.ssid,
            bssid=payload.bssid,
            security=payload.security,
            geom=service.geometry_point(payload.longitude, payload.latitude),
            latitude=payload.latitude,
            longitude=payload.longitude,
            submitted_by=user.id,
        )
        db.add(ap)
        db.flush()
    candidate, duplicate = service.add_candidate(db, ap, payload.password, user.id)
    record(db, user.id, "submission", ap.id)
    db.commit()
    payload_out = _detail_payload(db, ap, user.id, duplicate=duplicate)
    status_code = 200 if existing_ap is not None else 201
    return JSONResponse(status_code=status_code, content=payload_out.model_dump(mode="json"))


@access_points_router.post("/{ap_id}/passwords", status_code=201, response_model=AccessPointDetail)
@limiter.limit(f"{settings.submission_limit_per_day}/day", key_func=user_key)
def add_password_candidate(
    request: Request,
    ap_id: uuid.UUID,
    payload: PasswordCandidateCreate,
    user: User = Depends(security.get_verified_user),
    db: Session = Depends(get_db),
) -> Response:
    ap = db.get(AccessPoint, ap_id)
    if ap is None:
        raise HTTPException(status_code=404, detail="access point not found")
    candidate, duplicate = service.add_candidate(db, ap, payload.password, user.id)
    record(db, user.id, "submission", ap.id)
    db.commit()
    payload_out = _detail_payload(db, ap, user.id, duplicate=duplicate)
    status_code = 200 if duplicate else 201
    return JSONResponse(status_code=status_code, content=payload_out.model_dump(mode="json"))


@access_points_router.delete("/{ap_id}", status_code=204)
def delete_access_point(
    ap_id: uuid.UUID,
    user: User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
) -> Response:
    ap = db.get(AccessPoint, ap_id)
    if ap is None:
        raise HTTPException(status_code=404, detail="access point not found")
    if ap.submitted_by != user.id:
        raise HTTPException(status_code=403, detail="not the owner of this access point")
    db.delete(ap)
    db.commit()
    return Response(status_code=204)


@passwords_router.post("/{password_id}/vote", response_model=VoteResponse)
@limiter.limit(f"{settings.vote_limit_per_day}/day", key_func=user_key)
def vote(
    request: Request,
    password_id: uuid.UUID,
    payload: VoteRequest,
    user: User = Depends(security.get_verified_user),
    db: Session = Depends(get_db),
) -> VoteResponse:
    password_row = db.get(ApPassword, password_id)
    if password_row is None:
        raise HTTPException(status_code=404, detail="password candidate not found")
    row = service.set_vote(db, user.id, password_id, payload.value)
    record(db, user.id, "vote", password_id)
    db.commit()
    return VoteResponse(
        password_id=row.id,
        up_votes=row.up_votes,
        down_votes=row.down_votes,
        score=row.score,
        my_vote=payload.value,
    )


@passwords_router.delete("/{password_id}/vote", status_code=204)
@limiter.limit(f"{settings.vote_limit_per_day}/day", key_func=user_key)
def retract_vote(
    request: Request,
    password_id: uuid.UUID,
    user: User = Depends(security.get_verified_user),
    db: Session = Depends(get_db),
) -> Response:
    password_row = db.get(ApPassword, password_id)
    if password_row is None:
        raise HTTPException(status_code=404, detail="password candidate not found")
    service.retract_vote(db, user.id, password_id)
    db.commit()
    return Response(status_code=204)


@passwords_router.delete("/{password_id}", status_code=204)
def delete_password_candidate(
    password_id: uuid.UUID,
    user: User = Depends(security.get_current_user),
    db: Session = Depends(get_db),
) -> Response:
    password_row = db.get(ApPassword, password_id)
    if password_row is None:
        raise HTTPException(status_code=404, detail="password candidate not found")
    if password_row.submitted_by != user.id:
        raise HTTPException(status_code=403, detail="not the owner of this password candidate")
    db.delete(password_row)
    db.commit()
    return Response(status_code=204)


@reports_router.post("", status_code=201, response_model=ReportResponse)
@limiter.limit(f"{settings.report_limit_per_day}/day", key_func=user_key)
def create_report(
    request: Request,
    payload: ReportRequest,
    user: User = Depends(security.get_verified_user),
    db: Session = Depends(get_db),
) -> ReportResponse:
    if payload.ap_id is not None and db.get(AccessPoint, payload.ap_id) is None:
        raise HTTPException(status_code=404, detail="access point not found")
    if payload.password_id is not None and db.get(ApPassword, payload.password_id) is None:
        raise HTTPException(status_code=404, detail="password candidate not found")
    file_report(db, user.id, payload.ap_id, payload.password_id, payload.reason)
    record(db, user.id, "report", payload.password_id or payload.ap_id)
    db.commit()
    return ReportResponse(status="recorded")
