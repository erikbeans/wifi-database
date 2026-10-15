# Publishing to Google Play

This guide covers everything needed to publish WiFi Database to the
Google Play Store. New apps must be uploaded as a signed
**Android App Bundle (AAB)** — plain APKs are no longer accepted for
new Play Store listings.

## Current Android identity

Use package name `com.clickbeans.wifidatabase`. Release `v0.1.5`
(versionCode 6, target API 36) updates `v0.1.4` (versionCode 5) and supersedes `v0.1.1` (target API 35)
and `v0.1.0`, which used
`com.erikbeans.wifidatabase`; do not upload the old bundle.
The new package installs separately from previous debug installs and requires
signing in again.

App Links also need the app's signing certificate: add the SHA-256 fingerprint
from Play Console's **App signing key certificate** to the production
`WIFI_ANDROID_SHA256_FINGERPRINTS` list, preserving the debug fingerprint.
Google signs installed Play builds with that certificate, which can differ
from the upload certificate. Verify the live `/.well-known/assetlinks.json`
targets `com.clickbeans.wifidatabase` before checking email links.

## Prerequisites

1. **Google Play Developer account** — sign up at
   <https://play.google.com/console> (one-time $25 fee). Use the account
   that will own the app listing permanently; ownership can't be
   transferred later without support.
2. **Release keystore** — a private key you control (see below).
3. **Privacy policy URL** — the API serves both policy pages automatically:
   `https://wifidb.clickbeans.com/privacy` and `https://wifidb.clickbeans.com/terms`.
    Play requires this because the app collects location and account data.

## 1. Create an upload keystore

Run this once on your dev machine and keep the file safe — losing it
means you can't update your app (see [Play App Signing](#play-app-signing)
for the safety net):

```sh
keytool -genkeypair -v \
  -keystore upload.jks \
  -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Store `upload.jks` somewhere safe (encrypted backup, password manager
for the passwords). **Never commit it to git** — it's already covered by
`.gitignore` (`*.jks`).

## 2. Configure signing

Create `android/keystore.properties` (also gitignored) pointing at
your keystore:

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=your-keystore-password
keyAlias=upload
keyPassword=your-key-password
```

The build reads this file automatically; when it exists, release
builds are signed with it.

## 3. Build a signed AAB

```sh
cd android
./gradlew bundleRelease
```

The signed bundle lands at
`android/app/build/outputs/bundle/release/app-release.aab`.

Verify it before upload:

```sh
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
```

## 4. Play Console setup

In the [Play Console](https://play.google.com/console):

1. **Create app** — name: "WiFi Database", default language, App/Game:
   App, Free.
2. **Play App Signing** (recommended, and effectively required for new
   apps): Google generates and stores the *app signing key* that goes to
   users' devices. Your `upload.jks` becomes the *upload key* used to
   authenticate your updates. Choose "Export and upload a key from Java
   keystore" only if you have a pre-existing key you must keep — for a
   new app the default (Google-generated key) is fine.
3. **Store listing** — 512×512 PNG icon, 1024×500 feature graphic,
   at least 2 phone screenshots (1080×1920 or similar), short description
   (≤80 chars), full description (≤4000 chars).
4. **Data safety** — declare:
   - Location: collected, shared (network location is shown to other
     users), not sold
   - Email: collected, not shared, used for account management
   - App activity: page views/ interactions collected for abuse
     prevention
   - Data is encrypted in transit and at rest
   - Users can request data deletion (account deletion in Settings)
5. **Content rating** — complete the IARC questionnaire (answer
   honestly: sharing WiFi passwords, user-generated content, location
   sharing).
6. **Target audience & content** — declare the app is not directed at
   children (13+), contains user-generated content, and has a
   moderation/report mechanism (the app has voting and reporting).
7. **Privacy policy** — paste the URL where your hosted policy lives.
8. **App access** — the app requires an account for full features;
   provide demo credentials or explain the signup flow for review.
9. **Upload AAB** — create a release on the Production (or Internal
   testing) track and upload `app-release.aab`.

New personal developer accounts also require 12–20 testers enrolled in
closed testing for 14 days before production access is granted. Plan
for that lead time.

## 5. Release checklist (per update)

For the repeatable Codex workflow, use `$play-console-release`. Its versioned
instructions live in
[`.agents/skills/play-console-release/SKILL.md`](../.agents/skills/play-console-release/SKILL.md),
with a WiFi Database profile for signing, tests, and the existing Console track.
Example: "Use $play-console-release to publish the latest changes to internal testing."
For preparation only, say "Use $play-console-release to build the next signed
bundle without uploading" or "save a draft without publishing."
The skill reuses matching uploaded bundles and drafts, verifies the exact CI
source, and records the actual Play status. A submission awaiting review is
reported as pending, rather than available to testers.

### v0.1.5 release notes

- New welcome screen explains how WiFi Database helps you find and share WiFi passwords.
- Try sharing known passwords, finding WiFi while traveling, and voting on passwords.
- Reopen Welcome & tips from Settings at any time.
- Clearer instructions for moving the map and dragging the location pin.

Published to the existing **Internal testing** track on October 15, 2026,
at 4:53 PM PDT. [Play Console](https://play.google.com/console/u/0/developers/6840887112339117561/app/4976015294566511354/tracks/4701375907841058376/releases/5/details)
confirms **6 (0.1.5) is available to internal testers**. The signed
`bundleRelease` build and full `testDebugUnitTest` suite passed in
[Android Release CI](https://github.com/erikbeans/wifi-database/actions/runs/38825163004),
from tag `v0.1.5`, commit `f4d549a0be40a292307a80099c240162a907bd9e`.
Archive integrity and the JAR signature were verified locally; the signing
certificate matches `v0.1.4`. The downloaded bundle's SHA-256 matches GitHub's
release asset digest:
`09f79c459e3416bd9be1ab3b23084d6241a5b8131d35c775a717d996c55c0c1b`.

Play reports zero device-support losses, API 26+, target API 36, and the same
four native architectures. The only two warnings are the existing missing
deobfuscation mapping and native debug symbols described below. The signed
bundle, provenance, validation log, and Console confirmation screenshot are in
`~/Downloads/Wifi-Database-0.1.5/`, outside Git. The unrelated store-listing
draft was left untouched; managed publishing remains off.

This client update needs no new server deployment. The welcome flow and map
instructions are already documented in `docs/DESIGN.md`; the version bump adds
no design changes. On-device acceptance of the Play-installed update remains
a tester check.

### v0.1.4 release notes

- Improved map dragging and zooming while adjusting a shared network's location.
- Hide the keyboard when opening the location map or confirming a location.
- Hide deletion actions when a password or access point is unavailable to delete.

Published to the existing **Internal testing** track on October 15, 2026,
at 3:14 PM PDT. Play Console confirms **5 (0.1.4) is available to internal
testers**. The signed `bundleRelease` build and full `testDebugUnitTest` suite
passed in [Android Release CI](https://github.com/erikbeans/wifi-database/actions/runs/38814398997),
from tag `v0.1.4`, commit `30ae7d81195f928fbc46d6c602f2ce4e754934d2`.
Archive integrity and the JAR signature were verified locally; the upload
certificate matches `v0.1.3`. The downloaded bundle's SHA-256 matches GitHub's
release asset digest:
`19a93a2179d25e63bcd3ff12075b2096e3a306b8b49bc02c4d624a9e819c63c6`.

Play reports zero device-support losses, API 26+, target API 36, and the same
four native architectures. The two nonblocking warnings are the existing
missing deobfuscation mapping and native debug symbols described below.
The local bundle, provenance, validation log, and Console confirmation screenshot
are in `~/Downloads/Wifi-Database-0.1.4/`, outside Git.

This update changes only the Android client and requires no new server
deployment. The behavior changes are documented in `docs/DESIGN.md`; the version
bump does not introduce additional design changes. On-device acceptance of
these changes from the Play-installed update remains a tester check.

### v0.1.3 release notes

- Improved Nearby refresh after switching tabs or finding no networks.
- Contributor deletion is available for 15 minutes after sharing.
- Improved list refresh after voting removes a low-rated password or its network.

Published to the existing **Internal testing** track on October 15, 2026.
Play Console confirms version code 4 is available to internal testers. The
signed bundle build and full debug unit test suite passed in
[Android Release CI](https://github.com/erikbeans/wifi-database/actions/runs/38698094693).
The release uses
the existing upload key and package, so testers can update their Play install.
The corresponding API changes are deployed in production (commit `137f0cf`),
including the deletion and pruning modules; see `docs/DESIGN.md` for those
existing contracts. The version bump itself does not change app behavior.

### Build and upload

Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`,
then:

```sh
cd android
./gradlew bundleRelease
```

Upload the new AAB to Play Console → Internal testing → Create new release
for the ordinary WiFi Database update. Use another track, including Production,
only when requested. If the bundle is already uploaded, select it from Play's
library instead of uploading it again.
`versionCode` must increase with every upload; `versionName` is the
user-facing string (e.g. `0.2.0`).

## Automated release builds (CI)

`.github/workflows/android-release.yml` builds a signed AAB whenever
you push a `v*` tag, using GitHub repository secrets. Set these in
GitHub → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | base64 of `upload.jks` (`base64 -w0 upload.jks`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `upload` |
| `KEY_PASSWORD` | key password |

Then tag a release:

```sh
git tag v0.1.3
git push origin v0.1.3
```

The workflow builds the signed release bundle and runs the full debug unit
test suite before attaching the AAB to a GitHub Release. Compose UI tests
require the debug-only test activity manifest; that test activity is not
included in the release bundle.

## Play App Signing

With Play App Signing, Google holds the key that actually signs the
AAB delivered to users. You keep the *upload key* (`upload.jks`). If
you lose `upload.jks`, you can request a reset through the Play Console
after identity verification. Without Play App Signing, losing your
keystore means you can never update the app again — so leave Play App
Signing enabled.

## Internal testing warnings

- **No testers:** Internal testing → Testers → create/select an email list
  containing the Google account used on the test phone, save, and open the
  opt-in link with that account after rolling out the test release.
- **No deobfuscation file:** release minification is disabled, so no R8
  mapping file is generated. This warning does not block testing.
- **No native symbols:** Compose brings in the prebuilt
  `libandroidx.graphics.path.so`. Its distributed binaries are already
  stripped; our build cannot reconstruct missing debug information.
  The warning does not block testing. The shipped ARM64 binary uses 16 KB
  ELF alignment; packaging is handled by the Android Gradle Plugin.

The credential-field Robolectric tests cover API 35 and 36 in CI. API 36
Robolectric requires JDK 21; local non-Robolectric tests and builds may still
run on JDK 17.
