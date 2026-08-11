# In-app update check for the Android client

Design for T-135. Shipped in 1.12.0.

## Problem

The app is self-hosted and distributed as an APK by the server itself, so there is no store
to publish through and no update channel. A user only learns that a new version exists if
they think to revisit `<server>/shoppinglist.apk` by hand, and devices drift behind the
server indefinitely.

## Approach

The client asks the server it already syncs with. Three decisions shape everything else:

**Version identity comes from the server's own package version.** One built wheel is a
single deployable artifact whose parts deliberately share one version number, so the
package version *is* the embedded APK's version — no APK parsing, no build-time manifest,
nothing new in the release path. The alternative (recording the APK's real `versionCode` at
build time) buys insurance against the two numbers disagreeing, which cannot happen without
someone hand-editing one of the two files the release process bumps together.

**Installing is handed to the system.** Tapping Update fires `ACTION_VIEW` at the APK URL
and Android's browser/download manager plus the normal package installer take over. No
`REQUEST_INSTALL_PACKAGES`, no download code, no progress UI, no installer session to
babysit — and the user gets the install flow they already know.

**A missing endpoint and an old server are the same case.** The endpoint is served only
when this instance actually carries an APK, so both "built without the Android artifact"
and "released before this feature existed" answer 404. The client needs one code path for
"nothing to say", not two.

## Server

`GET /api/v1/app-version`, unauthenticated like `/registration-status` — checking for an
update is not an account operation, and the APK it points at is public anyway.

```json
{"version": "1.12.0", "download_url": "https://host/shopping/shoppinglist.apk"}
```

404 with `no_app_package` when the instance serves no APK, or when the package version
can't be determined (running from a source checkout rather than an installed wheel —
reporting nothing beats inventing a version that could trigger a spurious prompt).

The APK route is registered from `@bp.record_once`, which runs *after* the API blueprint's
own routes are defined, so "does this instance serve an APK?" cannot be answered at
registration time. It is resolved per request instead: `serve_android_apk` rides in the
per-instance config that `get_config()` already scopes correctly across multiple mounts,
and `routes/apk.py` exposes `apk_present()` so both routes answer from one check.

## Client

| Unit | Responsibility |
|---|---|
| `UpdatePrefsStore` | `autoCheckEnabled` (default on), `lastPromptedVersion`, `lastCheckedAt`. Device-local, unsynced, survives logout — same shape as `NotificationPrefsStore`. |
| `compareVersions()` | Numeric field-wise ordering; `null` for anything not purely numeric. |
| `UpdateChecker` | Gate, request, compare, decide. Owns the 12h rate limit and the once-per-version rule. |
| `UpdateViewModel` + prompt in `Nav.kt` | `ON_START` trigger, dialog over any screen except Login. |

`compareVersions` exists because string comparison puts `1.9.3` above `1.10.0` — the exact
transition this project shipped, and a bug that would have silently told every 1.9.3 user
they were up to date. Unparseable input returns `null` rather than throwing or guessing,
because the input is whatever a server said and "I can't tell" has to reach the caller.

The attempt timestamp is recorded *before* the request, not after: recording only on
success leaves a down server being retried on every single foreground.

## Settings

One switch in Account settings, directly below the notification switch, default on.
Off means no request at all — not a silent check.

## What this design does not cover

That `ACTION_VIEW` reaches a browser and the install completes cannot be verified in the
build sandbox; Robolectric does not model it. It is the riskiest part of the feature and is
covered by the real-device pass that follows every release.
