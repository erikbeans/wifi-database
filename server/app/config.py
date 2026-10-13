from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Environment-driven configuration, `WIFI_`-prefixed (see docs/DESIGN.md §6)."""

    model_config = SettingsConfigDict(env_prefix="WIFI_", env_file=".env", extra="ignore")

    app_name: str = "WiFi Database API"
    database_url: str = "postgresql+psycopg2://wifi:wifi@localhost:5432/wifi"

    jwt_secret: str = "dev-only-insecure-secret"
    jwt_algorithm: str = "HS256"
    access_token_minutes: int = 15
    refresh_token_days: int = 30

    encryption_key: str = ""

    smtp_host: str = "localhost"
    smtp_port: int = 1025
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_from: str = "no-reply@wifi-database.local"

    verify_url: str = "https://wifidb.clickbeans.com/verify?token={token}"
    reset_url: str = "https://wifidb.clickbeans.com/password-reset?token={token}"

    android_package: str = "com.erikbeans.wifidatabase"
    android_sha256_fingerprints: list[str] = []

    cors_origins: list[str] = []

    default_radius_m: int = 500
    max_radius_m: int = 5000
    max_bbox_degrees: float = 2.0
    search_query_max_chars: int = 64
    max_list_limit: int = 100

    lockout_threshold: int = 5
    lockout_base_minutes: int = 15
    lockout_max_minutes: int = 1440

    auth_email_quota_per_day: int = 10
    flag_report_threshold: int = 3
    audit_retention_days: int = 90

    rate_limit_per_minute: int = 60
    register_ip_limit_per_day: int = 20
    detail_limit_per_minute: int = 30
    detail_limit_per_day: int = 200
    submission_limit_per_day: int = 20
    vote_limit_per_day: int = 200
    report_limit_per_day: int = 20


@lru_cache
def get_settings() -> Settings:
    return Settings()
