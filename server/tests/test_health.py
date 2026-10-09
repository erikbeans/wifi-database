from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_healthz_returns_ok() -> None:
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "wifi-database-api"}


def test_api_v1_is_wired() -> None:
    response = client.get("/api/v1/auth")
    assert response.status_code in {404, 405}
