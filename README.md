# WiFi Database

Crowd-sourced directory of WiFi access-point passwords: an Android app for
scanning nearby networks, sharing SSID + password + location, searching,
mapping, and voting on password reliability — backed by a FastAPI/PostGIS
REST API.

The authoritative spec is [docs/DESIGN.md](docs/DESIGN.md).

## Layout

- `server/` — FastAPI + SQLAlchemy 2.0 + PostGIS REST API
- `android/` — Kotlin / Jetpack Compose app
- `docs/` — design documents

## Server

Local development via Docker Compose (PostGIS + API + MailHog):

```sh
cd server
docker compose up --build
```

- API: http://localhost:8000 (liveness at `/healthz`, OpenAPI docs at `/docs`)
- MailHog UI: http://localhost:8025 (catches all dev email)
- PostGIS: localhost:5432, user/db `wifi`, password `wifi`

Copy `server/.env.example` to `server/.env` and set `WIFI_JWT_SECRET` and
`WIFI_ENCRYPTION_KEY` (base64 of 32 random bytes: `openssl rand -base64 32`).

Run the tests (Python 3.12):

```sh
cd server
python3.12 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/pytest
```

Schema changes go through Alembic (`alembic/`); never hand-edit the database.

## Production

`server/docker-compose.prod.yml` runs PostGIS (no published ports) + API +
Caddy (automatic Let's Encrypt TLS, security headers). Deploy:

```sh
git clone <repo> /opt/wifi-database
cd /opt/wifi-database/server
cat > .env <<EOF
WIFI_PUBLIC_HOST=<your hostname>
WIFI_DB_USER=wifi
WIFI_DB_PASSWORD=$(openssl rand -hex 16)
WIFI_DB_NAME=wifi
WIFI_JWT_SECRET=$(openssl rand -hex 32)
WIFI_ENCRYPTION_KEY=$(openssl rand -base64 32)
EOF
chmod 600 .env
docker compose -f docker-compose.prod.yml up -d --build
```

Open 80/443 (tcp + udp 443 for HTTP/3) in the host firewall and any
cloud security groups.

## Android

Build (JDK 17 and an Android SDK with platform 35 required; Android Studio
handles both):

```sh
cd android
./gradlew assembleDebug
```

The debug APK lands in `android/app/build/outputs/apk/debug/`.
Unit tests: `./gradlew testDebugUnitTest`.

## CI (GitHub Actions)

- `.github/workflows/server.yml` — pytest against a PostGIS service
  container, plus a compose smoke test (`docker compose up` → `/healthz`).
- `.github/workflows/android.yml` — `assembleDebug` + unit tests, APK
  uploaded as an artifact.
