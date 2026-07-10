# Shopping List — Android client

Jetpack Compose client for the shopping-list server (`server/`). Offline-first:
it keeps a full local Room mirror and syncs to the server in the background, so
the app stays usable with no connection. See
`../docs/superpowers/specs/client-ui-notes.md` for the product requirements and
`../docs/superpowers/plans/2026-07-08-shopping-list-tickets.md` for the Wire
Contract this app implements against.

## Building

Requires a **JDK 17** and the **Android SDK** — point `ANDROID_HOME` at your
SDK, or add a `local.properties` here with `sdk.dir=/path/to/Android/sdk`.
Then:

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. It's a debug
build signed with the standard Android debug key — fine for installing and
testing, but not for Play Store distribution (a release build needs your own
signing config added to `app/build.gradle.kts`).

Run the checks with `./gradlew lint test`.

## Installing on a phone

minSdk is 26, so any phone running **Android 8.0 (Oreo) or newer** works.

- **Over USB (adb):** enable Developer Options → USB debugging on the phone,
  connect it, then run
  `adb install app/build/outputs/apk/debug/app-debug.apk`.
- **Sideload:** copy the APK to the phone (email, cloud drive, USB) and open
  it in a file manager. You'll be prompted to allow installs from that source
  the first time.

On first launch the app asks for your **server URL** — the base URL where the
Flask blueprint is mounted, e.g. `https://shopping.example.com/` or
`https://example.com/apps/shopping/`. It **must be `https`** (the login screen
rejects plain `http`), so the server needs TLS in front of it — see
[`../server/README.md`](../server/README.md) for the deployment requirements.
Enter it, then register a new account or log in. On later launches the app
resumes your session and reopens the list you last had open; the server URL is
remembered too.

### Testing against a self-signed server (debug builds only)

A **debug** build's Settings screen has a *"Trust self-signed certificates"*
toggle for pointing the app at a server with a self-signed cert (e.g. the
bundled `dev_tls_server.py` on `:8723`). It disables TLS certificate
verification — **insecure, for local testing only.** The option and the code
behind it exist **only in debug builds**: the release APK does not contain the
trust-all path at all (`src/debug` vs `src/release` `DevCertTrust.kt`), so the
setting is absent from release and a value carried over from a debug install
can never weaken a release build's TLS. For a real self-hosted deployment, put
a proper certificate (e.g. Let's Encrypt via a reverse proxy) in front of the
server instead.

## Invite links / App Links

Invites are shared as `https://<your-server>/invite/<token>` links. The app
declares an intent-filter for `https://*/invite/*` (see `AndroidManifest.xml`)
so tapping such a link opens straight into the redeem flow when this is the
only app installed that claims it.

This is **not** a fully verified [Android App
Link](https://developer.android.com/training/app-links/verify-android-applinks)
out of the box, because the server has no fixed public host (Notes: "no fixed
public URL, self-hosted") — Android's App Link verification (`autoVerify`)
checks a Digital Asset Links file at exactly one declared host, and this app
doesn't know your host ahead of time.

If you self-host the server and want the stronger, verified App Link behavior
(the link opens this app directly, with no disambiguation dialog, even when
other apps could also handle `https://` links), publish a Digital Asset
Links file for your server's host:

```
https://<your-server>/.well-known/assetlinks.json
```

containing an entry naming this app's package (`org.p23q.shoppinglist`) and
your release signing certificate's SHA-256 fingerprint. See the Digital Asset
Links documentation linked above for the exact JSON shape. The server package
in this repo does not currently serve this file for you — it would need to be
added to whatever process hosts your server deployment.

Without it, invite links still work via the paste-a-code fallback ("Join
list" in the app's drawer menu), or by the OS's normal disambiguation prompt
when more than one app can open the link.
