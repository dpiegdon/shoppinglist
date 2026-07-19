# shoppinglist-server

A Flask **blueprint** with a **SQLite** backend implementing the shopping-list
server: accounts, shared lists, offline-first field-level last-write-wins
sync, and email-bound invites. It is a library — mount it into any host Flask
app — plus a minimal standalone `app.py` for local development.

**The web client is embedded and served by the blueprint itself.** Opening
the server's base URL in a browser boots the React SPA directly — there is no
separate static-hosting deployment step. See "Web client" below.

The full design is documented in
[`docs/superpowers/specs/2026-07-08-shopping-list-server-design.md`](../docs/superpowers/specs/2026-07-08-shopping-list-server-design.md).
The **wire contract** (every endpoint's exact request/response JSON, the
field-clock shape, and the invite token format) that both this server and its
clients implement lives in
[`docs/superpowers/plans/2026-07-08-shopping-list-tickets.md`](../docs/superpowers/plans/2026-07-08-shopping-list-tickets.md#wire-contract-shared-interface--server-implements-both-clients-consume).

## Installing

```bash
pip install -e ".[dev]"   # from the server/ directory
```

## Mounting the blueprint into a host app

```python
from flask import Flask
from shoppinglist_server import create_blueprint
from shoppinglist_server.cli import shoppinglist_cli

app = Flask(__name__)

bp = create_blueprint(
    database_path="/var/lib/shoppinglist/shoppinglist.db",
    invite_hmac_key=b"...",              # signs invite tokens — see Configuration below
    base_url="https://lists.example.com",  # public URL clients reach you at — see Configuration below
    url_prefix=None,                     # optional; defaults to "<base_url path>/api/v1"
    name="shoppinglist_server",          # optional; must be unique per app when mounting several instances
    serve_web_client=True,               # optional, default — the embedded SPA at the mount root
    serve_invite_landing_page=True,      # optional, default — the ".../invite/<token>" landing page
    serve_android_apk=True,              # optional, default — the app download at ".../shoppinglist.apk"
                                         #   (see "Android app download" below)
    web_dist_dir=None,                   # optional — serve the web client from a custom directory
                                         #   instead of the bundle embedded in this package
    allow_registration=True,             # optional; False = invite/operator-only instance — POST
                                         #   /register returns 403 and the web login page says so
)
app.register_blueprint(bp)
app.cli.add_command(shoppinglist_cli)  # enables `flask shoppinglist ...`
```

That's the entire integration surface. Everything else — routes, the sync
engine, invite handling, GC — lives inside the package.

### Mounting under a path prefix

The **path component of `base_url` is the mount root**. To serve the whole
instance under `https://example.com/shopping` (say, alongside other apps on
the same domain), just say so in `base_url`:

```python
bp = create_blueprint(
    database_path="shopping/shopping.db",
    invite_hmac_key=b"...",
    base_url="https://example.com/shopping/",
)
```

Everything follows: the web client is served at `/shopping/` (with its asset
URLs and client-side router scoped to the prefix — logging in navigates to
`/shopping/login`, not `/login`), invites at `/shopping/invite/<token>`, the
APK at `/shopping/shoppinglist.apk`, and the API defaults to
`/shopping/api/v1` (pass `url_prefix` explicitly to override). The domain
root stays untouched for the rest of your app. Android users enter
`https://example.com/shopping` as their server URL — the app builds its API
calls relative to it.

### Multiple instances on one app

`create_blueprint(...)` is safe to call more than once and register multiple
fully independent instances on the same Flask app — each with its own
`database_path`, `invite_hmac_key`, and `url_prefix`. There is no shared
mutable state: each instance's config is resolved per-request from the
blueprint that matched, so instances never see each other's data, tokens, or
signing key, even under concurrent load.

Each instance beyond the first needs a distinct `name=` (Flask requires
unique blueprint names per app — this mirrors that requirement directly):

```python
app.register_blueprint(create_blueprint(
    database_path="/var/lib/shoppinglist/tenant-a.db", invite_hmac_key=key_a,
    base_url="https://a.example.com", url_prefix="/tenant-a/api", name="tenant_a",
    serve_web_client=False, serve_invite_landing_page=False, serve_android_apk=False,
))
app.register_blueprint(create_blueprint(
    database_path="/var/lib/shoppinglist/tenant-b.db", invite_hmac_key=key_b,
    base_url="https://b.example.com", url_prefix="/tenant-b/api", name="tenant_b",
    serve_web_client=False, serve_invite_landing_page=False, serve_android_apk=False,
))
```

**One real constraint, not a bug:** the invite landing page (`/invite/<token>`),
the embedded web client (`/`, `/assets/*`), and the Android APK download
(`/shoppinglist.apk`) are unprefixed, site-root routes by design (Spec §5's
share URL has no `/api/v1` segment) — there is only one `/` per app. At most
**one** mounted instance per app may set `serve_web_client=True` /
`serve_invite_landing_page=True` / `serve_android_apk=True`; a second attempt
raises a clear `ValueError` rather than Flask's raw endpoint-collision error.
Every other route (auth, account, lists, invites, sync) has no such
constraint and scales to as many instances as you mount.

The operator CLI needs to know which instance to target once more than one
is registered — pass `--instance NAME` (matching the `name=` above); with
exactly one instance mounted (the common case) it's inferred automatically:

```bash
flask --app app.py shoppinglist init-db --instance tenant_a
```

See `tests/test_multi_mount.py` for the full set of isolation guarantees this
is tested against (account/token/settings/invite-key isolation across
instances, and the CLI's instance-selection behavior).

## Configuration

| Key | Purpose |
|-----|---------|
| `DATABASE_PATH` / `database_path` | SQLite file path. Created (with schema) by `init-db`; the parent directory must exist and be writable. |
| `INVITE_HMAC_KEY` / `invite_hmac_key` | The signing key for invite tokens — see below. |
| `BASE_URL` / `base_url` | The absolute public URL clients reach this server at (scheme + host, plus any mount path; a trailing `/` is tolerated). Used to build the invite **share URLs** (`<base_url>/invite/<token>`) and the landing page's open-in-app link — get it wrong and invite links point somewhere unreachable. |
| `MAX_CONTENT_LENGTH` | Flask config key (not read from the env by the blueprint). The blueprint sets a **4 MB** default request-body cap so a host app is protected without proxy tuning; set this in the host app's Flask config to raise/lower it. Oversized requests get a `413 payload_too_large` JSON error. |
| `SECRET_KEY` | Read by the dev `app.py` only, as ordinary Flask hygiene. The blueprint itself never uses Flask sessions or cookies (auth is bearer tokens), so it does not depend on this value. |

The standalone dev `app.py` (below) reads these from the environment; a host
app instead passes them as `create_blueprint(...)` arguments directly.

### About `invite_hmac_key`

Invite links are **stateless signed tokens**: the server HMAC-SHA256-signs the
invite payload (invite id, list, invited email, a fixed 7-day expiry) with this
key, and later verifies redemptions against it — no invite state has to exist
server-side for the *link* to be checkable. Consequences:

- **Any byte string works; use a real random one** (32+ bytes), e.g.
  `python -c 'import secrets; print(secrets.token_hex(32))'`. Never ship the
  dev default.
- **Keep it secret.** Anyone holding the key can mint valid invite tokens for
  arbitrary lists.
- **Keep it stable, and back it up alongside the database.** Rotating (or
  losing) it invalidates every outstanding invite link — nothing else is
  affected (accounts, lists, sessions, and already-redeemed memberships all
  live in the database), so recovery is just re-sending invites.

## Operator CLI

Registered on the host app via `app.cli.add_command(shoppinglist_cli)`:

```bash
flask --app app.py shoppinglist init-db               # create the schema (idempotent)
flask --app app.py shoppinglist reset-password <email> # reset the password and sign out all devices (no email flow exists)
flask --app app.py shoppinglist gc                     # force tombstone garbage collection
```

`gc` also runs opportunistically (piggybacked on `POST /sync`, at most about
once a day), so a cron job is optional.

## Running the dev server

`server/app.py` is a minimal standalone host app, config via environment
variables, for local development and manual testing only:

```bash
cd server
DATABASE_PATH=dev.db INVITE_HMAC_KEY=dev-key BASE_URL=http://localhost:5000 \
  flask --app app.py run
```

It auto-initializes the schema on startup, so no separate `init-db` step is
needed. Try the register/login round trip:

```bash
curl -s -X POST http://localhost:5000/api/v1/register \
  -H 'Content-Type: application/json' \
  -d '{"email": "you@example.com", "password": "a long password"}'

curl -s -X POST http://localhost:5000/api/v1/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "you@example.com", "password": "a long password", "device_label": "curl"}'
```

The second call returns a bearer token; pass it as `Authorization: Bearer
<token>` on every other endpoint (see the wire contract linked above for the
full list).

## Web client

The React app in `../web/` builds straight into
`src/shoppinglist_server/web_dist/` (see `web/vite.config.ts`'s `outDir`) —
those built assets are committed to this repo and shipped as package data
(`pyproject.toml`), the same way `templates/invite.html` is. No Node/npm is
needed to *run* the server; it's only needed to *rebuild* the web client:

```bash
cd web
npm install
npm run build   # writes into ../server/src/shoppinglist_server/web_dist/
```

`routes/webapp.py`, registered directly on the host app (same reasoning as
the invite landing page: it must live at the site root, not under
`url_prefix`), serves the built `index.html` for `/` and any unmatched `GET`
(SPA client-side routing fallback), and the hashed `/assets/*` bundle with a
long cache lifetime. `/api/v1/*`, `/invite/<token>`, and `/shoppinglist.apk`
all rank above this catch-all — Werkzeug sorts routes by rule specificity,
not registration order — verified with real requests in
`tests/test_webapp.py` and `tests/test_apk.py`, not just a route dump. Pass `serve_web_client=False` to `create_blueprint(...)` to
disable it (e.g. a host app that wants to serve its own root content
instead); a package installed without ever running `npm run build` degrades
gracefully to the same effect rather than crashing.

The web client is same-origin with its own API by construction, so — unlike
the Android app — it has no server-URL setting.

## Android app download / single-artifact deploys

The Android release APK is shipped as package data too
(`src/shoppinglist_server/apk/shoppinglist.apk`) and served at
**`/shoppinglist.apk`** — linked from the web client's login page and from the
invite landing page, so new users can install the app straight from your
server. Pass `serve_android_apk=False` to `create_blueprint(...)` to disable;
a package built without the APK file degrades gracefully (no route, no links).

This makes one build of this package a **single deployable artifact** — API,
web client, invite landing page, and the app download in one wheel:

```bash
./build-wheel.sh             # repo root -> server/dist/shoppinglist_server-1.0.0-py3-none-any.whl
pip install server/dist/shoppinglist_server-*.whl   # on the deployment host
```

Use the script rather than calling `python -m build --wheel` / `pip wheel`
directly: it removes `build/` first and then checks what actually landed in the
wheel. **setuptools reuses `build/` across builds and only ever *adds* to it**,
so a file deleted from `src/` since the last build gets packaged again. That is
not hypothetical — the first 1.6.0 wheel shipped the previous release's
`web_dist/assets/index-*.js` alongside the current one, because `npm run build`
had replaced the content-hashed bundle and the old name lingered in `build/`
(T-105). `index.html` references bundles by hash, so the stale file was never
served and it cost size rather than correctness — but the same mechanism would
ship a wrong file the moment something is loaded by a stable path instead of a
hash. The script fails the build if `web_dist/assets/` and `index.html` disagree
in either direction, or if `schema.sql`, the migrations, the invite template, or
the APK are missing.

The script does *not* rebuild `web_dist` or the APK — those are committed
inputs with their own, much slower toolchains. Rebuild them first if the
release changes them (see above and `android/README.md`).

…then mount it from your host app as shown in "Mounting the blueprint" above.
(Copying the `src/shoppinglist_server/` directory into your Flask project works
too — everything the blueprint serves lives inside the package.)

**When a new Android release is built**, refresh the embedded copy:

```bash
cp android/app/build/outputs/apk/release/app-release.apk \
   server/src/shoppinglist_server/apk/shoppinglist.apk
```

The download URL is stable (no content hash) and served with a short cache
lifetime, so updated APKs propagate promptly.

## TLS dev server (for client-side testing)

`server/dev_tls_server.py` serves the same app over **HTTPS on port 8723**
with an ad-hoc self-signed certificate, bound to `0.0.0.0` — for Android/web
clients under development that need a real `https://` endpoint (e.g. to
exercise the invite landing page's App Link, which is hardcoded to
`scheme=https`). Requires the `dev` extra (`cryptography`).

```bash
cd server
python dev_tls_server.py
# Serving HTTPS (self-signed) on 0.0.0.0:8723, BASE_URL=https://localhost:8723
```

Uses its own database (`dev_tls.db`, override via `DATABASE_PATH`), separate
from the plain-HTTP dev server above. Clients must accept/ignore the
self-signed certificate (`curl -k`, or the client's debug-build cert-trust
config). Set `BASE_URL` if a device needs to reach it via the host's LAN IP
rather than `localhost`. Not for production — see Deployment requirements
below.

## Testing

```bash
pytest -v
```

`tests/test_full_system.py` runs the complete lifecycle end-to-end: two
accounts, list creation, invite + redeem, concurrent offline edits, sync
convergence, both members leaving (the second leave orphans the list), and
tombstone purge via `gc.run`.

## Backups

The database is a single SQLite file (`DATABASE_PATH`), running in WAL mode
(`PRAGMA journal_mode = WAL`) — a raw file copy taken while the server is
running can catch it mid-write. Use SQLite's own online backup instead,
which is safe to run against a live database with no downtime:

```bash
sqlite3 /var/lib/shoppinglist/shoppinglist.db ".backup /backups/shoppinglist-$(date +%F).db"
```

That's the only file you need to snapshot: WAL mode's `-wal`/`-shm` sidecar
files next to the main database are transient, and `.backup` already folds
their content into the output, so they don't need to be copied separately.

**Also back up `INVITE_HMAC_KEY` alongside the database** — it isn't stored
in the database, and losing it invalidates every outstanding invite link
(see "About `invite_hmac_key`" above); nothing else is affected, and
everything else recovers from the database backup alone.

## Deployment requirements

These are **not optional** — the blueprint does not implement them itself,
they are the deploying operator's responsibility:

- **A TLS-terminating reverse proxy is mandatory.** Bearer tokens must never
  travel in plaintext.
- **Login rate-limiting** should be added at the proxy; the blueprint does not
  rate-limit `/login` itself.

What the blueprint *does* handle itself (so you don't have to at the proxy, and
should avoid double-setting):

- **Request body cap** — a 4 MB `MAX_CONTENT_LENGTH` default (see Configuration
  to override).
- **Security headers** — every response carries `X-Content-Type-Options:
  nosniff` and `Referrer-Policy: no-referrer` (the latter keeps the secret token
  in an `/invite/<token>` URL out of the `Referer` header); HTML responses (the
  invite landing page and the embedded web client) additionally carry a
  `Content-Security-Policy` and `X-Frame-Options: DENY`. All are set with
  `setdefault`, so a header you set at the proxy is not overwritten.
- **Session revocation on password change** — changing a password revokes all of
  the account's other sessions, keeping only the one that made the change. The
  operator `reset-password` CLI goes further: it resets the password and signs
  out all devices (there is no trusted "current" session to spare in that flow).

Known, accepted trade-off:

- **Account enumeration.** Registration returns `409 email_taken` for an
  address already in use, and the operator `reset-password` CLI reports whether
  an email exists — both reveal account existence. This is deliberate: with no
  outbound email infrastructure (see Out of scope) there's no non-enumerating
  alternative for these flows. `/login` itself does *not* enumerate (it returns
  the same `invalid_credentials` for an unknown email and a wrong password).

## Out of scope (v1)

- No email verification and no outbound email of any kind. Invites are
  delivered out of band by the inviter; password reset is operator-only via
  the CLI.
- No push/real-time — sync is client-initiated.
- No roles or granular permissions beyond list membership; members can only
  remove themselves (`leave`), not others.
