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
    invite_hmac_key=b"...",       # see Configuration below
    base_url="https://lists.example.com",
    url_prefix="/api/v1",         # optional, this is the default
    serve_web_client=True,        # optional, this is the default — see "Web client" below
)
app.register_blueprint(bp)
app.cli.add_command(shoppinglist_cli)  # enables `flask shoppinglist ...`
```

That's the entire integration surface. Everything else — routes, the sync
engine, invite handling, GC — lives inside the package.

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
    serve_web_client=False, serve_invite_landing_page=False,
))
app.register_blueprint(create_blueprint(
    database_path="/var/lib/shoppinglist/tenant-b.db", invite_hmac_key=key_b,
    base_url="https://b.example.com", url_prefix="/tenant-b/api", name="tenant_b",
    serve_web_client=False, serve_invite_landing_page=False,
))
```

**One real constraint, not a bug:** the invite landing page (`/invite/<token>`)
and the embedded web client (`/`, `/assets/*`) are unprefixed, site-root
routes by design (Spec §5's share URL has no `/api/v1` segment) — there is
only one `/` per app. At most **one** mounted instance per app may set
`serve_web_client=True` / `serve_invite_landing_page=True`; a second attempt
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
| `SECRET_KEY` | Flask secret. |
| `INVITE_HMAC_KEY` | Server signing key for invite tokens. Keep it secret and stable — rotating it invalidates every outstanding invite. |
| `DATABASE_PATH` | SQLite file path. |

The standalone dev `app.py` (below) reads these from the environment; a host
app instead passes them as `create_blueprint(...)` arguments directly.

## Operator CLI

Registered on the host app via `app.cli.add_command(shoppinglist_cli)`:

```bash
flask --app app.py shoppinglist init-db               # create the schema (idempotent)
flask --app app.py shoppinglist reset-password <email> # print a new password (no email flow exists)
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
long cache lifetime. `/api/v1/*` and `/invite/<token>` both rank above this
catch-all — Werkzeug sorts routes by rule specificity, not registration
order — verified with real requests in `tests/test_webapp.py`, not just a
route dump. Pass `serve_web_client=False` to `create_blueprint(...)` to
disable it (e.g. a host app that wants to serve its own root content
instead); a package installed without ever running `npm run build` degrades
gracefully to the same effect rather than crashing.

The web client is same-origin with its own API by construction, so — unlike
the Android app — it has no server-URL setting.

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

## Deployment requirements

These are **not optional** — the blueprint does not implement them itself,
they are the deploying operator's responsibility:

- **A TLS-terminating reverse proxy is mandatory.** Bearer tokens must never
  travel in plaintext.
- **Login rate-limiting** should be added at the proxy; the blueprint does not
  rate-limit `/login` itself.

## Out of scope (v1)

- No email verification and no outbound email of any kind. Invites are
  delivered out of band by the inviter; password reset is operator-only via
  the CLI.
- No push/real-time — sync is client-initiated.
- No roles or granular permissions beyond list membership; members can only
  remove themselves (`leave`), not others.
