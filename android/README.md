# Tuppu — Android client

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

Keep that keystore: Android installs an update only if it is signed with the
same key, and `../release.sh` refuses to embed an APK signed with a different
one. A release build also cannot be installed over a debug build (different
signatures); uninstall the debug app first.

The release unit-test variant (`./gradlew testReleaseUnitTest`) includes the
proof that the debug-only TLS bypass is absent from release.

`../release.sh` builds the release APK and embeds it in the server package,
which serves it at `/shoppinglist.apk`.

## Modules

The build has two Gradle modules:

- **`core/`** is the data layer: the API client (`Api`, the DTOs, the
  interceptors, the protocol version and floor), the accounts (the registry, one
  API session per account), the Room database (entities, DAOs, the `@Database`
  class and its exported schemas in `core/schemas/`), the repositories and the
  sync engine. It is a plain Kotlin/JVM module with no
  Android SDK on its classpath, so none of it can use Android. Its tests are
  plain JUnit (`./gradlew :core:test`). [`core/README.md`](core/README.md) lists
  what it may import.
- **`app/`** is the Android app: the Compose UI, the Hilt wiring, and everything
  that needs the platform, such as DataStore preferences, the Keystore-backed
  store of account tokens, WorkManager scheduling, notifications, and building the Room
  database from a `Context` together with its migrations. Where `core` needs one
  of these it declares an interface and `app` implements it. Tests that need
  Robolectric live here: the screens, the migration test, and several tests of
  `core` classes (the repositories, the sync engine) that are here only because
  they open Room through a `Context`.

### Accounts

Every list belongs to an account: a row in the `accounts` table with the server
URL, the server's id for the account, the sync cursor, the default currency and
the account's other per-session state. The bearer token is not in the database;
it sits in the Keystore-backed encrypted preferences, keyed by the account's
local id. Each server account has its own API client, so a `401` signs out only
that account and a `426` marks only that account outdated. A sync runs every
signed-in, up-to-date account in turn; one failing does not stop the others, and
the status bar shows the worst of them.

A list and an item each have a local id, the phone's own key for the row, beside
the id the server gives them, which is unique only within one account. Screens,
navigation, notifications and the remembered last-opened list pass the local id;
only the sync engine maps server ids to rows, always within the syncing account,
and what it sends carries the server ids. Two accounts on the phone that can both
see a list therefore hold a row each, with their own unpushed edits and
quarantined rows, and removing one account leaves the other's rows alone.

With one account the overview is a plain list of lists. With several it has a
section per account, in the user's order, headed by the account's email and
server URL, and each card names its account. A signed-out account's section
offers to sign it in again; an outdated one says its server needs a newer app
and, tapped, asks the servers for one whatever the automatic check is set to,
as its row on the Accounts screen does with *Check for update*: a newer app is
offered with the usual prompt, and otherwise a dialog says none was found.
Each account's pending invites are fetched from its own server and shown in its
section, as soon as the account is signed in, and a new list goes to the account picked in the New-list dialog
(by default the account of the list opened last). The list screen shows the
list's account under its name, and a collaborator notification names it, as
"email · host/path", only when the phone holds more than one account. A screen showing one list takes
its account, API client and default currency from the list's row.

A login keeps every account already on the phone. Logging in again as an
account it holds, on the same server, keeps that account's lists, unpushed edits
included; logging in as anyone else adds a new account beside the others.

The phone can also hold one local area, an account with `kind = local` and no
server, made from the start screen's *Use without an account* or from Accounts'
*Add local area*; a note says once, when it is made, that its lists stay on
this phone only, are not backed up and cannot be shared. Its label is stored
empty and it is named *On this phone* in the app's language wherever it is
shown. It holds shopping lists and checklists only: the New-list dialog offers
no ledger for it, and `ListsRepo` refuses to create one there or to convert a
list to one. Its lists and items carry this device's id and field clocks like
any other, but the sync engine never sends them, and they count towards no
pending figure; with only the local area the top bar shows no sync status. A
local list's properties show *You* as its only member and have no invites,
close votes or collaborator notifications, and *Delete list* removes it and its
items from the phone. On the overview the local area is a section in your
order like any account's, and its cards carry a phone glyph; with the local area alone the overview is the
plain one-account screen. It takes no invites: an invite on a phone with only
the local area opens the form to add a server account. Its Account screen
explains it and counts its lists, and it can be removed only once it holds
none. While it is on the phone, servers that all need a newer app do not block
the app: each outdated account's section says so instead.

A list never moves between accounts, but it can be copied into another. List
properties' *Duplicate* makes the copy at once when the phone holds one account;
with several it asks *Copy to*, listing the list's own account first and then the
others in the overview's order. The copy is a new list in
the chosen account, with fresh ids and field clocks, the name suffixed
*(Copy)* in the app's language (the name is synced, so it stays in the
copier's), the category order, notes and kind, and every item that is not
deleted, hidden shopping fields included. It has no members or close votes of
its own until its server reports them. A copy into a server account goes out on
that account's next sync; one into the local area never does. A ledger is not
offered for copying, and `ListsRepo.duplicate` refuses to copy one into another
account. Removing a server account says that a list to keep can be copied to
another account first, or to this phone when the local area is there.

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

Once installed, the app keeps itself current: on foreground (at most twice a
day) it asks each server it has an account on, via `GET /api/v1/app-version`,
whether a newer version exists (the newest any of them offers wins), and offers
each new version once. Opening About checks right away, says what it found, and
offers a version you declined again. Accepting hands the APK URL to the system,
which installs it the same way a sideload does — the app downloads and installs
nothing itself and asks for no extra permissions. The check can be turned off
under About → App updates. A server that carries no APK answers 404 and the app
stays quiet.

On first launch the login screen shows the **server URL** — prefilled with
`https://p23q.org/shopping` (the original instance; edit it if you self-host)
or with whatever you last logged into. It's the base URL where the Flask
blueprint is mounted, e.g. `https://shopping.example.com/` or
`https://example.com/apps/shopping/`, and **must be `https`** (the login
screen rejects plain `http`), so the server needs TLS in front of it — see
[`../server/README.md`](../server/README.md) for the deployment requirements.
Confirm it, then register a new account or log in. The first request the app
makes to a server it has not signed in to before asks its protocol version; a
server older than the app supports (`MIN_SERVER_PROTOCOL` in `Protocol.kt`) is
refused with *"This server is too old for this app."* On later launches the app
reopens the list you last had open.

The app holds several accounts, on one server or on several. **Accounts** in
the menu lists them in your order (drag one by its handle to move it, which is
also the overview's order; the phone's own area moves like any other), each with its email,
its full server URL, its state and its own sync figures. *Add account* opens the same form and keeps the accounts
already there; tapping an account opens its own screen, with its default
currency, initials, password, email, sessions, *Delete account on server* and
*Remove from this phone* (which deletes its lists and items here only, and
warns how many changes have not gone out yet). *Server admin* in the menu opens
the admin console of a signed-in admin account; with several, it asks which. There is no sign-out: when a
server rejects an account's token (a password change, an admin reset, 62 days
idle), that account's row says *Signed out: tap to sign in*, its lists stay on
the phone and editable, and signing in again from there resumes its sync. The
start screen appears only while the phone holds no account at all, the local
area included.

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

Nothing else is host-specific. The package name (`org.p23q.shoppinglist`) and the
signing certificate carry the original domain but do not affect where the app
connects; rename them only to publish a build that could be installed alongside
the original.

### Testing against a self-signed server (debug builds only)

A **debug** build's login form and each account's screen have a *"Trust
self-signed certificates"* toggle for pointing the app at a server with a self-signed cert (e.g. the
bundled `dev_tls_server.py` on `:8723`). It disables TLS certificate
verification — **insecure, for local testing only.** The option and the code
behind it exist **only in debug builds**: the release APK does not contain the
trust-all path at all (`src/debug` vs `src/release` `DevCertTrust.kt`), so the
setting is absent from release and a value carried over from a debug install
can never weaken a release build's TLS. For a real self-hosted deployment, put
a proper certificate (e.g. Let's Encrypt via a reverse proxy) in front of the
server instead.

### Self-hosted TLS: making your own certificate trusted (release-safe)

When the app can't validate the server's certificate it says *"The server's
certificate isn't trusted…"* rather than *"Couldn't reach the server"*, so a
certificate problem is told apart from a wrong URL or being offline. Three
options, in order of preference:

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

Invites are shared as `https://<your-server>/invite/<token>` links, where
`<your-server>` may include a mount path of up to three segments. The app
declares an intent-filter for `https://*/invite/*` and one pattern per mount-path
depth (see `AndroidManifest.xml`; a `pathPattern` does not backtrack, so one
`/.*/invite/.*` covers one segment only), so tapping such a link opens straight
into the redeem flow when this is the only app installed that claims it.

The link names its server: everything before `/invite/`. The accounts whose
server URL is exactly that prefix can take the invite; the host alone is not
enough, since two instances can share one host under different paths. One such
account redeems it; several ask "Join with which account?"; none opens the
sign-in form prefilled with that server and redeems once the new account is
signed in. A signed-out account is signed in again first. The invite waits for that one
sign-in only: backing out of the form drops it. Pasted text is searched for its
first https link, so a whole "Join my list: …" message works. A pasted bare token
names no server: it goes to the only account, or the user picks one.

This is **not** a verified [Android App
Link](https://developer.android.com/training/app-links/verify-android-applinks)
out of the box: verification (`autoVerify`) checks a Digital Asset Links file at
one declared host, and a self-hosted app doesn't know its host ahead of time.

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

Without it, invite links still work via the paste-a-code fallback ("Join a
list" in the app's drawer menu), or by the OS's normal disambiguation prompt
when more than one app can open the link.
