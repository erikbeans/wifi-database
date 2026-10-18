from fastapi.testclient import TestClient
import pytest

from app import link_pages
from app.config import Settings
from app.main import app

client = TestClient(app)


def test_verify_link_page_shows_app_fallback() -> None:
    response = client.get("/verify", params={"token": "abc123-_x"})
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    assert "https://wifidb.clickbeans.com/verify?token=abc123-_x" in response.text


def test_password_reset_link_page_shows_app_fallback() -> None:
    response = client.get("/password-reset", params={"token": "abc123-_x"})
    assert response.status_code == 200
    assert "https://wifidb.clickbeans.com/password-reset?token=abc123-_x" in response.text


def test_assetlinks_returns_404_without_fingerprints() -> None:
    response = client.get("/.well-known/assetlinks.json")
    assert response.status_code == 404


def test_assetlinks_targets_clickbeans_package(monkeypatch: pytest.MonkeyPatch) -> None:
    fingerprints = ["AA:" * 31 + "AA"]
    settings = Settings(android_sha256_fingerprints=fingerprints)
    monkeypatch.setattr(link_pages, "get_settings", lambda: settings)
    response = client.get("/.well-known/assetlinks.json")
    assert response.status_code == 200
    assert response.json() == [{
        "relation": ["delegate_permission/common.handle_all_urls"],
        "target": {
            "namespace": "android_app",
            "package_name": "com.clickbeans.wifidatabase",
            "sha256_cert_fingerprints": fingerprints,
        },
    }]


def test_privacy_page_serves_the_privacy_statement() -> None:
    response = client.get("/privacy")
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    assert "Privacy Statement" in response.text
    assert "support@clickbeans.com" in response.text


def test_terms_page_serves_the_terms_of_service() -> None:
    response = client.get("/terms")
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    assert "Terms of Service" in response.text
    assert "Only share networks you" in response.text
