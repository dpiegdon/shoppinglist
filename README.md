# Shopping List

A shared, offline-first shopping-list app. Accounts (email + password) own
shopping lists; lists can be shared with other accounts via email-bound
invites (flat access — you either have access or you don't, no roles). Each
list is a registry of every item ever added to it, with items moving between
**backlog** (known, not on the list), **todo** (on the list), and **checked**
(bought) as you shop.

The full design lives in
[`docs/superpowers/specs/2026-07-08-shopping-list-server-design.md`](docs/superpowers/specs/2026-07-08-shopping-list-server-design.md)
(server) and
[`docs/superpowers/specs/client-ui-notes.md`](docs/superpowers/specs/client-ui-notes.md)
(client UI). The **wire contract** both clients implement against lives in
[`docs/superpowers/plans/2026-07-08-shopping-list-tickets.md`](docs/superpowers/plans/2026-07-08-shopping-list-tickets.md#wire-contract-shared-interface--server-implements-both-clients-consume).

## Parts

| Path | What | Docs |
|------|------|------|
| `server/` | Flask blueprint + SQLite backend: accounts, sync, sharing. Mountable at any URL, including multiple isolated instances on one app. One built wheel is a **single deployable artifact**: it embeds and serves the web client, the invite landing page, and the Android APK download. | [`server/README.md`](server/README.md) |
| `web/` | React web client. Not deployed separately — built straight into the server package and served by the blueprint itself. | [`web/README.md`](web/README.md) |
| `android/` | Native Android client (Kotlin/Compose), offline-first with a local Room mirror. Installable straight from a running server at `/shoppinglist.apk`. | [`android/README.md`](android/README.md) |

## Versioning

This project follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`).
Because one built wheel is a **single deployable artifact** — the server, the
embedded web client, and the Android APK all ship together — every part shares
**one** version number rather than versioning independently. A release bumps that
single version in both `server/pyproject.toml` and `android/app/build.gradle.kts`
(`versionName`, plus an incremented integer `versionCode`), and is tagged once as
`vX.Y.Z`.

## Setup

### Server

```bash
cd server
pip install -e ".[dev]"
DATABASE_PATH=dev.db INVITE_HMAC_KEY=dev-key BASE_URL=http://localhost:5000 \
  flask --app app.py run
```

Auto-initializes its schema on first run — no separate migration step. Full
configuration, the operator CLI, multi-instance mounting, the TLS dev server,
and deployment requirements are all in
[`server/README.md`](server/README.md).

### Web client

Built assets already ship embedded in `server/` (`git`-tracked, no Node
needed to *run* the server). To rebuild after making changes:

```bash
cd web
npm install
npm run build   # writes into ../server/src/shoppinglist_server/web_dist/
```

See [`web/README.md`](web/README.md).

### Android client

Build a debug APK (needs a JDK and the Android SDK):

```bash
cd android
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```

Install it on a phone (Android 8.0+) with `adb install app-debug.apk`, or
copy the APK to the device and open it — or skip building entirely and
download the release APK straight from a running server at
`https://<your-server>/shoppinglist.apk`. On first launch, confirm or edit
the prefilled server **https** URL and register or log in.

See [`android/README.md`](android/README.md) for prerequisites, sideloading,
release builds, and invite-link (App Link) setup.
