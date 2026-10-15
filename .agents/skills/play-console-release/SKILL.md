---
name: play-console-release
description: "Build, validate, and publish a signed Android app update to an existing Google Play Console track. Use when asked to update an APK or AAB in Play Console, including WiFi Database releases. Excludes creating a new Play listing."
---

# Play Console Release

Complete the requested release through visible Play Console confirmation. Honor
requests to prepare a bundle or save a draft without publishing. This skill
does not authorize a new release, promotion, or server deployment by itself.

## Establish the release

- Read the repository's `AGENTS.md`, release guide, build configuration, release
  workflow, and current Git status. Preserve unrelated changes.
- For WiFi Database, read [references/wifi-database.md](references/wifi-database.md).
  Treat its identifiers as lookup hints; verify the current repository and
  Console identity before writing anything.
- Inspect the app and its existing releases in Play Console. Use the track
  the user requests; otherwise use an unambiguous existing testing track.
  Clarify only if the app, track, or desired source cannot be resolved.
  Do not promote to production as part of an ordinary testing update.
- Compare the requested source against the last release. Reuse an existing
  validated artifact when it already represents the requested update.
  If that version is already uploaded to Play, select the existing bundle
  instead of uploading it again. If already available on the requested track,
  report that state without creating another release. Resume matching drafts;
  do not replace an unrelated draft or pending release.
- For a new artifact, choose a version code greater than every previously
  uploaded code, including drafts and other tracks. Derive the version name
  from project conventions or the user's request; never hardcode the last
  release's numbers.

## Prepare and validate

1. Create a dedicated branch before repository changes. Bump the Android
   version and write concise release notes from the actual changes since the
   previous shipped artifact. Preserve the application ID and signing key.
2. Update release documentation. Check `docs/DESIGN.md` when present: a version
   bump alone usually does not change design; build workflow, API, permission,
   or deployment changes may require corresponding updates.
3. Use the existing signing workflow. Prefer configured CI signing when keys
   are already stored there; do not retrieve or print private keys/passwords.
   Build the signed **release AAB** expected by the app's Play listing, even
   when the user informally calls it an APK. A debug APK is not a substitute.
4. Commit and push the release branch and trigger the configured workflow.
   Track the exact run, source commit, and artifact. Do not overwrite an
   existing release or retarget a release tag; use a unique retry tag or the
   workflow's supported rerun/dispatch mechanism when needed.
5. Wait for the build and appropriate tests to pass before upload. Diagnose
   failures from logs and fix concrete release blockers within scope. Never
   remove tests or bypass a failing check just to obtain an artifact.
   For Compose, distinguish the signed bundle's build variant from the variant
   needed by its UI test harness; use the repository's established setup.
6. Keep the user informed during long builds. If a run appears stuck, inspect
   live logs and compare its duration with recent successful runs before
   cancelling. Do not repeatedly rerun unchanged failures. After two attempts
   with the same unresolved blocker, report the cause and required next action.
7. Download the successful run's artifact to a clearly named local release
   folder. Check archive integrity, record a SHA-256 digest, and retain its
   source/run provenance. Verify signing locally when tooling is available;
   Play Console must also accept the upload identity and signature.
   Compare the uploaded bundle's package, version code, and version name with
   the intended source before proceeding. Do not infer identity from its filename.

If release changes require matching server behavior, verify the deployed
revision or contract without exposing production secrets. Report any missing
dependency and resolve it only within the user's authorized deployment scope.

## Upload and roll out

- Use a dedicated Play connector if available; otherwise use supported browser
  tools. Follow their current upload and confirmation instructions. Reuse the
  existing signed-in Console tab and identify the correct app and track.
- Create or resume the intended draft. Upload the exact validated artifact,
  enter its version name and release notes, and wait for upload processing.
  An apparent 0-byte stall can precede successful processing: inspect fresh
  page state before cancelling, retrying, or changing browser permissions.
- Preview the release. Inspect all errors/warnings and device-support changes.
  Stop for blocking errors, unexpected package/signing changes, or unexplained
  loss of device support. Evaluate known warnings against the project's build
  settings rather than treating every warning as a blocker.
- If the user requested the update, complete publication to the resolved track
  within that authorization. If the user requested only preparation, save a
  draft or return the bundle. Follow applicable tool confirmation requirements.
- Inspect Publishing overview when submission or managed publishing applies.
  Verify which changes the final action includes; do not submit or publish
  unrelated pending changes. Preserve the existing managed publishing setting
  and requested rollout percentage. If the UI cannot isolate this release,
  explain the conflict and request direction before the combined action.
- Verify the resulting release page explicitly shows the intended version
  and availability/status. A successful click, saved draft, or accepted upload
  alone is not proof of rollout. If already published, do not publish again.
  Report review or processing as pending, with the Console link and next action;
  do not keep rebuilding or uploading while awaiting Google's decision.
  Follow [Google's release guide](https://support.google.com/googleplay/android-developer/answer/9859348?hl=en)
  and [publishing status guidance](https://support.google.com/googleplay/android-developer/answer/9859654?hl=en)
  if the current Console flow differs. Internal testing is generally immediate
  and is excluded from managed publishing, but review exceptions can apply.

## Finish

- Save a screenshot of the visible release status when browser tools support
  it, and keep the resulting Console tab available to the user.
- Record the actual version, track, source, CI result, and publication status
  in the release documentation. Never record publication before verification.
- Finish the repository's branch workflow. In WiFi Database, validated changes
  must merge into `main` and be pushed; preserve unrelated work.
- Report the version, track, verified status, and any remaining action. Include
  the Console link and saved screenshot when available. State propagation time
  only when supported by the current Console UI or official guidance.
