from fastapi import FastAPI

from app.access_points.router import router as access_points_router
from app.auth.router import router as auth_router

app = FastAPI(title="WiFi Database API", version="0.1.0")

app.include_router(auth_router, prefix="/api/v1/auth", tags=["auth"])
app.include_router(access_points_router, prefix="/api/v1/access-points", tags=["access-points"])


@app.get("/healthz", include_in_schema=False)
def healthz() -> dict[str, str]:
    return {"status": "ok", "service": "wifi-database-api"}
