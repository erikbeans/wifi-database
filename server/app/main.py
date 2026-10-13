from fastapi import FastAPI, Request
from fastapi.responses import Response
from pydantic import BaseModel, Field
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware

from app import link_pages
from app.access_points.router import access_points_router, passwords_router, reports_router
from app.auth.router import router as auth_router
from app.ratelimits import limiter

import logging

crash_logger = logging.getLogger("wifi.crashes")

app = FastAPI(title="WiFi Database API", version="0.1.0")

app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)

app.include_router(auth_router, prefix="/api/v1/auth", tags=["auth"])
app.include_router(access_points_router, prefix="/api/v1/access-points", tags=["access-points"])
app.include_router(passwords_router, prefix="/api/v1/passwords", tags=["passwords"])
app.include_router(reports_router, prefix="/api/v1/reports", tags=["reports"])


@app.get("/healthz", include_in_schema=False)
def healthz() -> dict[str, str]:
    return {"status": "ok", "service": "wifi-database-api"}


@app.get("/verify", include_in_schema=False)
def verify_fallback_page(token: str = ""):
    return link_pages.verify_page(token)


@app.get("/password-reset", include_in_schema=False)
def password_reset_fallback_page(token: str = ""):
    return link_pages.password_reset_page(token)


@app.get("/.well-known/assetlinks.json", include_in_schema=False)
def assetlinks():
    return link_pages.assetlinks_response()


class CrashReport(BaseModel):
    stack_trace: str = Field(min_length=1, max_length=8000)
    device_model: str = Field(default="", max_length=128)
    android_version: str = Field(default="", max_length=64)
    app_version: str = Field(default="", max_length=64)


@app.post("/api/v1/crash-reports", status_code=204, include_in_schema=False)
@limiter.limit("10/hour")
def crash_report(request: Request, report: CrashReport):
    crash_logger.error(
        "CRASH model=%r android=%r app=%r trace=%s",
        report.device_model,
        report.android_version,
        report.app_version,
        report.stack_trace,
    )
    return Response(status_code=204)
