# Publishing to Google Play

This guide covers everything needed to publish WiFi Database to the
Google Play Store. New apps must be uploaded as a signed
**Android App Bundle (AAB)** — plain APKs are no longer accepted for
new Play Store listings.

## Current Android identity

Use package name `com.clickbeans.wifidatabase`. Release `v0.1.2`
(versionCode 3, target API 36) supersedes `v0.1.1` (target API 35)
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

Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`,
then:

```sh
cd android
./gradlew bundleRelease
```

Upload the new AAB to Play Console → Production → Create new release.
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
git tag v0.1.2
git push origin v0.1.2
```

The AAB is attached to a GitHub Release, ready to upload to Play.

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
