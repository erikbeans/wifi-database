import base64
import os
import re

os.environ.setdefault("WIFI_DATABASE_URL", "postgresql+psycopg2://wifi:wifi@localhost:5433/wifi_test")
os.environ.setdefault("WIFI_JWT_SECRET", "test-jwt-secret-0123456789abcdef")
os.environ.setdefault("WIFI_ENCRYPTION_KEY", base64.b64encode(b"0123456789abcdef0123456789abcdef").decode())
os.environ["WIFI_RATE_LIMIT_PER_MINUTE"] = "0"
os.environ["WIFI_REGISTER_IP_LIMIT_PER_DAY"] = "100000"
os.environ["WIFI_DETAIL_LIMIT_PER_MINUTE"] = "100000"
os.environ["WIFI_DETAIL_LIMIT_PER_DAY"] = "100000"
os.environ["WIFI_SUBMISSION_LIMIT_PER_DAY"] = "100000"
os.environ["WIFI_VOTE_LIMIT_PER_DAY"] = "100000"
os.environ["WIFI_REPORT_LIMIT_PER_DAY"] = "100000"
os.environ["WIFI_PASSWORD_CLEANUP_ENABLED"] = "false"  # Tests drive cleanup explicitly.

import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

import app.email as email_module
from app.database import Base, get_db
from app.main import app

engine = create_engine(os.environ["WIFI_DATABASE_URL"])
TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


@pytest.fixture(scope="session", autouse=True)
def schema():
    with engine.begin() as conn:
        conn.execute(text("CREATE EXTENSION IF NOT EXISTS postgis"))
        conn.execute(text("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
        conn.execute(text("DROP TABLE IF EXISTS alembic_version"))
    command.upgrade(Config("alembic.ini"), "head")
    Base.metadata.drop_all(engine)
    yield
    Base.metadata.drop_all(engine)


@pytest.fixture()
def db():
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    session = TestingSessionLocal()
    yield session
    session.close()
    Base.metadata.drop_all(engine)


@pytest.fixture()
def client(db):
    def _override():
        yield db

    app.dependency_overrides[get_db] = _override
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.pop(get_db, None)


@pytest.fixture(autouse=True)
def sent_emails(monkeypatch):
    captured: list[tuple[str, str, str]] = []
    monkeypatch.setattr(
        email_module, "send_email", lambda to, subject, body: captured.append((to, subject, body))
    )
    return captured


def extract_token(email_body: str) -> str:
    match = re.search(r"token=([A-Za-z0-9_\-]+)", email_body)
    assert match is not None
    return match.group(1)


def auth_headers(user: dict) -> dict:
    return {"Authorization": f"Bearer {user['access_token']}"}


@pytest.fixture()
def registered_user(client, sent_emails):
    def _register(email="user@example.com", password="password123", verify=True) -> dict:
        response = client.post("/api/v1/auth/register", json={"email": email, "password": password})
        assert response.status_code == 201, response.text
        user = {"email": email, "password": password}
        if verify:
            token = extract_token(sent_emails[-1][2])
            response = client.post("/api/v1/auth/verify", json={"token": token})
            assert response.status_code == 200, response.text
            user["access_token"] = response.json()["access_token"]
            user["refresh_token"] = response.json()["refresh_token"]
        else:
            response = client.post("/api/v1/auth/login", json={"email": email, "password": password})
            assert response.status_code == 200, response.text
            user["access_token"] = response.json()["access_token"]
            user["refresh_token"] = response.json()["refresh_token"]
        return user

    return _register


@pytest.fixture()
def sample_ap(client, registered_user) -> dict:
    user = registered_user()
    response = client.post(
        "/api/v1/access-points",
        json={
            "ssid": "Cafe Nero Guest",
            "password": "espresso42",
            "bssid": "AA:BB:CC:11:22:33",
            "security": "WPA2",
            "latitude": 47.6062,
            "longitude": -122.3321,
            "consent_confirmed": True,
        },
        headers=auth_headers(user),
    )
    assert response.status_code == 201, response.text
    return {"ap": response.json(), "user": user}
