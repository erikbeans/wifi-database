# WiFi Database — Session Context & Handoff

Written 2026-10-08 to carry state into a new CLI session. The authoritative
project spec is **`docs/DESIGN.md`** — this file summarizes how we got there
and what's next. Delete or archive once absorbed.

## Project

Crowd-sourced WiFi access-point password directory:

- **Android app** (Kotlin/Compose): scan nearby networks, share
  SSID + password + GPS location, search by name/proximity, view APs on a
  map, vote on password reliability.
- **Server** (REST API + database): stores encrypted passwords, serves
  ranked candidates by votes.

Monorepo layout (planned): `server/`, `android/`, `docs/`. Only `docs/` exists
so far — **no code has been written yet; the next phase is scaffolding.**

## Completed in this session

1. **Design document** (`docs/DESIGN.md`) — full architecture, Android
   platform constraints, 7-table data model, complete API spec, security
   posture, and a 5-phase build plan with acceptance criteria.
2. **Security design review** — 12 findings (1 High, 5 Medium, 6 Low);
   **all 12 are addressed in the doc** (see "Security decisions" below).
3. **Password voting/ranking feature** added to the design after the initial
   review: multiple password candidates per AP, up/down votes, ranked
   presentation, byte-identical resubmissions become upvotes, 3 reports
   auto-flag a candidate.
4. **Published to GitHub** (private): https://github.com/erikbeans/wifi-database

## Key technical decisions (all in DESIGN.md)

- **Server:** Python 3.12, FastAPI, Uvicorn, SQLAlchemy 2.0 + GeoAlchemy2,
  PostgreSQL 16 + PostGIS, Alembic, Pydantic v2, passlib/Argon2id, PyJWT,
  `cryptography` (AES-256-GCM), slowapi, transactional SMTP (email
  verification/reset), pytest + httpx. Docker Compose: PostGIS + API +
  MailHog for local dev.
- **Android:** Kotlin, Jetpack Compose + Material 3, Navigation Compose,
  ViewModel/Flow, Hilt, Retrofit + OkHttp + kotlinx.serialization,
  Keystore-encrypted token storage, Play Services Location, osmdroid (OSM,
  free, no API key), WifiManager scans. minSdk 26, targetSdk 35. Key OS
  constraints: fine-location permission gates scans; Android 9+ throttles
  scans to 4 per 2 min (scan on demand, cache + timestamp the results).
- **Auth:** email + password (no Google login). 15-min access JWT + opaque
  256-bit refresh tokens (SHA-256-hashed in DB, single-use rotation, family
  revocation on reuse, revoked on logout/password reset). 5-strike
  exponential login lockout, uniform 401s, enumeration-safe registration.
  **Verified email required to reveal passwords, submit, vote, or report.**
- **Data model (7 tables):** `users`, `auth_tokens`, `access_points`,
  `ap_passwords`, `password_votes`, `reports`, `audit_events`. Passwords are
  never overwritten — each contribution is a candidate ranked by
  `score = up_votes − down_votes`; candidates scoring below −3 collapse
  behind "show unlikely passwords".
- **Security posture:** WiFi passwords AES-256-GCM encrypted at rest (fresh
  96-bit nonce per seal); account passwords Argon2id; contributor IDs never
  exposed in API responses; detail endpoint rate-limited (30/min, 200/day
  per account) and audit-logged (90-day retention); CORS disabled (native
  app only, no cookies/CSRF); consent checkbox required on every submission;
  bbox clamped to 2° per side; account deletion anonymizes contributions
  (ON DELETE SET NULL) and cascades votes.

## Git / GitHub state

- Remote: `origin` → https://github.com/erikbeans/wifi-database (private,
  owned by `erikbeans`).
- `main` history (all pushed, working tree clean):
  - `b929de7` Add project design document
  - `0660caf` Add password voting and ranked candidates to design
  - `b609af9` Address security review findings in design
- `gh` CLI is authenticated as **erikbeans** (active) with a second account
  `ebeans-fqt` also present — use erikbeans for this repo.
- Git identity was auto-configured as `Erik Beans <erikbeans@mac.lan>`
  (hostname is `mac.lan`). Set `user.name`/`user.email` properly if a real
  GitHub-noreply email is wanted on commits.

## Next steps (DESIGN.md §10)

1. **Phase 0 — Scaffold:** `server/` skeleton + `android/` Gradle skeleton +
   docker-compose (PostGIS + API + MailHog) + GitHub Actions CI. Acceptance:
   `docker compose up` serves `/healthz`; app builds; CI green.
2. **Phase 1 — Server core:** auth (register/verify/login/refresh/lockout/
   reset/delete), encryption, APs + password candidates, votes/ranking,
   reports/auto-flag, audit log, geo queries, rate limits, tests.
3. **Phase 2 — App core** (auth + verification deep link, nearby list,
   share, search, detail/copy, voting + report UI).
4. **Phase 3 — Map** (osmdroid, bbox viewport pins).
5. **Phase 4 — Polish.**

Open questions to settle (DESIGN.md §11): server deployment target
(VPS vs managed), SMTP provider + sender domain, default search radius,
pin-drag location correction in share flow, ToS/privacy policy text,
vote-mechanics tuning (−3 collapse threshold, score decay,
voted-only-after-open anti-brigading).

## Environment

- macOS 26.6.2 (arm64), host `mac.lan`, user `erikbeans`.
- Working directory: `/Users/erikbeans/Documents/Wifi Database` (git repo,
  `main` branch).
- When starting the new session here, point it at this file first, then
  `docs/DESIGN.md` for the full spec.
