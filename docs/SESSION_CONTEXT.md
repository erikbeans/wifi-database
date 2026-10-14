# WiFi Database — Session Context & Handoff

Written 2026-10-14 as a handoff for the next agent/session. The authoritative
spec is **`docs/DESIGN.md`** — this file summarizes current state and how to
operate the project. Delete or archive once absorbed.

## Project

Crowd-sourced WiFi access-point password directory: Android app
(Kotlin/Compose) + REST API (FastAPI, PostGIS). Private repo:
`github.com/erikbeans/wifi-database`. Owner: **erikbeans** (this is the gh
account to use; a second account `ebeans-fqt` exists on the machine — ignore
it for this repo).

## State: all four build phases are COMPLETE

- **Phase 0 — Scaffold:** monorepo, docker compose, CI. Done.
- **Phase 1 — Server core:** auth (register/verify/login/refresh rotation
  with family revocation/lockout/reset/delete), AES-256-GCM password
  encryption, APs + candidates, votes/ranking, reports with 3-strike
  auto-flag, audit log, geo queries, rate limits. 56 tests, deployed,
  live-verified including a real email round-trip.
- **Phase 2 — App core:** login/register/reset UI with native credential
  fields (Bitwarden-compatible), https deep links, nearby scan matching,
  scan-only share dropdown, detail with Add-to-phone, votes, reports,
  settings, crash reporter. On-device acceptance done by the owner.
- **Phase 3 — Map:** osmdroid viewport pins via `in-bbox`, pin-tap bottom
  sheet, pin-drag location picker on Share with 500 m clamp.
- **Phase 4 — Polish:** donations (Settings row + capped post-success card
  → GitHub Sponsors), in-app privacy/terms links, empty states, WiFi-off
  banner, map auto-centering.

All phases on-device tested on the owner's Samsung Fold 6 (Android 16).

## Production environment

- Host: Oracle Cloud Always Free ARM64 VM (Ubuntu 24.04). Public name:
  **`wifidb.clickbeans.com`** (DNS → VM; the old `openclaw.clickbeans.com`
  name was retired — do not use it). Everything talks HTTPS via Caddy
  (auto Let's Encrypt; assetlinks + /privacy + /terms served by the API).
- Stack: `/opt/wifi-database` checkout (root-owned; `ubuntu` pulls) with
  `server/docker-compose.prod.yml`: PostGIS (no published port) + API +
  Caddy. Secrets in `/opt/wifi-database/server/.env` (root:600): DB
  password, `WIFI_JWT_SECRET`, `WIFI_ENCRYPTION_KEY` (base64 32 bytes),
  Brevo SMTP creds, `WIFI_ANDROID_SHA256_FINGERPRINTS` (JSON list).
- **Deploy:** `git -C /opt/wifi-database pull && cd /opt/wifi-database/server
  && sudo docker compose -f docker-compose.prod.yml up -d --build`
  (API runs `alembic upgrade head` at boot; migrations live in
  `server/alembic/versions/`). Docker requires sudo (ubuntu not in the
  docker group).
- SMTP: **Brevo** (`smtp-relay.brevo.com:587`), sender
  `no-reply@clickbeans.com` (domain verified). 10 auth emails/day/address.
- Donations: **GitHub Sponsors live** at `https://github.com/sponsors/erikbeans`
  (tiers set, bank connected). App links there; zero server involvement.

## Development environment

- Dev clone: `/home/ubuntu/coding/wifi_app/wifi-database` (ubuntu-owned).
- Android SDK: `~/android-sdk`; builds need `export ANDROID_HOME=~/android-sdk`.
  The host is ARM64 — x86_64 Android build tools run under qemu-user
  (binfmt + amd64 libs already configured). Emulator is NOT available.
- **Robolectric cannot run locally** (no aarch64 native runtime): the
  `CredentialFieldTest` runs in CI (x86_64) only. Locally run tests with
  explicit `--tests` filters excluding it.
- Server tests: venv (e.g. `/tmp/opencode/venv`; recreate with
  `pip install -e ".[dev]"` from `server/`) + throwaway PostGIS:
  `sudo docker run -d --name wifi-test-db -p 5433:5432 -e POSTGRES_USER=wifi
  -e POSTGRES_PASSWORD=wifi -e POSTGRES_DB=wifi_test postgis/postgis:16-3.4`.
  Tests default to `localhost:5433` (conftest honors `WIFI_DATABASE_URL`).
- adb is NOT on the server. The owner tests on a Fold 6 via adb on their Mac:
  `scp openclaw:coding/wifi_app/wifi-database/android/app/build/outputs/apk/debug/app-debug.apk /tmp/`
  then `adb install -r`. Rolling debug APK is also published to the GitHub
  release tag `debug-latest` on every green Android CI run.
- Git identity is repo-local: `Erik Beans <10479994+erikbeans@users.noreply.github.com>`.

## Key implementation facts (don't re-learn these)

- **WiFi passwords are 8–63 chars** (server-enforced; matches Android's
  WifiNetworkSuggestion). Short test data exists in prod ("tests", "yeah")
  and will always fail Add-to-phone — by design.
- **Share flow is scan-only:** dropdown of scanned SSIDs (deduped by SSID,
  preferring strongest security), no manual entry; the server cannot
  verify scan membership (trust boundary, documented in DESIGN.md).
  Pin-drag mini-map clamps to 500 m from the GPS fix (`domain/GeoMath.kt`).
- **Ownership UI:** detail responses carry per-viewer `mine` flags
  (AP + each candidate) — client previously used local-only storage that
  died on reinstall; the flags are the durable fix. Delete buttons rely
  on `mine || SubmissionMemory`.
- **Deep links:** `https://wifidb.clickbeans.com/verify|/password-reset`
  (App Links, autoVerify, assetlinks.json served by API from
  `WIFI_ANDROID_SHA256_FINGERPRINTS`) + fallback HTML pages + legacy
  `wifidatabase://` scheme. All debug builds sign with the committed
  `android/app/debug.keystore` (that fingerprint is what assetlists lists).
- **Crash reporter:** uncaught exceptions POST to `/api/v1/crash-reports`
  (rate-limited 10/h/IP; see `sudo docker logs wifi-database-prod-api-1 | grep wifi.crashes`).
- **Donation caps** (`domain/DonationPolicy.kt`, unit-tested): max 2 asks
  per lifetime, never before 3rd confirmed-working password, "Not now"
  defers 10 confirms, "Never" permanent. State is local-only, server not
  involved.
- **CI:** Server CI (pytest against PostGIS + compose smoke incl. boot
  migrations; pull retries harden against Docker Hub flakiness), Android CI
  (build + tests + `debug-latest` rolling release), **Android Release**
  (`v*` tag → signed AAB via GitHub secrets; secrets NOT yet configured).

## Remaining work

1. **Play launch (owner's prerequisites, then tag `v0.1.0`):** Play
   developer account, `upload.jks` keystore, the four GitHub secrets
   (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`),
   listing assets — full checklist in `docs/RELEASE.md`. Bump versionCode
   per upload.
2. **Post-MVP backlog** (DESIGN.md): offline cache, vote weighting/decay,
   admin review UI for flagged content, SSID+proximity dedup, key
   rotation tooling. Vote-mechanics tuning is the only "open question"
   left in DESIGN.md §11, deliberately post-MVP.
3. Possible UX review pass on a real device; app icon is a basic adaptive
   vector (placeholder-quality).

## Notes / gotchas

- Multiple agents (OpenCode, Codex, Claude) can edit this repo — check
  `git status` before starting; commit other agents' completed work after
  reviewing it.
- Test accounts in prod: `erikbeans@gmail.com` (verified; its password was
  shared in chat history), `ldallemolle@gmail.com` (owner's second
  account). Test APs: "BeanieBitz" (owner's home mesh), "tests", "yeah".
- The design doc's §11 open questions are all resolved except vote
  mechanics; don't re-litigate them without asking the owner.
