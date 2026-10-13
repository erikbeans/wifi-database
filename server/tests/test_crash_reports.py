import base64
import os

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

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_crash_report_is_accepted_and_logged(caplog) -> None:
    payload = {
        "stack_trace": "java.lang.RuntimeException: boom\n\tat FakeClass.fakeMethod(Fake.java:1)",
        "device_model": "SM-F956B",
        "android_version": "16",
        "app_version": "0.1.0",
    }
    response = client.post("/api/v1/crash-reports", json=payload)
    assert response.status_code == 204


def test_crash_report_rejects_oversized_trace() -> None:
    payload = {"stack_trace": "x" * 20000}
    response = client.post("/api/v1/crash-reports", json=payload)
    assert response.status_code == 422
