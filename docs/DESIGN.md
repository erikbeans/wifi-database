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
- Let a user mark shared passwords as working or not, so the most reliable
  password for each network is presented first.

### User stories (MVP)

1. **Nearby list** — I open the app near a network I want to join. I see the
   networks my phone can currently scan, and the ones with a shared password
   are badged "Password available". I tap one and copy the top-ranked password.
2. **Share** — I know the password for a network. I pick its SSID from my
   scan results (or type it manually), enter the password, confirm the
   captured GPS location, and submit. It is now searchable by anyone.
3. **Search** — I search by network name, or ask for everything within a
   radius of my location, and get a list with distances.
4. **Map** — I see a map of my surroundings with pins for access points in
   the database. Tapping a pin shows details.
5. **Vote** — after trying a password, I give it a thumbs-up if it worked or
   a thumbs-down if it didn't. Good passwords rise, bad ones sink, so the
   next person is offered the most likely password first.

### Explicit non-goals for MVP

- No auto-joining networks (user copies the password and joins manually).
- No offline cache in MVP (post-MVP backlog); password voting, ranking, and
  abuse reporting are in MVP scope (§4, §5).
- No background scanning or background location — everything foreground.
- The server is a plain REST API; no web frontend.
- No paywalls or paid features. The only monetization is an optional,
  hard-capped donation prompt (§7, "Donations"); donations never unlock
  functionality.

## 2. System architecture

```
┌───────────────────────┐            ┌──────────────────────┐        ┌────────────────────────┐
│  Android app         │            │  Server (FastAPI)    │        │  PostgreSQL 16 + PostGIS│
│  Kotlin/Compose       │  HTTPS     │                      │  SQL   │                        │
│  ─────────────────    │ ─────────▶ │  /api/v1/auth        │ ─────▶ │  users                 │
│  - WiFi scan (OS)     │  JWT       │  /api/v1/access-...  │        │  access_points          │
│  - GPS (OS)           │  Bearer    │  Argon2id hashing    │        │  (geometry; passwords &  │
│  - osmdroid map (OSM) │            │  AES-256-GCM secrets │        │   votes in child tables)│
│  - Retrofit client    │            │  Rate limiting       │        │                        │
└───────────────────────┘            └──────────────────────┘        └────────────────────────┘
```

Key properties:

- All traffic over HTTPS; every API endpoint except auth requires a JWT;
  password reveals, submissions, votes, and reports additionally require a
  verified email address.
- The app never talks to the database directly; only the API does.
- WiFi passwords are stored **encrypted at rest** (AES-256-GCM with a
  server-held key). Account passwords are **hashed** (Argon2id). These are
  different things with different mechanisms, deliberately.
- Location of an access point is the contributor's GPS fix at share time
  (i.e., an approximation of the router's location). The UI should say so.
- Passwords are community-ranked, never overwritten: each contributed
  password is a candidate, and up/down votes decide which is offered first
  (§4, §5).

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

Seven tables: users, auth tokens (refresh/verify/reset), access points (the
physical network), password candidates (one per contributed password), votes,
reports, and an append-only audit log.

```sql
CREATE TABLE users (
    id                 UUID PRIMARY KEY,
    email              TEXT NOT NULL UNIQUE,      -- stored lowercase
    password_hash      TEXT NOT NULL,             -- Argon2id
    email_verified_at  TIMESTAMPTZ,               -- NULL until email verified
    failed_login_count INTEGER NOT NULL DEFAULT 0,
    locked_until       TIMESTAMPTZ,               -- set after repeated failures
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE auth_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind       TEXT NOT NULL CHECK (kind IN ('refresh', 'verify', 'reset')),
    token_hash TEXT NOT NULL UNIQUE,             -- SHA-256 of raw token; raw never stored
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,                       -- set on first use (single-use)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE access_points (
    id           UUID PRIMARY KEY,
    ssid         TEXT NOT NULL,                     -- network name
    bssid        TEXT,                              -- MAC, nullable (manual entries)
    security     TEXT,                              -- 'WPA2', 'WPA3', 'Open', ... (informational)
    geom         GEOMETRY(Point, 4326) NOT NULL,     -- PostGIS point (indexed with GiST)
    latitude     DOUBLE PRECISION NOT NULL,          -- convenience mirror of geom
    longitude    DOUBLE PRECISION NOT NULL,
    submitted_by UUID REFERENCES users(id) ON DELETE SET NULL,  -- creator; anonymized if account deleted
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ap_passwords (
    id                  UUID PRIMARY KEY,
    access_point_id     UUID NOT NULL REFERENCES access_points(id) ON DELETE CASCADE,
    password_ciphertext BYTEA NOT NULL,             -- AES-256-GCM: nonce || ciphertext || tag
    password_key_version INT NOT NULL DEFAULT 1,    -- enables future key rotation
    up_votes            INTEGER NOT NULL DEFAULT 0, -- denormalized for cheap ranking;
    down_votes          INTEGER NOT NULL DEFAULT 0, -- recomputed from password_votes on change
    score               INTEGER GENERATED ALWAYS AS (up_votes - down_votes) STORED,
    status              TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'flagged')),
    submitted_by        UUID REFERENCES users(id) ON DELETE SET NULL,  -- contributor
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ap_passwords_rank
    ON ap_passwords (access_point_id, score DESC, created_at DESC)
    WHERE status = 'active';

CREATE TABLE password_votes (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- votes die with the account
    password_id UUID NOT NULL REFERENCES ap_passwords(id) ON DELETE CASCADE,
    value       SMALLINT NOT NULL CHECK (value IN (-1, 1)),  -- 1 worked, -1 didn't
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, password_id)             -- one vote per user; changeable
);

-- Only one record per physical AP when we know its MAC.
CREATE UNIQUE INDEX uq_access_points_bssid
    ON access_points (bssid) WHERE bssid IS NOT NULL;
CREATE INDEX idx_access_points_geom ON access_points USING GIST (geom);

CREATE TABLE reports (                            -- abuse reporting (MVP)
    id          UUID PRIMARY KEY,
    reporter_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ap_id       UUID REFERENCES access_points(id) ON DELETE CASCADE,
    password_id UUID REFERENCES ap_passwords(id) ON DELETE CASCADE,
    reason      TEXT NOT NULL,                    -- 'wrong', 'abusive', 'not_mine_to_share', ...
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ap_id IS NOT NULL OR password_id IS NOT NULL)
);

CREATE TABLE audit_events (                       -- append-only; abuse investigation only
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    event_type TEXT NOT NULL,                     -- 'password_view' | 'submission' | 'vote' | 'report'
    target_id  UUID,                              -- ap or password id
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Candidates, ranking, and dedup:

- A physical access point is one `access_points` row (keyed by BSSID when
  known). Every contributed password is its own `ap_passwords` candidate —
  passwords are **never overwritten**; a new submission for a known BSSID
  becomes an additional candidate.
- If a submission is byte-identical to an existing candidate for that AP
  (server decrypts that AP's handful of candidates to compare), no duplicate
  is stored — it counts as an automatic upvote on the existing candidate.
- Ranking: `score` = upvotes − downvotes; candidates sort by score desc, then
  recency. The app offers the top candidate first and collapses candidates
  scoring below −3 behind a "show unlikely passwords" toggle. Candidates
  with 3+ reports are `flagged` and excluded from the ranked list pending
  review.
- Entries without a BSSID are never auto-merged (two Starbucks guest networks
  are genuinely different hotspots); a future phase may merge on
  SSID + proximity heuristics.

## 5. API design

Conventions: JSON only; `/api/v1` prefix; JWT Bearer auth on everything except
auth endpoints; password-reveal, submission, vote, and report endpoints also
require a verified email; errors use FastAPI's `{"detail": ...}` shape;
distances in meters.

### Auth

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/v1/auth/register` | `{email, password}` | `201 {message: "verification email sent"}` — identical whether or not the email is already registered |
| POST | `/api/v1/auth/verify` | `{token}` (from email deep link) | `200 {access_token, refresh_token}` |
| POST | `/api/v1/auth/resend-verification` | `{email}` | `202` (generic) |
| POST | `/api/v1/auth/login` | `{email, password}` | `200 {access_token, refresh_token}` |
| POST | `/api/v1/auth/refresh` | `{refresh_token}` | `200 {access_token, refresh_token}` — rotation: the old refresh token is invalidated |
| POST | `/api/v1/auth/logout` | `{refresh_token}` | `204` — revokes that refresh token |
| POST | `/api/v1/auth/password-reset-request` | `{email}` | `202` (generic) |
| POST | `/api/v1/auth/password-reset` | `{token, new_password}` | `204` — revokes all refresh tokens |
| DELETE | `/api/v1/auth/me` | — | `204` — deletes the account; contributions are anonymized, not destroyed |

- Tokens: short-lived access JWT (HS256, 15 min) + opaque 256-bit refresh
  token (30 days). Refresh/verify/reset tokens are stored only as SHA-256
  hashes in `auth_tokens`, are single-use (refresh rotates on every use —
  reuse of an already-rotated token revokes the whole family, which is how
  theft is detected), and are revocable on logout and password change/reset.
  A stolen access token dies in minutes.
- Login hardening: uniform 401 "invalid credentials" for wrong email and
  wrong password alike; 5 consecutive failures lock the account for 15 min,
  doubling up to 24 h (`users.locked_until`). Verify/reset emails are
  single-use, 24 h, deep-linked back into the app.
- Verification gates: an unverified account may browse lists, but cannot
  reveal passwords, submit, vote, or report (403, `reason: "email_unverified"`).
- Password policy: min 8 chars with Argon2id + lockout. Email validated by
  Pydantic.

### Access points

| Method | Path | Purpose |
|---|---|---|
| GET | `/access-points/near?lat&lon&radius_m&limit` | Nearby list (radius search, default radius 500 m, max 5000 m, max limit 100). Returns `distance_m`. |
| GET | `/access-points/in-bbox?min_lat&min_lon&max_lat&max_lon&limit` | Map viewport query (used while panning the map). Bbox clamped to 2° per side (~200 km); max limit 100. |
| GET | `/access-points/search?q&limit` | Name search (case-insensitive substring; `pg_trgm` index). |
| GET | `/access-points/{id}` | Full detail — **the only endpoint that returns passwords** (ranked candidate list). |
| POST | `/access-points` | Create an access point (or match it by BSSID) and add the password as its first candidate. |
| POST | `/access-points/{ap_id}/passwords` | Add another password candidate to an existing access point. |
| POST | `/passwords/{id}/vote` | Set or change my vote, `{value: 1 or -1}`. Upsert; one vote per user per password. |
| DELETE | `/passwords/{id}/vote` | Retract my vote. |
| DELETE | `/passwords/{id}` | Delete my own password candidate. |
| DELETE | `/access-points/{id}` | Delete my own access point (owner only; candidates cascade). |
| POST | `/reports` | Report a wrong or abusive entry: `{ap_id or password_id, reason}`. 3+ reports on a candidate auto-flags it out of the ranked list; AP-level reports queue for review. |

**List/search responses never include passwords or contributor IDs.** Summary shape:

```json
{
  "id": "9b2f...",
  "ssid": "Cafe Nero Guest",
  "security": "WPA2",
  "latitude": 47.6062,
  "longitude": -122.3321,
  "distance_m": 42,
  "password_count": 2,
  "best_score": 11,
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
  password"). Server rejects otherwise. Same consent applies to adding
  candidates via `/access-points/{ap_id}/passwords`.
- On BSSID match with an existing AP: **no overwrite** — the password is added
  as a new candidate, unless byte-identical to an existing candidate, in
  which case it becomes an automatic upvote on that candidate. Return `200`
  with the AP plus a `duplicate_password` flag; new APs return `201`.
- Validation: SSID 1–32 bytes; password 1–63 chars; lat/lon range-checked;
  bbox clamped to 2° per side; search queries ≤ 64 chars; every list endpoint
  capped at limit 100.

**Detail response** (the only place passwords appear; sorted by score desc,
then recency; `my_vote` is the caller's current vote):

```json
{
  "id": "9b2f...",
  "ssid": "Cafe Nero Guest",
  "security": "WPA2",
  "latitude": 47.6062,
  "longitude": -122.3321,
  "passwords": [
    { "id": "p-1", "password": "espresso42", "up_votes": 12,
      "down_votes": 1, "score": 11, "my_vote": 1 },
    { "id": "p-2", "password": "nero2023", "up_votes": 2,
      "down_votes": 5, "score": -3, "my_vote": null }
  ]
}
```

**Rate limits (defaults, tunable):** 60 req/min per IP; 20 submissions per day
per user; 200 votes/day per user; 20 reports/day per user; detail fetches
(the password-revealing endpoint) 30/min and 200/day per account; 10 auth
emails/day per address. Unverified accounts get no detail/submission/vote
quota at all — they are blocked, not throttled.

## 6. Server design (`server/`)

Stack: Python 3.12, FastAPI, Uvicorn, SQLAlchemy 2.0 + GeoAlchemy2,
Alembic migrations, Pydantic v2, passlib/argon2id, PyJWT, `cryptography`
(AESGCM), slowapi rate limiting, transactional SMTP for email
(verification/reset), pytest + httpx. Docker Compose for local dev
(`postgis/postgis:16-3.4` image + API; a MailHog container catches dev email).

```
server/
├── app/
│   ├── main.py              # FastAPI app, router wiring, /healthz
│   ├── config.py            # pydantic-settings: DB URL, JWT secret, AES key, SMTP, limits
│   ├── database.py          # engine, session factory
│   ├── security.py          # hashing, JWT, token store, login lockout, auth dependencies
│   ├── audit.py            # append-only audit events (no secrets, 90-day retention)
│   ├── access_points/
│   │   ├── router.py        # endpoints (APs, password candidates, votes, reports)
│   │   ├── schemas.py       # Pydantic request/response models
│   │   ├── models.py        # SQLAlchemy models
│   │   ├── service.py       # geo queries, candidate dedup, ranking, votes
│   │   ├── moderation.py    # report intake, auto-flagging at 3+ reports
│   │   └── crypto.py        # AES-256-GCM seal/open with key_version
│   └── auth/
│       └── router.py        # register/verify/login/refresh/logout/reset/delete-account
├── alembic/                 # migrations (schema above)
├── tests/                   # pytest: auth, CRUD, geo queries, votes/ranking, encryption round-trip
├── pyproject.toml
├── Dockerfile
└── docker-compose.yml
```

Notable decisions:

- **Encryption:** AES-256-GCM; the key lives only in server env
  (`WIFI_ENCRYPTION_KEY`, base64 32 bytes). Every seal uses a fresh random
  96-bit nonce (`os.urandom(12)`) prepended to the ciphertext — nonce reuse
  is catastrophic in GCM, so this is a hard rule in `crypto.py`. The DB alone
  cannot reveal passwords. Key rotation: add a new key, flip the default,
  batch-re-encrypt by `password_key_version`.
- **Tokens:** access JWT 15 min; refresh/verify/reset tokens are opaque
  256-bit values stored only as SHA-256 hashes, single-use, expiring 24 h
  (verify/reset) or 30 days (refresh).
- **Email:** transactional SMTP only — verification, resets, and "someone
  tried to register with your address" notices. No other email, ever.
- **CORS is disabled.** The API serves native apps only, so there are no
  browser origins to allow; combined with Bearer tokens (no cookies) there is
  no CSRF surface. HSTS and standard security headers are set at the proxy.
- Passwords are never logged (log filter guards the fields). Audit events
  (`password_view`, `submission`, `vote`, `report`) hold user and target IDs
  only and are purged after 90 days.
- Error mapping: 401 unauthenticated, 403 not-owner-or-unverified, 404
  missing, 409 (reserved), 422 validation.

## 7. Android app design (`android/`)

Stack: Kotlin, Jetpack Compose + Material 3, Navigation Compose, ViewModel +
Coroutines/Flow, Hilt DI, Retrofit + OkHttp + kotlinx.serialization,
Android Keystore-backed encrypted storage for tokens (access token is
memory-only; refresh token encrypted at rest), Play Services Location
(FusedLocationProvider for GPS), osmdroid 6.x (map), `WifiManager` scans.

```
android/app/src/main/java/.../
├── data/
│   ├── api/                 # Retrofit service, DTOs, auth interceptor, token-refresh authenticator
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
    ├── settings/            # SettingsScreen (account, logout, support)
    └── detail/              # DetailSheet / DetailScreen (reveal + copy)
```

### Screens and flows

1. **Auth gate:** Login / Register → "check your email" screen; the
   verification deep link lands back in the app and stores tokens
   (Keystore-encrypted). A 401 triggers silent refresh via the OkHttp
   `Authenticator`; a failed refresh clears tokens and returns here. Logout
   revokes the refresh token.
2. **Nearby (home):** on open — request location permission (rationale first),
   request GPS fix, trigger one WiFi scan. Cross-reference the two sources:
   each scanned SSID is matched against `GET /access-points/near` results and
   badged "Password available" with the top candidate's score when a DB
   entry exists (match by BSSID when the scan provides one, else by SSID
   within radius). "Results as of T" label +
   refresh button, respecting scan throttling.
3. **Share:** pick a scanned network (SSID/BSSID/security prefilled) or manual
   entry → password field (show/hide) → current GPS coordinates displayed and
   editable-by-map-pin optional → required consent checkbox → submit →
   optimistic update of nearby list. If the AP already exists (BSSID match)
   the UI says "added as an alternative password"; if the password is
   identical to an existing candidate it becomes an upvote, and the UI says
   so. (Treat 200 and 201 the same.)
4. **Search:** text query against `GET /access-points/search`, plus a "near me"
   mode using `GET /access-points/near` sorted by distance.
5. **Map:** osmdroid `MapView` with user location + pins from
   `GET /access-points/in-bbox` for the current viewport (debounced ~300 ms
   while panning, hard cap of markers). Tap pin → bottom sheet with summary →
   "View password" fetches detail and offers copy-to-clipboard + a toast
   confirmation.
6. **Detail:** ranked password list (top first; candidates scoring below −3
   collapse behind "show unlikely passwords"). Each row has thumbs-up/down
   with the user's own vote reflected; "Copy" defaults to the selected row;
   after copying, a "Did it work?" prompt drives voting. Row overflow offers
   "Report" with a reason picker. Delete buttons for the user's own AP or
   password candidates. The "Did it work?" confirmation is also the only
   place the donation card may ever appear (see "Donations" below).

### Donations

Optional, deliberately subtle support for development costs, via GitHub
Sponsors (`https://github.com/sponsors/erikbeans` — 0% platform fee,
one-time or monthly, cards handled by GitHub/Stripe). Tap opens the
sponsors page in the system browser — the app never touches payment data.

- **Always available:** a "Support development" row in Settings (heart
  icon). Silent, discoverable, never pushed.
- **Contextual ask, hard-capped:** after the user confirms a password
  worked ("Did it work?" → Yes), an inline dismissible card may appear —
  "Glad it worked! If you'd like to support WiFi Database →" with
  **Not now** and **Never**. Shown at most **2 times per account
  lifetime**, never before the 3rd confirmed-working password. "Never"
  suppresses it permanently; "Not now" defers at least 10 further
  confirmed-working passwords.
- **Never:** modals, splash/auth placement, repeat drives, or anything
  that blocks a task.
- **Zero server involvement:** no donation state in the backend, no
  accounts tied, nothing unlocked. Ask state (counter + never flag) is
  local-only on the device. Label everything "support development," not
  charity.

### Repository pattern

`AuthRepository`, `WifiRepository` (system scans), `AccessPointRepository`
(API). UI never touches WifiManager/Retrofit directly.

## 8. Security and privacy summary

- TLS everywhere (reverse proxy in production terminates it); CORS disabled;
  Bearer tokens only (no cookies), so no CSRF surface.
- Account passwords: Argon2id hashes. WiFi passwords: AES-256-GCM at rest
  with a fresh random nonce per seal.
- Short-lived access JWTs (15 min) + hashed, rotated, revocable refresh
  tokens; password reset revokes all sessions.
- Verified email required to reveal, submit, vote, or report — the primary
  control against mass-scraping and sybil voting. Login lockout, uniform
  auth errors, and enumeration-safe registration.
- Password fields excluded from all logs; the detail endpoint is
  authenticated, verified, rate-limited, and written to an append-only audit
  log (90-day retention).
- Consent checkbox required per submission; contributor identity stored
  internally for accountability and "delete own entry" support — but never
  exposed in API responses.
- Votes: one per user per password (primary-key enforced), changeable and
  retractable, rate-capped per day; identical resubmissions fold into upvotes
  so bad actors cannot flood an AP with duplicate candidates.
- Input validation on both client and server (lengths, lat/lon bounds, MAC
  format).
- Share screen copy warns that submitted passwords become public.
- Donations are fully external (GitHub Sponsors in the system browser):
  no payment data, no donation records, and no donation-related API
  endpoints exist.

## 9. Repo layout and tooling

```
/  (monorepo)
├── docs/          # this document and future specs
├── server/        # FastAPI app (see §6)
├── android/       # Gradle project (see §7)
└── README.md      # quickstart for both
```

- Local dev: `docker compose up` (PostGIS + API + MailHog), `gradle installDebug` for app.
- CI (GitHub Actions): server runs pytest against a PostGIS service
  container; android runs `assembleDebug` + unit tests.
- Alembic manages all schema changes; never hand-edit the DB.

## 10. Build phases

| Phase | Deliverable | Acceptance criteria |
|---|---|---|
| 0 — Scaffold | Repo skeleton: `server/`, `android/`, docker compose, CI | `docker compose up` serves `/healthz`; app builds; CI green |
| 1 — Server core | Auth (register/verify/login/refresh/lockout/reset/delete), encryption, APs + password candidates, votes/ranking, reports/auto-flag, audit log, geo queries, rate limits, tests | Register→verify→login round-trip; unverified account blocked from reveal/submit/vote/report; refresh rotation invalidates the old token; locked account rejected with uniform 401s; near/bbox/search verified against seeded PostGIS data; password decrypt round-trip; vote upsert adjusts counts; identical resubmission becomes an upvote; 3 reports auto-flag a candidate; audit rows written for reveal/submit/vote/report; tests pass |
| 2 — App core | Auth + verification-deeplink UI, nearby list, share flow, search, detail/copy, voting + report UI | End-to-end on device: register → verify email → scan → share → second account copies the top password → votes up/down and ordering updates → reports an entry |
| 3 — Map | MapScreen with viewport pins + detail sheet | Pins load while panning within bbox; tap → detail → copy |
| 4 — Polish | Empty states, error handling, permission edge cases, icons, donation prompts (Settings row + capped post-success card → GitHub Sponsors) | UX review pass; airplane/throttled-scan cases handled gracefully; donation caps honored (≤2 asks per lifetime, "Never" respected, nothing gated) |

### Post-MVP backlog

- Offline cache (Room) of recently viewed entries.
- Vote weighting by account reputation/age; score decay over time.
- Auto-archive access points whose candidates are all deeply negative.
- Admin review UI for flagged candidates and AP reports (MVP: operator SQL).
- Auto-join via `WifiNetworkSpecifier` where the OS allows.
- SSID+proximity dedup for BSSID-less entries.
- Password re-encryption tooling (key rotation in anger).

## 11. Open questions

1. Deployment target for the server (VPS with docker compose vs. a managed
   host) — affects TLS setup and secret storage.
2. Default search radius (proposed 500 m) and whether the map shows only
   authenticated results or requires sign-in first (currently: sign-in first).
3. SMTP provider and sender domain for verification/reset email (the security
   review pulled these flows into MVP).
4. Whether the share flow should let users drag the pin to correct the AP
   location (cheap to add since the map exists; proposed yes).
5. ToS/privacy policy text for the consent step.
6. Vote mechanics: collapse threshold for weak passwords (proposed score −3),
   whether scores decay over time, and whether voting should require having
   opened the password (anti-brigading).
