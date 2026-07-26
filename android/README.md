# Shopping List — Android client

Jetpack Compose client for the shopping-list server (`server/`). Offline-first:
it keeps a full local Room mirror and syncs to the server in the background, so
the app stays usable with no connection. See
[`../docs/wire-contract.md`](../docs/wire-contract.md) for the interface this app
implements against.

## Building

Requires a **JDK 17** and the **Android SDK** — point `ANDROID_HOME` at your
SDK, or add a `local.properties` here with `sdk.dir=/path/to/Android/sdk`.
Then:

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. It's a debug
build signed with the standard Android debug key — fine for installing and
testing.

Run the checks with `./gradlew lint test`.

### Release builds

```bash
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

Release builds are minified (R8; kotlinx-serialization keep rules live in
`app/proguard-rules.pro`) and signed from a keystore that is **deliberately not
committed**: `keystore/release.jks`, with its credentials in
`keystore.properties` (both gitignored — see `.gitignore`). Without those files
the release build has no signing config and fails at the signing step; recreate
them with:

```bash
keytool -genkeypair -keystore keystore/release.jks -alias shoppinglist \
  -keyalg RSA -keysize 2048 -validity 10000
cat > keystore.properties <<EOF
storeFile=keystore/release.jks
storePassword=...
keyAlias=shoppinglist
keyPassword=...
EOF
```

**Back the keystore + properties file up somewhere safe** — installing an
update over an existing install requires the same signing key; losing it means
uninstall/reinstall (local data re-syncs from the server, so nothing is lost,
but sessions/settings reset). Note the release build cannot be installed *over*
a debug build (different signatures) — uninstall the debug app first. Release
builds have no "trust self-signed certificates" toggle by design; see the TLS
section below.

The release unit-test variant (`./gradlew testReleaseUnitTest`) includes the
proof that the debug-only TLS bypass is absent from release.

After building a release, **refresh the copy embedded in the server package**
so `/shoppinglist.apk` serves the new version (see "Android app download" in
[`../server/README.md`](../server/README.md)):

```bash
cp app/build/outputs/apk/release/app-release.apk \
   ../server/src/shoppinglist_server/apk/shoppinglist.apk
```

## Installing on a phone

minSdk is 26, so any phone running **Android 8.0 (Oreo) or newer** works.

- **From a running server (easiest for users):** the server serves the release
  APK itself at `https://<your-server>/shoppinglist.apk`, linked from the web
  client's login page and from invite landing pages — see the "Android app
  download" section in [`../server/README.md`](../server/README.md).
- **Over USB (adb):** enable Developer Options → USB debugging on the phone,
  connect it, then run
  `adb install app/build/outputs/apk/debug/app-debug.apk`.
- **Sideload:** copy the APK to the phone (email, cloud drive, USB) and open
  it in a file manager. You'll be prompted to allow installs from that source
  the first time.

On first launch the login screen shows the **server URL** — prefilled with
`https://p23q.org/shopping` (the original instance; edit it if you self-host)
or with whatever you last logged into. It's the base URL where the Flask
blueprint is mounted, e.g. `https://shopping.example.com/` or
`https://example.com/apps/shopping/`, and **must be `https`** (the login
screen rejects plain `http`), so the server needs TLS in front of it — see
[`../server/README.md`](../server/README.md) for the deployment requirements.
Confirm it, then register a new account or log in. On later launches the app
resumes your session and reopens the list you last had open.

### Changing the prefilled URL for your own build

The default is one constant:

```
app/src/main/java/org/p23q/shoppinglist/ui/login/LoginViewModel.kt
    const val DEFAULT_SERVER_URL = "https://p23q.org/shopping"
```

Edit it before building, and update the assertion in
`app/src/test/java/org/p23q/shoppinglist/ui/login/LoginViewModelTest.kt` to match
or the test suite fails. It is only a prefill — the field stays editable, and any
URL a user logs into replaces it — so users of a stock build can always reach your
server by typing its URL.

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

### Self-hosted TLS: making your own certificate trusted (release-safe)

When the app can't validate the server's certificate it now says so distinctly —
*"The server's certificate isn't trusted…"* — rather than the generic *"Couldn't
reach the server"*, so you can tell a cert problem apart from a wrong URL or being
offline. You have three options, in order of preference:

1. **Use a certificate from a public CA** — e.g. Let's Encrypt, typically
   terminated at a reverse proxy (nginx/Caddy/Traefik) in front of the Flask
   blueprint. Nothing to configure on the phone. Best for anything internet-facing.
2. **Run your own CA and install it on the device.** Homelab setups often use a
   private CA. Install that CA's certificate on the phone (Settings → Security →
   Encryption & credentials → Install a certificate → CA certificate). This app
   ships a `network_security_config.xml` that trusts **user-installed** CAs in
   addition to the system store — a deliberate choice for a self-hosted-server app
   (Android 7+ ignores user CAs by default, which otherwise makes a correct private
   HTTPS setup impossible). This works in **release** builds and only trusts CAs
   *you* explicitly installed; it does not trust-all.
3. **Debug-only trust-all toggle** (previous section) — quickest for local dev, but
   debug builds only and insecure.

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
