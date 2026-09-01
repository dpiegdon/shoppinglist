# Shopping List

A shared, offline-first shopping-list app. Accounts (email + password) own
shopping lists; lists can be shared with other accounts via email-bound
invites (flat access — you either have access or you don't, no roles). Each
list is a registry of every item ever added to it, with items moving between
**backlog** (known, not on the list), **todo** (on the list), and **checked**
(bought) as you shop.

It is **self-hosted**: you run the server, and your phone points at it. There is
no central service.

The **wire contract** — every endpoint, the field-clock shape, and the invite
token format, which the server implements and both clients consume — is
[`docs/wire-contract.md`](docs/wire-contract.md). Point-in-time design and
planning documents are kept in
[`docs/archive/`](docs/archive/README.md) as history.

## Parts

| Path | What | Docs |
|------|------|------|
| `server/` | Flask blueprint + SQLite backend: accounts, sync, sharing. Mountable at any URL, including multiple isolated instances on one app. One built wheel is a **single deployable artifact**: it embeds and serves the web client, the invite landing page, and the Android APK download. | [`server/README.md`](server/README.md) |
| `web/` | React web client. Not deployed separately — built straight into the server package and served by the blueprint itself. | [`web/README.md`](web/README.md) |
| `android/` | Native Android client (Kotlin/Compose), offline-first with a local Room mirror. Installable straight from a running server at `/shoppinglist.apk`. | [`android/README.md`](android/README.md) |

## Setup

```bash
git clone https://github.com/dpiegdon/shoppinglist
cd shoppinglist
```

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

## Point the app at your own server

The Android app's login screen prefills a server URL on first run. That default
is one constant:

```
android/app/src/main/java/org/p23q/shoppinglist/ui/login/LoginViewModel.kt
    const val DEFAULT_SERVER_URL = "https://p23q.org/shopping"
```

Change it to your own instance before building the APK. It is only a **prefill** —
still editable on the login screen, and replaced by whatever URL the user actually
logs into — so users of a stock build can always point themselves at your server
by hand. There is one test asserting the value, in
`android/app/src/test/java/org/p23q/shoppinglist/ui/login/LoginViewModelTest.kt`;
update it to match, or `./verify-all.sh` will fail.

Nothing else is host-specific. The Android package name (`org.p23q.shoppinglist`)
and the APK signing certificate carry the original domain, but neither affects
where the app connects — rename them only if you intend to publish your own build
somewhere the original could also be installed.

## Building the full bundle

One built wheel is the **whole deployable artifact** — server, embedded web
client, invite landing page, and the Android APK download, in one file. The web
bundle and the APK are committed inputs with their own, much slower toolchains, so
`build-wheel.sh` deliberately does *not* rebuild them. The full sequence, from a
clean checkout:

```bash
# 1. Web client -> server/src/shoppinglist_server/web_dist/  (needs Node)
cd web && npm install && npm run build && cd ..

# 2. Android release APK -> embedded copy  (needs JDK 17 + Android SDK, and a
#    signing keystore: see android/README.md)
cd android && ./gradlew :app:assembleRelease && cd ..
cp android/app/build/outputs/apk/release/app-release.apk \
   server/src/shoppinglist_server/apk/shoppinglist.apk

# 3. Verify everything (server pytest, web vitest+tsc+lint, Android test+lint)
./verify-all.sh

# 4. Build and self-check the wheel -> server/dist/*.whl
./build-wheel.sh
```

Steps 1 and 2 are only needed when the web client or the app actually changed —
but if you skip one after changing it, the wheel silently ships the *previous*
build of that part. `build-wheel.sh` catches a stale or mismatched web bundle and
a missing APK; it cannot catch a bundle you simply forgot to rebuild. That is why
`release.sh` (below) always rebuilds both rather than deciding whether it needs to.

`./smoke-wheel.sh [wheel] [expected-version]` is the other half of the check:
`build-wheel.sh` verifies what *landed* in the wheel, and this installs it into a
throwaway venv and exercises it — schema, register, login, a sync round-trip, and
every embedded artifact actually being served. Failures of that kind are invisible
from the source tree the test suite runs in.

Then install the wheel on the deployment host and mount the blueprint from your
Flask app — see [`server/README.md`](server/README.md).

## Versioning and releases

This project follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`).
Because one built wheel is a single deployable artifact, every part shares **one**
version number rather than versioning independently. A release bumps that single
version in both `server/pyproject.toml` and `android/app/build.gradle.kts`
(`versionName`, plus an incremented integer `versionCode`), and is tagged once as
`vX.Y.Z`.

Cutting one is `./release.sh`:

```bash
./release.sh 1.13.0 T-147     # version, and the ticket the release is filed under
```

It refuses to start on a dirty tree, off `main`, or if the tag already exists;
bumps every version file (`server/pyproject.toml`, `android/app/build.gradle.kts`,
`web/package.json`); rebuilds the web bundle; runs `verify-all.sh`; builds the
signed release APK; and only then embeds it — after checking with `aapt2` that the
APK really carries the new `versionName`/`versionCode`, and with `apksigner` that
it is signed **with the same key as the release it replaces** (Android will not
update an installed app across a key change, so that mistake would strand every
existing user). Finally it builds the wheel, smoke-tests it in a clean venv, then
commits and writes the annotated `vX.Y.Z` tag.

`--notes FILE` supplies the tag message; otherwise it is the commit subjects since
the previous tag.

Everything it does is the sequence above, in order, without the opportunity to
skip a step — which is the point. It stops at the tag.

## Testing

`./verify-all.sh` runs every suite (server lint + pytest, web vitest + tsc +
lint, Android unit tests + lint) in one command, fastest-first, and stops at the
first failure. Each can also be run on its own — see the per-part READMEs.

The server's lint stage is isort, black, ruff and ty, in `--check` mode: it
reports, it does not rewrite. `cd server && .venv/bin/isort . && .venv/bin/black .`
fixes layout.

The Android stage needs a JDK: export `JAVA_HOME`, or leave it unset and let
Gradle find one on your `PATH`.

## License

[MIT](LICENSE) © 2026 David R. Piegdon.

The vendored ticket tracker in `.agents/skills/gittoc/` is a separate MIT-licensed
project ([codeberg.org/dpiegdon/gittoc](https://codeberg.org/dpiegdon/gittoc)) and
carries its own `LICENSE.txt`.
