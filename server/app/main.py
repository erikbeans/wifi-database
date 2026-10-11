from fastapi import FastAPI
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware

from app.access_points.router import access_points_router, passwords_router, reports_router
from app.auth.router import router as auth_router
from app.ratelimits import limiter

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
