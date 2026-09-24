# shoppinglist-server

A Flask **blueprint** with a **SQLite** backend: accounts, shared lists
(shopping lists, checklists, expense lists), offline-first field-level
last-write-wins sync, and email-bound invites. It is a library — mount it into
any host Flask app — plus a minimal standalone `app.py` for local development.

**The web client is embedded and served by the blueprint itself.** Opening
the server's base URL in a browser boots the React SPA directly — there is no
separate static-hosting deployment step. See "Web client" below.

The **wire contract** (every endpoint's exact request/response JSON, the
field-clock shape, and the invite token format) that both this server and its
clients implement lives in [`docs/wire-contract.md`](../docs/wire-contract.md).
The original design spec, which explains *why* the sync and access models look
the way they do, is archived at
[`docs/archive/specs/2026-07-08-shopping-list-server-design.md`](../docs/archive/specs/2026-07-08-shopping-list-server-design.md).

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
    admin_emails=["you@example.com"],    # optional — who gets the Server admin screen; see Configuration
    max_content_length=4 * 1024 * 1024,  # optional, default — request-body cap of THIS blueprint's routes
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
(`/shoppinglist.apk`) are site-root routes by design (the share URL has no
`/api/v1` segment) — there is only one `/` per app. At most
**one** mounted instance per app may set `serve_web_client=True` /
`serve_invite_landing_page=True` / `serve_android_apk=True`; a second attempt
raises a clear `ValueError` rather than Flask's raw endpoint-collision error.
Every other route (auth, account, lists, invites, sync) has no such
constraint and scales to as many instances as you mount.

**Sharing the app with other services.** Mounting the blueprint changes nothing
about how a co-mounted blueprint behaves: the body cap, the error handlers, the
per-request database teardown and the values kept on `flask.g` are all scoped to
this blueprint's own routes (and prefixed on `g`), the invite template lives
under a package-named folder, and the app's config is never written. The
security-headers hook is the one app-wide callback Flask cannot scope, and it acts
only on routes this package owns. The exception is the site-root routes above:
mounted at a bare domain (`base_url` without a path) the web client's catch-all
answers every unmatched `GET` on the app, so a host that serves its own pages
passes `serve_web_client=False`, or mounts under a path such as `/shopping`.

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
| `admin_emails` | Argument only. The instance's admins, matched case-insensitively against the logged-in account's email on every request — the only way to grant admin, so no API call can escalate privilege. Admins get the Server admin screen on both clients: registration on/off (until restart), reset a user's password, delete a user. |
| `max_content_length` | Argument only: the request-body cap, in bytes, of **this blueprint's own routes** (default **4 MB**, so a host is protected without proxy tuning). It is applied per request and nothing else: the host app's `MAX_CONTENT_LENGTH` is neither written nor read, so a co-mounted service keeps its own limit (or none) and a host that raises its own limit does not loosen this one. Oversized requests get a `413 payload_too_large` JSON error. Needs Flask 3.1 or newer. |
| `SECRET_KEY` | Read by the dev `app.py` only, as ordinary Flask hygiene. The blueprint itself never uses Flask sessions or cookies (auth is bearer tokens), so it does not depend on this value. |

The standalone dev `app.py` (below) reads these from the environment; a host
app instead passes them as `create_blueprint(...)` arguments directly.

### About `invite_hmac_key`

Invite links are **stateless signed tokens**: the server HMAC-SHA256-signs the
invite payload (invite id, list, invited email, a fixed 7-day expiry) with this
key, and later verifies redemptions against it — no invite state has to exist
server-side for the *link* to be checkable. Consequences:

- **Any byte string works; use a real random one** (32+ bytes), e.g.
  `python -c 'import secrets; print(secrets.token_hex(32))'`. The dev `app.py`
  **refuses to start** when `INVITE_HMAC_KEY` is unset or still the published
  dev default, rather than running perfectly and wholly insecurely: a forgotten
  variable is an operator mistake that has to be loud. A host app that builds the
  blueprint itself is responsible for the same check.
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
flask --app app.py shoppinglist audit                  # check database invariants (read-only)
```

### Housekeeping: what runs by itself, and when

Both of the above also run by themselves, driven by ordinary traffic — there is
no background thread and no cron job to install:

- **The retention GC** (`gc`) runs at most about once a day, on the first
  authenticated request after that day has passed. It hard-deletes tombstones
  past the 45-day retention window, invites that have been dead (used, expired
  or revoked) for a week, and sessions past their idle window.
- **The full housekeeping sweep** — the retention GC plus the invariant audit —
  runs on the first authenticated request of a server run, and about weekly
  after that. Its findings go to the audit log (see Audit log below) as
  `housekeeping.violation` records; the one violation it repairs, a live list
  that has no members left, is tombstoned rather than deleted and logged as
  `housekeeping.repair`.

Both triggers hang off *authenticated* requests, so a server nobody is using
sweeps nothing. That is deliberate: if nobody is signed in, nothing is being
created either, and the sweep happens as soon as somebody comes back.

`audit` is read-only and exits non-zero when it finds a violation, so it is the
one worth wiring into cron or a monitoring check if you want to be told about a
problem rather than finding it in the log. Running `gc` from cron is still
optional, and only buys a tidier database on a server that is going unused.

## Protocol version

The client/server interface carries an integer version, `PROTOCOL_VERSION` in
`shoppinglist_server/protocol.py`. Every API request must declare it as
`X-Client-Protocol: <integer>`; a request that declares nothing, something
malformed, or an older version is answered

```json
{"error": "client_outdated", "message": "...", "protocol": 3}
```

with status `426`, decided before authentication and before the database is
opened. `GET /app-version` (which reports `protocol` alongside `version`) and the
site-root routes — the web client, the invite landing page, the APK — are exempt,
so an outdated app can still find its update and a browser can still load the
SPA.

Both clients carry the same constant (`web/src/api/protocol.ts`, the Android
client's `Protocol.kt`). A client newer than the server is **not** refused, so
upgrade the server first. The number is bumped only by a change an installed
client of the previous protocol cannot handle, which makes that release a major
one; `../release.sh` refuses a release whose version and protocol disagree. The
policy and the changelog live in
[`docs/wire-contract.md`](../docs/wire-contract.md).

## Deploying and upgrading

1. Build the wheel (see the root README) and `pip install` it on the host.
2. Mount the blueprint from your host app, as above, behind a TLS proxy (see
   Deployment requirements).
3. For a new database, run `flask shoppinglist init-db` once.

To upgrade, install the new wheel and restart. An existing database migrates
itself the first time the new version opens it; take a backup first (see
Backups), since migrations only run forward. Each migration is one transaction,
so a failed one — a crash, or the busy timeout while another worker holds the
write lock — leaves the database exactly as it was and the next connection
retries it rather than the process dying in a state that needs an operator's
hand. The same write lock also serializes several workers that start together
and race to migrate the same file: the one that waits re-checks the schema
version once it has the lock, so it skips work the winner already committed
instead of replaying it. That closes the failure mode where a half-applied
migration bricked the database until someone hand-stamped `PRAGMA user_version`
— it does **not** by itself make a *rolling* restart safe, since a worker still
running the previous wheel's code is not guaranteed to work against a schema the
new wheel has already migrated. Stop every worker, install, and start them
again, rather than replacing them one at a time. A **major** upgrade also raises
the protocol version (see above), so every installed app has to be updated with
it; until it is, it is answered `426 client_outdated` and pointed at the
download.

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
  -H 'Content-Type: application/json' -H 'X-Client-Protocol: 3' \
  -d '{"email": "you@example.com", "password": "a long password"}'

curl -s -X POST http://localhost:5000/api/v1/login \
  -H 'Content-Type: application/json' -H 'X-Client-Protocol: 3' \
  -d '{"email": "you@example.com", "password": "a long password", "device_label": "curl"}'
```

`X-Client-Protocol` is required on every API call (see Protocol version above);
without it the server answers `426 client_outdated`. The second call returns a
bearer token; pass it as `Authorization: Bearer <token>` on every other endpoint
(see the wire contract linked above for the full list).

## Web client

The React app in `../web/` builds straight into
`src/shoppinglist_server/web_dist/` (see `web/vite.config.ts`'s `outDir`) —
those built assets are committed to this repo and shipped as package data
(`pyproject.toml`), the same way `templates/shoppinglist_server/invite.html` is. No Node/npm is
needed to *run* the server; it's only needed to *rebuild* the web client:

```bash
cd web
npm install
npm run build   # writes into ../server/src/shoppinglist_server/web_dist/
```

`routes/webapp.py`, registered on the host app at the site root, serves the
built `index.html` for `/` and any unmatched `GET` (the SPA's client-side
routing), and the hashed `/assets/*` bundle with a long cache lifetime. The API,
`/invite/<token>` and `/shoppinglist.apk` all rank above this catch-all, because
Werkzeug sorts routes by specificity, not registration order. Pass
`serve_web_client=False` to disable it; a package built without the web bundle
behaves the same way rather than crashing.

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
./build-wheel.sh             # repo root -> server/dist/shoppinglist_server-<version>-py3-none-any.whl
pip install server/dist/shoppinglist_server-*.whl   # on the deployment host
```

Use the script rather than `pip wheel` directly. setuptools reuses `build/`
across builds and only ever *adds* to it, so a file deleted from `src/` gets
packaged again; the script cleans first, then fails if `web_dist/assets/` and
`index.html` disagree or if `schema.sql`, the migrations, the invite template or
the APK are missing. It does *not* rebuild the web bundle or the APK —
`../release.sh` does both, and embeds the APK at
`src/shoppinglist_server/apk/shoppinglist.apk`.

The download URL is stable (no content hash) and served with a short cache
lifetime, so a new APK reaches users promptly.

### Telling the app an update exists

Installed apps have no app store to ask, so they ask the server they already
sync with. `GET /api/v1/app-version` answers with the version this server
carries and the absolute URL to fetch it:

```json
{"version": "1.12.0", "download_url": "https://example.com/shopping/shoppinglist.apk", "protocol": 3}
```

It is unauthenticated (like `/registration-status`) and exempt from the protocol
gate, since an app refused as outdated finds its update here. The version reported is
this **package's** version rather than one parsed out of the APK — the single
artifact shares one version number, so those are the same thing. The Android
client checks on foreground, at most twice a day, and offers each new version
once; opening its Settings checks right away and offers a skipped version again.
Users can turn the check off there.

Nothing needs configuring: the endpoint exists whenever the APK does. It answers
`404 no_app_package` when the instance serves no APK, and that answer still
carries `protocol`: the app asks for it before signing in, so an instance with
`serve_android_apk` off is still one the app can use.

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

`tests/test_full_system.py` runs the whole lifecycle end to end: two accounts,
invite and redeem, concurrent offline edits, convergence, both members leaving,
and the tombstone purge.

### Linting and formatting

Four tools, all installed by the `dev` extra and all configured in
`pyproject.toml`:

```bash
isort . && black .          # fix layout and import order
ruff check . && ty check .  # lint and type-check
```

black is the authority on layout: isort runs in black's profile, and ruff only
lints (its formatter and import rules are off). Run isort before black.

`../verify-all.sh` runs all four in `--check` mode as its first stage, so a
badly formatted tree fails the build without anything being rewritten under you.

## Backups

The database is a single SQLite file (`DATABASE_PATH`), running in WAL mode
(`PRAGMA journal_mode = WAL`) — a raw file copy taken while the server is
running can catch it mid-write. Use SQLite's own online backup instead,
which is safe to run against a live database with no downtime. This needs the
**`sqlite3` command-line tool** on the host — it is not one of this package's
Python dependencies and is not installed by `pip install`, so add it separately
(`apt install sqlite3`, `brew install sqlite3`, or your distro's equivalent):

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
- **Run it under a real WSGI server** — gunicorn, uWSGI, waitress, or similar —
  with one or more worker processes; `server/app.py`'s `flask run` and
  `dev_tls_server.py` are single-process development servers only. Worker
  processes add concurrency for **reading and for request handling**, not for
  writing: SQLite's WAL journal mode (which this package always turns on)
  allows any number of concurrent readers but only **one writer at a time**
  across every worker process touching a given database file. A
  write that arrives while another is in progress waits out SQLite's busy
  timeout and then answers `503 server_busy` with `Retry-After: 2` — an
  expected outcome under concurrent load, not a fault, and always safe to
  retry (see "What the blueprint does handle itself" below).
- **The database file must live on a local filesystem.** WAL mode relies on
  shared-memory locking between the processes that open it, which network
  filesystems (NFS, SMB, and similar) do not implement correctly — writing
  `DATABASE_PATH` there risks silent corruption, not just poor performance.
- **Login rate-limiting** should be added at the proxy; the blueprint does not
  rate-limit `/login` itself. Note each attempt against an existing account costs
  a full scrypt verify (~32 MB, ~200 ms), so this is a resource lever as well as
  a guessing one.
- **`Strict-Transport-Security` belongs at the proxy.** The blueprint sets the
  other security headers itself (below) but deliberately not this one: it cannot
  tell whether it was actually reached over HTTPS, and asserting HSTS over plain
  HTTP is wrong.
- **`ProxyFix` if you want meaningful client IPs.** The audit log records
  `request.remote_addr`, which behind a proxy is the *proxy's* address unless the
  host app wraps the WSGI app in
  `werkzeug.middleware.proxy_fix.ProxyFix`. Only do so if the proxy is trusted to
  set `X-Forwarded-For` — otherwise clients can forge it.

What the blueprint *does* handle itself (so you don't have to at the proxy, and
should avoid double-setting):

- **Request body cap** — 4 MB by default, for this blueprint's own routes only
  (`max_content_length`, see Configuration).
- **Security headers** — responses for this blueprint's own routes carry
  `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer` (the
  latter keeps the secret token in an `/invite/<token>` URL out of the `Referer`
  header); HTML responses (the invite landing page and the embedded web client)
  additionally carry a `Content-Security-Policy` and `X-Frame-Options: DENY`.
  All are set with `setdefault`, so a header you set at the proxy is not
  overwritten. The hook is scoped to the routes this extension owns — its API
  blueprint plus the site-root pages it serves — so mounting it alongside other
  blueprints does not impose its CSP or `Referrer-Policy` on their routes.
- **Session revocation on password change** — changing a password revokes all of
  the account's other sessions, keeping only the one that made the change. The
  operator `reset-password` CLI signs out all devices.
- **Idle sessions expire** — after 7 days unused on the web, 62 days on Android
  and for clients that don't say what they are. Any request slides the window.

- **Request-body row cap** — a `/sync` push carries at most 250 rows; larger
  batches get `422 too_many_changes` and clients split them. The byte cap
  bounds bytes, which is the wrong unit: applying a batch holds SQLite's single
  write lock throughout, so an unbounded one stalls every other write.
- **Write contention** answers `503 server_busy` + `Retry-After`, not an opaque
  500, so a client knows retrying is worthwhile.
- **`Cache-Control: no-store`** on every response except the ones that chose
  their own caching (hashed assets, the APK, the SPA index).

Known, accepted trade-off:

- **Account enumeration.** Registration returns `409 email_taken` for an
  address already in use, and the operator `reset-password` CLI reports whether
  an email exists — both reveal account existence. This is deliberate: with no
  outbound email infrastructure (see Out of scope) there's no non-enumerating
  alternative for these flows. `/login` itself does *not* enumerate: it returns
  the same `invalid_credentials` for an unknown email and a wrong password, and
  performs the same scrypt verify either way, so the two are not distinguishable
  by timing.
- **Invite addresses are a claim, not an identity.** Nothing verifies that an
  invited email belongs to the account that holds it, so `GET /invites/pending`
  hands out an invite's token only to an account that already held the address
  when the invite was minted (`accounts.email_set_at`). An invite to an address
  with no account yet is reachable only through the link the inviter passes on
  out of band, which is the check that email verification would otherwise
  provide.

## Audit log

Security-relevant events go to the standard `logging` logger
`shoppinglist_server.audit` at INFO. The blueprint only *emits* — routing is the
host app's job:

```python
import logging
handler = logging.FileHandler("/var/log/shoppinglist/audit.log")
handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
logging.getLogger("shoppinglist_server.audit").addHandler(handler)
```

A plain `FileHandler` never rotates: every `401`/`403` writes an `authz.denied`
record (see below), so an instance exposed to the open internet accumulates one
line per credential-stuffing or scanner request indefinitely. Use
`logging.handlers.RotatingFileHandler` or `TimedRotatingFileHandler` in place of
`FileHandler` above (same `addHandler` call) — this package only emits to the
logger, so rotation policy is entirely the host app's choice.

Recorded: `account.registered`, `auth.login`, `auth.logout`,
`auth.session_revoked`, `account.password_changed`, `account.email_changed`,
`account.deleted`, `invite.minted`, `invite.redeemed`, `invite.revoked`,
`admin.registration_toggled`, `admin.password_reset`, `admin.user_deleted`,
`db.contention`, and `authz.denied` for every 401/403 (carrying the error code,
so failed logins and cross-account attempts are both visible).

Each record is one line of `key=value` pairs. Some values are client-chosen
(the `platform` a login declares, the `path` a denied request asked for), so
every value is escaped before it is written: spaces and control characters —
newlines above all — become `\n`-style escapes, so a value can neither add a
field nor start a line, and a value longer than 200 characters is cut
with a `...[truncated]` marker. One request can neither forge a second record
nor bloat the log.

**The log contains no email addresses and no credentials**, by construction —
accounts appear as opaque ids and forbidden keys are redacted even if a future
call site passes them. That is deliberate: logs are usually retained longer and
guarded less than the database, and everything this server stores is personal
data. Resolve an id to a person via `GET /admin/users` when you actually need to.

## Ledgers

A list whose `kind` is `expenses` is a ledger: it holds shared money rather than
things to buy, and the clients render balances from it. Each item is one entry,
carrying who paid or received what, who owes or is credited with what, and which
of three types it is:

- **`expense`** — someone paid for the group, the original case, and what an
  entry with no type at all means.
- **`income`** — the group received money (a refund, a deposit, a sale). The
  same shape, counting the other way.
- **`transfer`** — one member handed another money to settle up: exactly one
  sender, exactly one different recipient, and nothing spent overall.

Amounts are always positive; the type carries the sign. The type lives inside
the same single last-write-wins field as the amounts, so it can never drift
apart from them.

Three rules are enforced here rather than in the clients, because they are what
the feature means:

- **It cannot be converted.** A list is created as a ledger or never becomes
  one, in either direction — the item shapes are incompatible.
- **It is closed by unanimous vote** (`POST /lists/{id}/close-votes`). Once
  every current member has agreed, the list becomes a read-only archive: no
  writes, no new members, and pending invite links stop redeeming. Closing is
  final in this version.
- **It cannot be left while open, or deleted at all.** Leaving becomes possible
  once the list is closed, and the last member out orphans it as usual. Deleting
  an *account* is never refused, whatever it is a member of; the departed id
  stays in the entries it was part of.

While the list is open, anyone who has voted to close — or who has left — has
their amounts frozen: no write may change what they paid or owe, so an entry
naming them cannot be deleted either, and neither can its type be changed, which
moves everyone in it. Someone who has voted also changes nothing on the list
themselves until they withdraw. That is what makes agreeing to close mean
something.

The wire shapes, error codes and the exact freeze rule are in
[`../docs/wire-contract.md`](../docs/wire-contract.md).

## Out of scope (v1)

- No email verification and no outbound email of any kind. Invites are
  delivered out of band by the inviter; password reset is operator-only via
  the CLI.
- No push/real-time — sync is client-initiated.
- No roles or granular permissions beyond list membership; members can only
  remove themselves (`leave`), not others.
