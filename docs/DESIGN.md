# WiFi Database — Design Document

A crowd-sourced directory of WiFi access point passwords. Users share the
password and location of networks they have access to; other users search the
database by proximity or name, view results on a map, and copy passwords.

Two deliverables:

1. **Server** — REST API + database (this repo, `server/`)
2. **Android app** (this repo, `android/`)

## 1. Goals and user stories

### Goals

- Let a user see which nearby WiFi networks have a password in the database.
- Let a user share a password (SSID + password + GPS location) to the central database.
- Let a user search the database by network name or by location.
- Let a user see nearby access points as pins on a map.

### User stories (MVP)

1. **Nearby list** — I open the app near a network I want to join. I see the
   networks my phone can currently scan, and the ones with a shared password
   are badged "Password available". I tap one and copy the password.
2. **Share** — I know the password for a network. I pick its SSID from my
   scan results (or type it manually), enter the password, confirm the
   captured GPS location, and submit. It is now searchable by anyone.
3. **Search** — I search by network name, or ask for everything within a
   radius of my location, and get a list with distances.
4. **Map** — I see a map of my surroundings with pins for access points in
   the database. Tapping a pin shows details.

### Explicit non-goals for MVP

- No auto-joining networks (user copies the password and joins manually).
- No offline cache, verification votes, or reporting (post-MVP backlog).
- No background scanning or background location — everything foreground.
- The server is a plain REST API; no web frontend.

## 2. System architecture

```
┌───────────────────────┐            ┌──────────────────────┐        ┌────────────────────────┐
│  Android app         │            │  Server (FastAPI)    │        │  PostgreSQL 16 + PostGIS│
│  Kotlin/Compose       │  HTTPS     │                      │  SQL   │                        │
│  ─────────────────    │ ─────────▶ │  /api/v1/auth        │ ─────▶ │  users                 │
│  - WiFi scan (OS)     │  JWT       │  /api/v1/access-...  │        │  access_points          │
│  - GPS (OS)           │  Bearer    │  Argon2id hashing    │        │  (geometry + encrypted  │
│  - osmdroid map (OSM) │            │  AES-256-GCM secrets │        │   password columns)     │
│  - Retrofit client    │            │  Rate limiting       │        │                        │
└───────────────────────┘            └──────────────────────┘        └────────────────────────┘
```

Key properties:

- All traffic over HTTPS; every API endpoint except auth requires a JWT.
- The app never talks to the database directly; only the API does.
- WiFi passwords are stored **encrypted at rest** (AES-256-GCM with a
  server-held key). Account passwords are **hashed** (Argon2id). These are
  different things with different mechanisms, deliberately.
- Location of an access point is the contributor's GPS fix at share time
  (i.e., an approximation of the router's location). The UI should say so.

## 3. Android platform constraints (read this before building)

These are hard OS constraints that shape the app design:

| Constraint | Implication for the app |
|---|---|
| WiFi scan results require `ACCESS_FINE_LOCATION` granted at runtime | Location permission is requested before scanning, with a rationale dialog; app is unusable without it |
| Android 9+ (API 28) throttles WiFi scans to 4 per 2 minutes in the foreground | Scan on user demand ("Refresh" button), cache `ScanResults` with a timestamp, and show "results as of T" |
| `minSdk = 26` (Android 8.0) chosen | Avoids oldest quirks while covering ~98% of active devices; `targetSdk = 35` |
| SSID/BSSID of the connected network also requires location permission | Same permission gate covers it |
| No API to join a WPA network silently (since Android 10) | MVP copies to clipboard; user joins via OS settings |

## 4. Data model

Two tables for MVP.

```sql
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,      -- stored lowercase
    password_hash TEXT NOT NULL,            -- Argon2id
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE access_points (
    id                 UUID PRIMARY KEY,
    ssid               TEXT NOT NULL,                    -- network name
    bssid              TEXT,                             -- MAC, nullable (manual entries)
    security           TEXT,                             -- 'WPA2', 'WPA3', 'Open', ... (informational)
    password_ciphertext BYTEA NOT NULL,                  -- AES-256-GCM: nonce || ciphertext || tag
    password_key_version INT NOT NULL DEFAULT 1,         -- enables future key rotation
    geom               GEOMETRY(Point, 4326) NOT NULL,    -- PostGIS point (indexed with GiST)
    latitude           DOUBLE PRECISION NOT NULL,        -- convenience mirror of geom
    longitude          DOUBLE PRECISION NOT NULL,
    submitted_by       UUID NOT NULL REFERENCES users(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only one record per physical AP when we know its MAC.
CREATE UNIQUE INDEX uq_access_points_bssid
    ON access_points (bssid) WHERE bssid IS NOT NULL;
CREATE INDEX idx_access_points_geom ON access_points USING GIST (geom);
```

Dedup / merge policy:

- If a new submission carries a `bssid` that already exists, the server
  **updates** that record (newest password wins, `updated_at` bumped) rather
  than creating a duplicate.
- Entries without a BSSID are never auto-merged (two Starbucks guest networks
  are genuinely different hotspots); a future phase may merge on
  SSID + proximity heuristics.

## 5. API design

Conventions: JSON only; `/api/v1` prefix; JWT Bearer auth on everything except
auth endpoints; errors use FastAPI's `{"detail": ...}` shape; distances in
meters.

### Auth

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/v1/auth/register` | `{email, password}` | `201 {id, email}` |
| POST | `/api/v1/auth/login` | `{email, password}` | `200 {access_token, token_type: "bearer"}` |

- JWT HS256, expiry 30 days (MVP simplicity — no refresh tokens; app re-logins
  on 401). Secret from env.
- Password policy: min 8 chars. Email validated by Pydantic.

### Access points

| Method | Path | Purpose |
|---|---|---|
| GET | `/access-points/near?lat&lon&radius_m&limit` | Nearby list (radius search, default radius 500 m, max 5000 m, max limit 100). Returns `distance_m`. |
| GET | `/access-points/in-bbox?min_lat&min_lon&max_lat&max_lon&limit` | Map viewport query (used while panning the map). |
| GET | `/access-points/search?q&limit` | Name search (case-insensitive substring; `pg_trgm` index). |
| GET | `/access-points/{id}` | Full detail — **the only endpoint that returns the password**. |
| POST | `/access-points` | Create/update a shared entry. |
| DELETE | `/access-points/{id}` | Delete own submission (owner only). |

**List/search responses never include the password.** Summary shape:

```json
{
  "id": "9b2f...",
  "ssid": "Cafe Nero Guest",
  "security": "WPA2",
  "latitude": 47.6062,
  "longitude": -122.3321,
  "distance_m": 42,
  "submitted_by": "u-1",
  "created_at": "2026-10-07T18:00:00Z",
  "updated_at": "2026-10-07T18:00:00Z"
}
```

**Create request:**

```json
POST /api/v1/access-points
{
  "ssid": "Cafe Nero Guest",
  "password": "espresso42",
  "bssid": "aa:bb:cc:11:22:33",
  "security": "WPA2",
  "latitude": 47.6062,
  "longitude": -122.3321,
  "consent_confirmed": true
}
```

- `bssid` and `security` optional; `consent_confirmed` must be `true` (the app
  shows a required checkbox: "I confirm I'm allowed to share this network's
  password"). Server rejects otherwise.
- On BSSID conflict: update existing record, return `200` with the updated
  record; otherwise `201`.
- Validation: SSID 1–32 bytes; password 1–63 chars; lat/lon range-checked.

**Rate limits (defaults, tunable):** 60 req/min per IP; 20 submissions per day
per user; detail fetches (the password-revealing endpoint) 30/min per user.

## 6. Server design (`server/`)

Stack: Python 3.12, FastAPI, Uvicorn, SQLAlchemy 2.0 + GeoAlchemy2,
Alembic migrations, Pydantic v2, passlib/argon2id, PyJWT, `cryptography`
(AESGCM), slowapi rate limiting, pytest + httpx. Docker Compose for local dev
(`postgis/postgis:16-3.4` image + API).

```
server/
├── app/
│   ├── main.py              # FastAPI app, router wiring, /healthz
│   ├── config.py            # pydantic-settings: DB URL, JWT secret, AES key, limits
│   ├── database.py          # engine, session factory
│   ├── security.py          # hashing, JWT encode/decode, auth dependencies
│   ├── access_points/
│   │   ├── router.py        # endpoints
│   │   ├── schemas.py       # Pydantic request/response models
│   │   ├── models.py        # SQLAlchemy models
│   │   ├── service.py       # geo queries, dedup/merge, encryption use
│   │   └── crypto.py        # AES-256-GCM seal/open with key_version
│   └── auth/
│       └── router.py        # register/login
├── alembic/                 # migrations (schema above)
├── tests/                   # pytest: auth, CRUD, geo queries, encryption round-trip
├── pyproject.toml
├── Dockerfile
└── docker-compose.yml
```

Notable decisions:

- **Encryption:** AES-256-GCM; the key lives only in server env
  (`WIFI_ENCRYPTION_KEY`, base64 32 bytes). Ciphertext column stores
  `nonce || ciphertext+tag` so the DB alone is not enough to reveal passwords.
  `password_key_version` allows adding a second key later without downtime.
- Passwords are never logged (log filter guards the fields).
- Error mapping: 401 unauthenticated, 403 not-owner, 404 missing, 409 (reserved
  merge conflicts), 422 validation.

## 7. Android app design (`android/`)

Stack: Kotlin, Jetpack Compose + Material 3, Navigation Compose, ViewModel +
Coroutines/Flow, Hilt DI, Retrofit + OkHttp + kotlinx.serialization,
DataStore (auth token persistence), Play Services Location (FusedLocationProvider
for GPS), osmdroid 6.x (map), `WifiManager` scans.

```
android/app/src/main/java/.../
├── data/
│   ├── api/                 # Retrofit service, DTOs, auth interceptor
│   ├── wifi/                # WifiManager scan wrapper (Flow, throttling-aware)
│   └── location/           # FusedLocationProvider wrapper
├── di/                      # Hilt modules
├── domain/                  # models (AccessPoint, ScanResult ...), repositories
└── ui/
    ├── auth/                # LoginScreen, RegisterScreen
    ├── nearby/              # NearbyScreen (scan results x DB matches)
    ├── search/              # SearchScreen (name search / radius list)
    ├── share/               # ShareScreen (pick SSID, enter password, consent)
    ├── map/                 # MapScreen (osmdroid)
    └── detail/              # DetailSheet / DetailScreen (reveal + copy)
```

### Screens and flows

1. **Auth gate:** Login / Register. Token stored in DataStore; a 401 anywhere
   clears it and returns here.
2. **Nearby (home):** on open — request location permission (rationale first),
   request GPS fix, trigger one WiFi scan. Cross-reference the two sources:
   each scanned SSID is matched against `GET /access-points/near` results and
   badged "Password available" when a DB entry exists (match by BSSID when the
   scan provides one, else by SSID within radius). "Results as of T" label +
   refresh button, respecting scan throttling.
3. **Share:** pick a scanned network (SSID/BSSID/security prefilled) or manual
   entry → password field (show/hide) → current GPS coordinates displayed and
   editable-by-map-pin optional → required consent checkbox → submit →
   optimistic update of nearby list. Handle the BSSID-update case (200) the
   same as creation (201).
4. **Search:** text query against `GET /access-points/search`, plus a "near me"
   mode using `GET /access-points/near` sorted by distance.
5. **Map:** osmdroid `MapView` with user location + pins from
   `GET /access-points/in-bbox` for the current viewport (debounced ~300 ms
   while panning, hard cap of markers). Tap pin → bottom sheet with summary →
   "View password" fetches detail and offers copy-to-clipboard + a toast
   confirmation.
6. **Detail:** SSID, security, distance, submitted date, password hidden until
   a copy action, delete button for own submissions.

### Repository pattern

`AuthRepository`, `WifiRepository` (system scans), `AccessPointRepository`
(API). UI never touches WifiManager/Retrofit directly.

## 8. Security and privacy summary

- TLS everywhere (reverse proxy in production terminates it).
- Account passwords: Argon2id hashes. WiFi passwords: AES-256-GCM at rest.
- Password fields excluded from all logs; detail endpoint rate-limited and
  authenticated.
- Consent checkbox required per submission; contributor identity stored for
  accountability and "delete own entry" support.
- Input validation on both client and server (lengths, lat/lon bounds, MAC
  format).
- Share screen copy warns that submitted passwords become public.

## 9. Repo layout and tooling

```
/  (monorepo)
├── docs/          # this document and future specs
├── server/        # FastAPI app (see §6)
├── android/       # Gradle project (see §7)
└── README.md      # quickstart for both
```

- Local dev: `docker compose up` (PostGIS + API), `gradle installDebug` for app.
- CI (GitHub Actions): server runs pytest against a PostGIS service
  container; android runs `assembleDebug` + unit tests.
- Alembic manages all schema changes; never hand-edit the DB.

## 10. Build phases

| Phase | Deliverable | Acceptance criteria |
|---|---|---|
| 0 — Scaffold | Repo skeleton: `server/`, `android/`, docker compose, CI | `docker compose up` serves `/healthz`; app builds; CI green |
| 1 — Server core | Auth, encryption, CRUD + geo queries, rate limits, tests | Register/login round-trip; near/bbox/search verified against seeded PostGIS data; password decrypt round-trip; tests pass |
| 2 — App core | Auth UI, nearby list, share flow, search, detail/copy | End-to-end on device: register → scan → share → another account sees password and copies it |
| 3 — Map | MapScreen with viewport pins + detail sheet | Pins load while panning within bbox; tap → detail → copy |
| 4 — Polish | Empty states, error handling, permission edge cases, icons | UX review pass; airplane/throttled-scan cases handled gracefully |

### Post-MVP backlog

- Verification votes ("still works") with counters.
- Offline cache (Room) of recently viewed entries.
- Report/flag endpoint + auto-hide threshold + admin review.
- Auto-join via `WifiNetworkSpecifier` where the OS allows.
- SSID+proximity dedup for BSSID-less entries.
- Password re-encryption tooling (key rotation in anger).

## 11. Open questions

1. Deployment target for the server (VPS with docker compose vs. a managed
   host) — affects TLS setup and secret storage.
2. Default search radius (proposed 500 m) and whether the map shows only
   authenticated results or requires sign-in first (currently: sign-in first).
3. Email verification / password reset flows — skipped in MVP; do we need them
   before launch given contributor accountability?
4. Whether the share flow should let users drag the pin to correct the AP
   location (cheap to add since the map exists; proposed yes).
5. ToS/privacy policy text for the consent step.
