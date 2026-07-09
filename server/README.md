# shoppinglist-server

A Flask **blueprint** with a **SQLite** backend implementing the shopping-list
server: accounts, shared lists, offline-first field-level last-write-wins
sync, and email-bound invites. It is a library — mount it into any host Flask
app — plus a minimal standalone `app.py` for local development.

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
)
app.register_blueprint(bp)
app.cli.add_command(shoppinglist_cli)  # enables `flask shoppinglist ...`
```

That's the entire integration surface. Everything else — routes, the sync
engine, invite handling, GC — lives inside the package.

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
