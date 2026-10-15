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
2. **Share** — I know the password for a network. I pick its SSID from a
   dropdown of the networks my phone currently scans (SSID/BSSID/security
   prefilled), enter the password, confirm the captured GPS location, and
   submit. It is now searchable by anyone. Manual SSID entry is not
   offered: only networks physically present in the current scan can be
   shared (client-enforced; see §7 "Trust boundary").
3. **Search** — I search by network name, or ask for everything within a
   radius of my location, and get a list with distances.
4. **Map** — I see a map of my surroundings with pins for access points in
   the database. Tapping a pin shows details.
5. **Vote** — after trying a password, I give it a thumbs-up if it worked or
   a thumbs-down if it didn't. Good passwords rise, bad ones sink, so the
   next person is offered the most likely password first.

### Explicit non-goals for MVP

- No silent network joining (impossible since Android 10). The detail screen's
  primary action is **"Add to phone"** via the Wi-Fi suggestion API: the user
  approves the system prompt once per app/network, and the phone then
  auto-connects whenever the network is in range. Copy-to-clipboard remains
  as a fallback (and for devices below Android 10).
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
| No API to join a WPA network silently (since Android 10) | "Add to phone" uses `WifiNetworkSuggestion` (one system-approval prompt, then auto-join in range); copy-to-clipboard fallback |

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
    low_score_since     TIMESTAMPTZ,               -- start of the current negative-score grace period
    status              TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'flagged')),
    submitted_by        UUID REFERENCES users(id) ON DELETE SET NULL,  -- contributor
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ap_passwords_rank
    ON ap_passwords (access_point_id, score DESC, created_at DESC)
    WHERE status = 'active';
CREATE INDEX idx_ap_passwords_low_score_since
    ON ap_passwords (low_score_since) WHERE low_score_since IS NOT NULL;

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
- Delayed permanent cleanup: when a password candidate's score reaches
  **−1 or lower**, persist `low_score_since` using the database wall clock.
  The candidate remains stored and available for voting for a **24-hour grace
  period**. Further negative or repeated votes do not extend that deadline.
  Recovery to **0 or higher** cancels pending deletion; a later fall to −1 or
  lower starts a fresh full 24 hours. This applies to vote switching,
  retraction, duplicate-submission upvotes, and votes removed during account
  deletion. All candidates qualify, including flagged/anonymized entries;
  contributor ownership, candidate age, and manual deletion deadlines do not
  restrict this policy.
  Once the grace period has elapsed and the score remains negative, delete
  the candidate, its encrypted password, votes, and candidate reports. The
  parent access point is deleted only when no candidates remain (including
  flagged candidates). AP-level reports then cascade; audit records retain
  their normal 90-day retention. An API lifespan task checks due candidates
  immediately at startup and every hour, so idle entries are removed
  without another vote. Deletion occurs on the first successful cleanup pass
  after 24 hours (normally within another hour); downtime or lock contention
  can delay cleanup. Timers survive restarts and overdue work is caught up.
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
| POST | `/passwords/{id}/vote` | Set or change my vote, `{value: 1 or -1}`. Upsert; one vote per user per password. Returns final counts and deletion flags; scores ≤ −1 start a 24-hour grace period, with cleanup only after expiry. |
| DELETE | `/passwords/{id}/vote` | Retract my vote. |
| DELETE | `/passwords/{id}` | Delete my own password candidate strictly before its creation time + 15 minutes; expired requests return `403`. |
| DELETE | `/access-points/{id}` | Delete my own access point within its first 15 minutes, only when every remaining candidate is mine and within its own window; candidates cascade. |
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
- Validation: SSID 1–32 bytes; password 8–63 chars (WPA passphrase rules —
  shorter passwords can never be saved to a phone); lat/lon range-checked;
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
  "mine": true,
  "server_time": "2026-10-07T18:05:00Z",
  "can_delete": false,
  "delete_until": null,
  "passwords": [
    { "id": "p-1", "password": "espresso42", "up_votes": 12,
      "down_votes": 1, "score": 11, "my_vote": 1, "mine": true,
      "can_delete": true, "delete_until": "2026-10-07T18:15:00Z" },
    { "id": "p-2", "password": "nero2023", "up_votes": 2,
      "down_votes": 5, "score": -3, "my_vote": null, "mine": false,
      "can_delete": false, "delete_until": null }
  ]
}
```

The detail response carries a per-viewer `mine` flag on the AP and on each
candidate — true when the signed-in user submitted it — without exposing
contributor identity to other viewers. Ownership alone does not permit deletion.

**Contributor deletion window:** existing and new password candidates may be
deleted by their contributor only while database wall-clock time is strictly
earlier than `created_at + 15 minutes`. At the deadline and thereafter the API
returns `403` with "The 15-minute deletion window has ended." Duplicate
submissions, voting, reveals, copying, and saving to a phone never reset the
original creation timestamp. Account deletion still anonymizes contributions;
reporting and operator removal remain available after expiry.

The same rule applies to the access point's creation time. Whole-access-point
deletion additionally requires every remaining candidate (including flagged
candidates) to belong to the requester and remain within its own window.
Foreign-owned or anonymized candidates block deletion; an empty access point
can be deleted within its original window. Submissions and both contributor
deletion paths lock the parent access-point row, and deletion checks use the
database wall clock after lock acquisition to prevent cascade races or stale
transaction timestamps from extending the window.

Detail and both submission responses include `server_time`, and per-viewer
`can_delete` and nullable `delete_until` on the AP and each visible candidate.
Non-owners receive false/null. Owners receive their password's deadline even
after expiry. An AP's deadline is the earliest of its own and all remaining
password deadlines, or null when foreign/anonymized candidates block deletion.
`can_delete` reflects eligibility at `server_time`. Deploy the server before
the Android update; older clients remain subject to the server's checks.

**Vote cleanup response:** `POST /passwords/{id}/vote` keeps its existing
`password_id`, `up_votes`, `down_votes`, `score`, and `my_vote` fields and adds
`password_deleted` and `access_point_deleted` (both default false). The vote
that first drops a score to −1 or below returns `200` with both flags false:
its grace period has just started. A later vote can trigger expired cleanup
and return final counts captured before deletion. Periodic cleanup does not
send a client event; subsequent requests to deleted candidates/networks return
`404`. Retraction retains its `204` response; clients reload detail to observe
removal.

Mutations lock the parent access-point row before reading/modifying candidates,
including voting, submissions, reports, and manual deletion. Counts are
recomputed from votes while holding that lock. Authenticated requests acquire
a shared user key lock first; account deletion acquires an exclusive user lock
and all associated parent locks in UUID order before removing votes. This
prevents new votes escaping account cleanup and serializes cleanup against new
contributions. The periodic task takes the same parent locks in UUID order,
skips locked parents for a later pass, and rechecks the score and timer under
lock. It commits batches of at most 100 parents and immediately drains further
batches; multiple API processes can run safely. Failures roll back the current
batch, are logged, and retry on the next interval. Submission responses are
assembled before releasing the lock.

Alembic revision `0002` is the historical immediate-cleanup migration; data
it already deleted cannot be recovered. Revision `0003` adds the nullable
`low_score_since` timestamp and a partial index for scheduled cleanup,
reconciles counts from stored votes, and starts a full 24-hour grace period
for existing negative scores at migration time. It deletes no candidates.
Both the schema migration and new API code are required. Stop old API workers
before migration; do not run workers with the old immediate-cleanup policy
alongside this version. The existing container startup runs
`alembic upgrade head` before starting the API and its cleanup task. No separate
scheduler is required. `WIFI_PASSWORD_CLEANUP_ENABLED` defaults to true; tests disable the
background task and run cleanup explicitly. Downgrading `0003` removes pending
timers without deleting data; upgrading again grants fresh grace periods.

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
    ├── welcome/             # First-use introduction, replayable from Settings
    ├── auth/                # LoginScreen, RegisterScreen
    ├── nearby/              # NearbyScreen (scan results x DB matches)
    ├── search/              # SearchScreen (name search / radius list)
    ├── share/               # ShareScreen (pick SSID, enter password, consent)
    ├── map/                 # MapScreen (osmdroid)
    ├── settings/            # SettingsScreen (account, logout, support)
    └── detail/              # DetailSheet / DetailScreen (reveal + copy)
```

### Screens and flows

**Welcome & tips:** the first ordinary launch after installation or this
update shows a readable welcome page before sign-in or the restored app
destination. It introduces finding and sharing WiFi passwords, with three
things to try: share known passwords with permission, find shared passwords
when traveling, and vote up working passwords or down nonworking ones.
The page uses the app artwork, three informational icon rows, and a persistent
"Get started" button; text scrolls on small screens and with large font sizes.
No timeout dismisses it. Only tapping "Get started" saves completion in an
installation-level SharedPreferences file; completion survives process restarts,
updates, sign-outs, and account changes. Clearing app data or reinstalling
resets it. Normal destinations, including restored Map/Detail screens, are
gated without constructing their content or ViewModels, so Nearby does not
request location or scan before completion. Bottom navigation stays hidden.
Verification/password-reset links bypass the introduction and keep their
tokens; the introduction remains pending for the next ordinary destination.
Settings → "Welcome & tips" reopens the same page with "Done" and Back
returning to Settings without changing completion. Settings scrolls to keep
its controls accessible on smaller displays. This is an Android-only flow
with no new server API or database changes.

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
   within radius). Refresh whenever the Nearby screen resumes, including
   returning from another tab or bringing the app back to the foreground;
   recheck location permission each time. "Results as of T" label +
   pull-to-refresh (drag down from the top of the list), respecting scan
   throttling. The empty state remains scrollable for pull-to-refresh and
   includes a Refresh button; refresh stays available after an empty result
   or a location/network error.
3. **Share:** pick a scanned network from a dropdown (SSID/BSSID/security
   prefilled; manual entry is not offered) → password field (show/hide) →
   current GPS coordinates shown on an embedded mini-map with a draggable
   pin: when expanded, instructions above the map explain swiping to pan,
   touching and holding the pin before dragging it to the router's position
   (limited to 500 m from the GPS fix), and confirming with "Use this
   location" → required consent checkbox → submit → optimistic update of
   nearby list.
   If the AP already exists (BSSID match) the UI says "added as an
   alternative password"; if the password is identical to an existing
   candidate it becomes an upvote, and the UI says so. (Treat 200 and 201
   the same.)

   **Trust boundary:** the scan-only constraint is enforced by the client
   (the dropdown). The server cannot independently verify that an SSID was
   in the submitter's scan — any API client can POST any SSID. The server
   validates format, records the contributor's coordinates, and relies on
   rate limits, reports/auto-flagging, and the audit log for abuse
   containment. Hidden networks (blank SSID in scan results) cannot be
   selected and therefore cannot be shared in the MVP.
4. **Search:** text query against `GET /access-points/search`, plus a "near me"
   mode using `GET /access-points/near` sorted by distance.
5. **Map:** osmdroid `MapView` with user location + pins from
   `GET /access-points/in-bbox` for the current viewport (debounced ~300 ms
   while panning, hard cap of markers). Tap pin → bottom sheet with summary →
   "View password" fetches detail and offers copy-to-clipboard + a toast
   confirmation.
6. **Detail:** ranked password list (top first; candidates scoring below −3
   collapse behind "show unlikely passwords"). Each row has thumbs-up/down
   with the user's own vote reflected; **"Add to phone"** (Wi-Fi suggestion,
   system approval prompt) is the primary action with "Copy" as fallback;
   after copying, a "Did it work?" prompt drives voting. Row overflow offers
   "Report" with a reason picker. Delete controls for the user's own AP or
   password candidates appear only while deletion is allowed, disappearing
   when eligibility expires or permissions are unavailable. The detail page
   omits deletion-rule explanations; the share screen still discloses the
   window before submission. Server-provided permissions determine deletion
   availability, never device submission history. Missing metadata hides
   deletion controls. Server timestamp differences are anchored to
   Android's monotonic clock at request start, conservatively including network
   time; actions and confirmation buttons expire while open and detail refreshes
   on resume. Failed DELETE responses show the server explanation, preserve the
   password list, and refresh permissions. The "Did it work?" confirmation is also the only
   place the donation card may ever appear (see "Donations" below).

After a vote reports automatic deletion, detail removes the candidate, clears
its submission memory, copied-password state, and open candidate dialogs, and
shows “Password removed after community downvotes.” A deleted network returns
to the previous screen and refreshes Nearby, Search, or Map results. A `404`
when reloading detail follows the same navigation/refresh path. Retraction
reloads detail and clears disappeared candidates. Older clients may show stale
rows until refreshed; server cleanup applies regardless of app version.
Scheduled deletion is observed on the next refresh, without requiring a vote.

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
- Passwords scoring ≤ −1 for a full 24-hour grace period are permanently
  removed by periodic cleanup; recovery to ≥ 0 cancels the timer. Networks
  emptied by this cleanup are removed too.
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
- Tagged Android releases build a signed `bundleRelease`, then run the full
  `testDebugUnitTest` suite before publishing the AAB to GitHub Releases.
  Compose UI tests use the debug-only test activity manifest, which stays
  out of the signed release bundle.
- The repository's `play-console-release` skill guides signed-build validation
  and publication to the existing Internal testing track by default. It verifies
  the artifact's source and Play bundle identity, resumes matching drafts or
  uploaded bundles, and records confirmed availability or pending review.
  Production promotion requires a request for that track.
- Alembic manages all schema changes; never hand-edit the DB.

## 10. Build phases

| Phase | Deliverable | Acceptance criteria |
|---|---|---|
| 0 — Scaffold | Repo skeleton: `server/`, `android/`, docker compose, CI | `docker compose up` serves `/healthz`; app builds; CI green |
| 1 — Server core | Auth (register/verify/login/refresh/lockout/reset/delete), encryption, APs + password candidates, votes/ranking, reports/auto-flag, audit log, geo queries, rate limits, tests | Register→verify→login round-trip; unverified account blocked from reveal/submit/vote/report; refresh rotation invalidates the old token; locked account rejected with uniform 401s; near/bbox/search verified against seeded PostGIS data; password decrypt round-trip; vote upsert adjusts counts; identical resubmission becomes an upvote; 3 reports auto-flag a candidate; audit rows written for reveal/submit/vote/report; tests pass |
| 2 — App core | Auth + verification-deeplink UI, nearby list, share flow, search, detail/copy, voting + report UI | End-to-end on device: register → verify email → scan → share → second account copies the top password → votes up/down and ordering updates → reports an entry |
| 3 — Map | MapScreen with viewport pins + detail sheet; draggable location pin on the share flow | Pins load while panning within bbox; tap → detail → copy; share flow accepts a dragged pin within 500 m of the GPS fix |
| 4 — Polish | Empty states, error handling, permission edge cases, icons, donation prompts (Settings row + capped post-success card → GitHub Sponsors) | UX review pass; airplane/throttled-scan cases handled gracefully; donation caps honored (≤2 asks per lifetime, "Never" respected, nothing gated) |

### Post-MVP backlog

- Offline cache (Room) of recently viewed entries.
- Vote weighting by account reputation/age; score decay over time.
- Admin review UI for flagged candidates and AP reports (MVP: operator SQL).
- Auto-join via `WifiNetworkSpecifier` where the OS allows.
- SSID+proximity dedup for BSSID-less entries.
- Password re-encryption tooling (key rotation in anger).

## 11. Open questions

1. **[Resolved 2026-10-13]** Deployment target — Oracle Cloud Always Free
   VM (this repo's compose stack + Caddy) at `wifidb.clickbeans.com`.
2. **[Resolved 2026-10-13]** Default search radius 500 m (max 5 km); the
   map requires sign-in.
3. **[Resolved 2026-10-13]** SMTP — Brevo transactional relay, sender
   `no-reply@clickbeans.com`.
4. **[Resolved 2026-10-13]** Pin-drag location correction in the share
   flow — yes: embedded mini-map with a draggable pin starting at the GPS
   fix, a "use this location" confirm, and a 500 m drag-radius guard.
   Ships with Phase 3 (osmdroid work). Server change: none.
5. **[Resolved 2026-10-14]** ToS/privacy text — drafted in `docs/PRIVACY.md`
   and `docs/TERMS.md`, served by the API at `/privacy` and `/terms`.
   In-app surfacing of both lands with Phase 4.
6. Vote mechanics: collapse threshold for weak passwords (proposed score −3),
   whether scores decay over time, and whether voting should require having
   opened the password (anti-brigading) — post-MVP tuning.
