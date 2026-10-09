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

    cors_origins: list[str] = []


@lru_cache
def get_settings() -> Settings:
    return Settings()
