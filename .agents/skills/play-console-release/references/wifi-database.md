# WiFi Database release profile

Read this only for the WiFi Database app. Re-read repository configuration and
Play Console on every release; this profile does not pin a current version.

## Identity and sources

- Repository: `erikbeans/wifi-database`; use the GitHub account with access to
  that repository (historically `erikbeans`, rather than `ebeans-fqt`).
- Local checkout: `/Users/erikbeans/Documents/Wifi Database` when available.
- Android application ID: `com.clickbeans.wifidatabase`. The historical
  `com.erikbeans.wifidatabase` bundle is a separate app and is not an update.
- Read `AGENTS.md`, `docs/RELEASE.md`, `docs/DESIGN.md`,
  `android/app/build.gradle.kts`, and `.github/workflows/android-release.yml`.
  Consult `docs/SESSION_CONTEXT.md` for operational hints, not current version
  or deployment truth.
- Existing track for ordinary updates: **Internal testing**. Confirm it in
  Console; production promotion is a separate requested operation.
- Known Console track:
  <https://play.google.com/console/u/0/developers/6840887112339117561/app/4976015294566511354/tracks/4701375907841058376?tab=releases>

## Signed build and tests

- The `Android Release` workflow runs for pushed `v*` tags and uses GitHub's
  existing signing secrets. Keep their contents out of logs and documentation.
- It uses JDK 21, builds `./gradlew bundleRelease --console=plain`, then runs
  `./gradlew testDebugUnitTest --console=plain` before publishing the artifact.
  Re-read the workflow if these commands change.
- Compose UI tests need `androidx.compose.ui:ui-test-manifest`, which is a
  **debug-only dependency**. Running those tests as `testReleaseUnitTest` has
  failed with `Unable to resolve activity ... androidx.activity.ComponentActivity`.
  Keep the harness out of the shipped release; run the complete established
  debug unit suite while building the signed release bundle.
- Successful workflows attach `app-release.aab` to the matching GitHub Release
  and also upload a `release-aab` Actions artifact.
- Resolve the release tag to its exact commit and select CI by that SHA and
  workflow, rather than taking the latest run or the rolling `debug-latest`
  release. Useful commands, with variables set from the verified release:

  ```sh
  gh run list --repo erikbeans/wifi-database --workflow android-release.yml --commit "$release_sha" --json databaseId,headSha,status,conclusion,url
  gh run view "$release_run_id" --repo erikbeans/wifi-database --json headSha,status,conclusion,jobs,url
  gh release download "$release_tag" --repo erikbeans/wifi-database --pattern app-release.aab --dir "$release_dir"
  unzip -t "$release_dir/app-release.aab"
  shasum -a 256 "$release_dir/app-release.aab"
  jarsigner -verify "$release_dir/app-release.aab"
  ```

  Require the selected run to have succeeded and match the tagged source.
  Inspect signature output: an unsigned archive is unacceptable even if the
  command exits successfully. Check package and version in Play's bundle details.
- Local build output, when built locally:
  `android/app/build/outputs/bundle/release/app-release.aab`.
- Use a local folder such as `~/Downloads/Wifi-Database-<version>/` for the
  downloaded bundle and Console confirmation screenshot. Keep both out of Git.

## Console review and dependent API

- Release minification is disabled, so no R8 deobfuscation mapping exists.
  The missing-deobfuscation warning has been nonblocking.
- A prebuilt AndroidX native library lacks distributable debug symbols;
  the missing-native-symbol warning has also been nonblocking. Confirm current
  warnings match those facts rather than assuming all warnings are harmless.
- Preserve device support unless the requested change deliberately alters it.
- API origin: `https://wifidb.clickbeans.com/`. The `openclaw` SSH alias has
  historically reached the production host; verify it before any access.
- Production checkout: `/opt/wifi-database`; Docker service container has been
  `wifi-database-prod-api-1`. Use read-only revision/module checks for release
  dependencies. A Play update does not implicitly authorize server deployment.
- Preserve the configured Play **app-signing** certificate in App Links; it can
  differ from the upload-key certificate. Do not rotate either to publish an update.

## Repository completion

Create a `codex/` feature branch, validate there, then merge into `main` and
push as authorized by `AGENTS.md`. Update `docs/RELEASE.md` with the real rollout
result and keep affected design sections current. A fresh install, new store
listing, or account/signing reconfiguration is not part of a routine update.
