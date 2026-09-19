# Tuppu

Tuppu (Akkadian: a clay tablet) is a self-hosted app for shared lists, of three
kinds:

- **Shopping lists** remember every item ever added. Items move between
  *backlog* (known, not needed), *todo* (to buy) and *checked* (bought), and
  carry a category, stores, quantity and price.
- **Checklists** are the same without the shopping fields: packing lists,
  chores.
- **Expense lists** track shared costs: who paid what for whom, everyone's
  balance, and the transfers that settle it. The members close one by
  unanimous vote; after that it is a read-only record.

Lists are shared by invite link, and every member has the same access. You run
the server; a web client and an Android app talk to it. The Android app works
offline and syncs when it can.

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

## Quick start

```bash
cd server
pip install -e ".[dev]"
DATABASE_PATH=dev.db INVITE_HMAC_KEY=dev-key BASE_URL=http://localhost:5000 \
  flask --app app.py run
```

Then open <http://localhost:5000>. The built web client is committed, so running
the server needs no Node. Deploying for real (mounting, configuration, TLS,
backups, upgrades) is in [`server/README.md`](server/README.md).

- **Web:** `cd web && npm install && npm run build` rebuilds the bundle into the
  server package; `npm run dev` runs a dev server. See
  [`web/README.md`](web/README.md).
- **Android:** `cd android && ./gradlew assembleDebug` (JDK 17 and the Android
  SDK). Runs on Android 8.0 and newer. The login screen prefills
  `https://p23q.org/shopping`; to ship a build that prefills your own server,
  see [`android/README.md`](android/README.md).

## Testing

`./verify-all.sh` runs every suite (server lint and pytest, web vitest, tsc and
lint, Android unit tests and lint), fastest first, and stops at the first
failure. The server lint stage only checks; `cd server && .venv/bin/isort . &&
.venv/bin/black .` fixes the layout. The Android stage needs a JDK, from
`JAVA_HOME` or the `PATH`.

## Releases

Every part shares one version number, because one wheel ships them all
([SemVer](https://semver.org/), tagged `vX.Y.Z`).

```bash
./release.sh 1.17.0 T-nnn [--notes FILE]   # version, and the ticket the release is filed under
```

It bumps the version in every part, rebuilds the web bundle and the signed
release APK, and runs `verify-all.sh`. It then checks that the APK carries the
new version and is signed with the same key as the previous release, since
Android refuses an update signed with a different key. Finally it builds the wheel
(`build-wheel.sh`), smoke-tests it in a clean venv (`smoke-wheel.sh`), commits
and tags. It needs a clean tree on `main`, npm, the Android SDK and the release
keystore ([`android/README.md`](android/README.md)), and it touches no remote.
Without `--notes`, the tag message is the commit subjects since the last tag.

`build-wheel.sh` alone does not rebuild the web bundle or the APK, so a wheel
built by hand after skipping either ships the previous build of it.

## License

[MIT](LICENSE) © 2026 David R. Piegdon.

The vendored ticket tracker in `.agents/skills/gittoc/` is a separate MIT-licensed
project ([codeberg.org/dpiegdon/gittoc](https://codeberg.org/dpiegdon/gittoc)) and
carries its own `LICENSE.txt`.
