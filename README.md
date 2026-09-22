# Tuppu

Tuppu (Akkadian: a clay tablet) is a self-hosted app for shared lists, of three
kinds:

- **Shopping lists** remember every item ever added. Items move between
  *backlog* (known, not needed), *todo* (to buy) and *checked* (bought), and
  carry a category, stores, quantity and price.
- **Checklists** are the same without the shopping fields: packing lists,
  chores.
- **Ledgers** track shared money. Every entry is an *expense* (someone paid for
  the group), an *income* (money came in — a refund, a deposit, a sale) or a
  *transfer* (one member paid another). They give the net spent, everyone's
  balance and the transfers that settle it. The members close a ledger by
  unanimous vote; after that it is a read-only record.

Lists are shared by invite, addressed to an email: the invitee sees it on their
overview and joins there, or opens the invite link. Every member has the same
access. You run the server; a web client and an Android app talk to it. The
Android app works offline and syncs when it can.

## Parts

| Path | What |
|------|------|
| [`server/`](server/README.md) | Flask blueprint + SQLite. One built wheel is the whole deployment: API, web client, invite page and the Android APK download. |
| [`web/`](web/README.md) | React client, built into the server package and served by it. |
| [`android/`](android/README.md) | Kotlin/Compose client with a local Room mirror. Installable from a running server at `/shoppinglist.apk`. |
| `shared-test-cases/` | Case tables both clients are tested against (expense arithmetic, name order), so the two cannot drift apart. |
| [`docs/wire-contract.md`](docs/wire-contract.md) | The client/server interface, and the authority on it. Past design documents are in [`docs/archive/`](docs/archive/README.md). |

The repository, the Python package (`shoppinglist_server`), the APK's download
path and the Android application id keep the project's original name,
`shoppinglist`: changing the application id would strand every installed app.

## Running a release

A release tag already contains the built web bundle and the signed Android APK,
so building its wheel needs no Node, no Android SDK and no signing key — only
Python 3.11 or newer locally, plus network access for pip: `.[dev]` (which
includes the pinned build backend) and, inside `smoke-wheel.sh`, Flask, both come
from PyPI. `build-wheel.sh` itself builds with `--no-build-isolation`, so it does
not reach the network again once `.[dev]` is installed.

```bash
git checkout vX.Y.Z
cd server && python3 -m venv .venv && .venv/bin/pip install -e ".[dev]" && cd ..
./build-wheel.sh          # -> server/dist/shoppinglist_server-X.Y.Z-py3-none-any.whl
./smoke-wheel.sh          # installs it in a throwaway venv and exercises it
```

The APK inside the wheel is the one committed at the tag, byte for byte. It
cannot be rebuilt by anyone else with the same signature: the release keystore
is never committed, and Android refuses an update signed with a different key.
Installing and running the wheel (mounting, configuration, TLS, backups,
upgrades) is in [`server/README.md`](server/README.md).

## Building a checkout

`./bootstrap.sh` prepares a fresh machine for everything below and for the
tests: the server venv, the web dependencies, the Android SDK location, and a
check for what it cannot install (a JDK, a UTF-8 locale). `--android-sdk` also
downloads a minimal SDK. To run the server from source:

```bash
cd server
pip install -e ".[dev]"
DATABASE_PATH=dev.db INVITE_HMAC_KEY=dev-key BASE_URL=http://localhost:5000 \
  flask --app app.py run
```

Then open <http://localhost:5000>. It serves the committed web bundle, which
between releases lags `web/src`; `cd web && npm install && npm run build`
rebuilds it into the server package, and `npm run dev` runs a dev server. See
[`web/README.md`](web/README.md).

For the Android app, `cd android && ./gradlew assembleDebug` (JDK 17 and the
Android SDK). It runs on Android 8.0 and newer and prefills
`https://p23q.org/shopping` on the login screen; to ship a build that prefills
your own server, see [`android/README.md`](android/README.md).

## Testing

`./verify-all.sh` runs every suite (server lint and pytest, web vitest, tsc and
lint, a server wheel build and smoke test, Android debug and release unit tests
and lint), fastest first, and stops at the first failure. The server lint stage
only checks; `cd server && .venv/bin/isort . && .venv/bin/black .` fixes the
layout. The Android stage needs a JDK, from `JAVA_HOME` or the `PATH`, and also
runs `testReleaseUnitTest`, the only variant that compiles the release source
set — see [`android/README.md`](android/README.md) for what that proves.

## Making a release

Every part shares one version number, because one wheel ships them all
([SemVer](https://semver.org/), tagged `vX.Y.Z`).

```bash
./release.sh X.Y.Z T-nnn [--notes FILE]   # version, and the ticket the release is filed under
```

It bumps the version in every part, rebuilds the web bundle and the signed
release APK, and runs `verify-all.sh`. It then checks that the APK carries the
new version and is signed with the same key as the previous release. Finally it
builds the wheel (`build-wheel.sh`), smoke-tests it in a clean venv
(`smoke-wheel.sh`), commits and tags. It needs a clean tree on `main`, npm, the
Android SDK and the release keystore ([`android/README.md`](android/README.md)),
and it touches no remote. Without `--notes`, the tag message is the commit
subjects since the last tag.

`build-wheel.sh` alone does not rebuild the web bundle or the APK, so a wheel
built by hand after skipping either ships the previous build of it.

## License

[MIT](LICENSE) © 2026 David R. Piegdon.

The vendored ticket tracker in `.agents/skills/gittoc/` is a separate MIT-licensed
project ([github.com/dpiegdon/gittoc](https://github.com/dpiegdon/gittoc)) and
carries its own `LICENSE.txt`.
