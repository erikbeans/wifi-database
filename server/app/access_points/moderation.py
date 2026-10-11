import uuid

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.access_points.models import ApPassword, Report
from app.config import get_settings


def file_report(
    db: Session,
    reporter_id: uuid.UUID,
    ap_id: uuid.UUID | None,
    password_id: uuid.UUID | None,
    reason: str,
) -> Report:
    report = Report(reporter_id=reporter_id, ap_id=ap_id, password_id=password_id, reason=reason)
    db.add(report)
    db.flush()
    if password_id is not None:
        count = (
            db.execute(select(func.count()).select_from(Report).where(Report.password_id == password_id)).scalar_one()
        )
        if count >= get_settings().flag_report_threshold:
            db.get(ApPassword, password_id).status = "flagged"
    return report
