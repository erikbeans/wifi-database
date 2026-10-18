import asyncio
import logging
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Request
from fastapi.responses import Response
from pydantic import BaseModel, Field
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware

from app import link_pages
from app.access_points import cleanup
from app.access_points.router import access_points_router, passwords_router, reports_router
from app.auth.router import router as auth_router
from app.config import get_settings
from app.ratelimits import limiter

crash_logger = logging.getLogger("wifi.crashes")


@asynccontextmanager
async def lifespan(app: FastAPI):
    get_settings().validate_production_secrets()
    task = asyncio.create_task(cleanup.cleanup_loop()) if get_settings().password_cleanup_enabled else None
    try:
        yield
    finally:
        if task is not None:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task


app = FastAPI(title="WiFi Database API", version="0.1.0", lifespan=lifespan)

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


@app.get("/privacy", include_in_schema=False)
def privacy():
    return link_pages.privacy_page()


@app.get("/terms", include_in_schema=False)
def terms():
    return link_pages.terms_page()


class CrashReport(BaseModel):
    stack_trace: str = Field(min_length=1, max_length=8000)
    device_model: str = Field(default="", max_length=128)
    android_version: str = Field(default="", max_length=64)
    app_version: str = Field(default="", max_length=64)


def sanitize_crash_trace(trace: str) -> str:
    return trace.replace("\r", "").replace("\n", " | ")


@app.post("/api/v1/crash-reports", status_code=204, include_in_schema=False)
@limiter.limit("10/hour")
def crash_report(request: Request, report: CrashReport):
    crash_logger.error(
        "CRASH model=%r android=%r app=%r trace=%s",
        report.device_model,
        report.android_version,
        report.app_version,
        sanitize_crash_trace(report.stack_trace),
    )
    return Response(status_code=204)
