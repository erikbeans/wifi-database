from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_verify_link_page_shows_app_fallback() -> None:
    response = client.get("/verify", params={"token": "abc123-_x"})
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    assert "wifidatabase://verify?token=abc123-_x" in response.text


def test_password_reset_link_page_shows_app_fallback() -> None:
    response = client.get("/password-reset", params={"token": "abc123-_x"})
    assert response.status_code == 200
    assert "wifidatabase://password-reset?token=abc123-_x" in response.text


def test_assetlinks_returns_404_without_fingerprints() -> None:
    response = client.get("/.well-known/assetlinks.json")
    assert response.status_code == 404
