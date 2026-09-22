#!/usr/bin/env bash
# Smoke-tests a built wheel in a throwaway venv (T-142).
#
# build-wheel.sh checks what LANDED in the wheel. This checks that the wheel,
# once installed somewhere that is not this source tree, actually works: the
# blueprint mounts, the schema self-initializes, an account can be created, a
# sync round-trips, and every embedded artifact (web bundle, invite page, APK)
# is really served. Those are the failures a packaging mistake produces, and
# none of them can be seen from the source checkout the test suite runs in.
#
# Deliberately a separate venv with ONLY the wheel and Flask installed: running
# it against server/.venv would import the editable source tree and prove
# nothing about the package.
#
#   ./smoke-wheel.sh [path/to/wheel] [expected-version]
#
# With no arguments it takes the newest wheel in server/dist/.
set -uo pipefail
cd "$(dirname "$0")" || exit 1

WHEEL="${1:-}"
EXPECTED_VERSION="${2:-}"
if [ -z "$WHEEL" ]; then
  WHEEL=$(ls -t server/dist/*.whl 2>/dev/null | head -1)
fi
if [ -z "$WHEEL" ] || [ ! -f "$WHEEL" ]; then
  echo "no wheel to test — pass one, or run ./build-wheel.sh first" >&2
  exit 1
fi
WHEEL=$(cd "$(dirname "$WHEEL")" && pwd)/$(basename "$WHEEL")

VENV=$(mktemp -d)
trap 'rm -rf "$VENV"' EXIT

echo "=== installing $(basename "$WHEEL") into a clean venv ==="
python3 -m venv "$VENV" || exit 1
# Flask itself still comes from PyPI — this venv is deliberately not the source
# tree's — but its version is pinned to whatever server/.venv already resolved,
# rather than left to float, when that venv is available (T-281).
FLASK_PIN=""
if [ -x server/.venv/bin/pip ]; then
  FLASK_PIN=$(server/.venv/bin/pip show flask 2>/dev/null | sed -n 's/^Version: //p')
fi
if [ -n "$FLASK_PIN" ]; then
  "$VENV/bin/pip" install -q "$WHEEL" "flask==$FLASK_PIN" || exit 1
else
  "$VENV/bin/pip" install -q "$WHEEL" || exit 1
fi

echo
echo "=== exercising the installed package ==="
EXPECTED_VERSION="$EXPECTED_VERSION" "$VENV/bin/python" - <<'PY'
import os
import sys
import tempfile
from importlib.metadata import version

from flask import Flask

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server.protocol import PROTOCOL_HEADER, PROTOCOL_VERSION

failures = []
checks = 0


def check(label, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print(f"  ok    {label}")
    else:
        print(f"  FAIL  {label}" + (f" — {detail}" if detail else ""))
        failures.append(label)


installed = version("shoppinglist-server")
expected = os.environ.get("EXPECTED_VERSION") or ""
if expected:
    check(f"installed version is {expected}", installed == expected, f"got {installed}")
else:
    print(f"  ..    installed version {installed} (no expectation given)")

# Mounted under a prefix on purpose: every absolute URL the package builds has to
# survive it, and a root-only smoke test would not catch a prefix bug. The prefix
# comes from base_url's path — register_blueprint takes no url_prefix of its own.
tmp = tempfile.mkdtemp()
db_path = os.path.join(tmp, "smoke.db")

# The schema comes from schema.sql inside the wheel, applied the way a deployment
# does it (`flask shoppinglist init-db`) rather than by the dev server's shortcut.
# A wheel that forgot to package schema.sql or the migrations fails right here.
conn = db_module.connect(db_path)
try:
    db_module.init_db(conn)
finally:
    conn.close()
check("the packaged schema initializes a fresh database", os.path.getsize(db_path) > 0)

app = Flask(__name__)
app.register_blueprint(
    create_blueprint(
        database_path=db_path,
        invite_hmac_key=b"smoke-key",
        base_url="http://smoke.example.com/shopping/",
    )
)
c = app.test_client()
# Every API request declares the protocol (T-243); without the header the gate answers 426
# before the route runs, which is what the check below proves.
c.environ_base[f"HTTP_{PROTOCOL_HEADER.upper().replace('-', '_')}"] = str(PROTOCOL_VERSION)
API = "/shopping/api/v1"

# --- the API answers ------------------------------------------------------
r = c.get(f"{API}/registration-status")
check("registration-status answers 200", r.status_code == 200, str(r.status_code))
check("no-store on an API response", r.headers.get("Cache-Control") == "no-store")

r = c.post(f"{API}/register", json={"email": "smoke@example.com", "password": "smoke-password"})
check("register creates an account", r.status_code == 201, r.get_data(as_text=True)[:120])

r = c.post(
    f"{API}/login",
    json={"email": "smoke@example.com", "password": "smoke-password", "device_label": "smoke"},
)
check("login returns a token", r.status_code == 200 and "token" in (r.get_json() or {}))
token = (r.get_json() or {}).get("token", "")
account_id = (r.get_json() or {}).get("account_id", "")
auth = {"Authorization": f"Bearer {token}"}

# --- a full sync round-trip ---------------------------------------------
clock = {"updated_at": 1, "updated_by": "smoke-device"}
r = c.post(
    f"{API}/sync",
    headers=auth,
    json={
        "cursor": 0,
        "device_id": "smoke-device",
        "changes": {
            "lists": [
                {
                    "id": "list-1",
                    "created_at": 1,
                    "fields": {"name": {"value": "Groceries", **clock}},
                }
            ],
            "items": [
                {
                    "id": "item-1",
                    "list_id": "list-1",
                    "created_at": 1,
                    "fields": {
                        "name": {"value": "Milk", **clock},
                        "status": {"value": "todo", **clock},
                    },
                }
            ],
        },
    },
)
check("sync accepts a push", r.status_code == 200, r.get_data(as_text=True)[:160])

r = c.post(f"{API}/sync", headers=auth, json={"cursor": 0, "device_id": "other-device"})
body = r.get_json() if r.status_code == 200 else {}
lists = body.get("changes", {}).get("lists", [])
items = body.get("changes", {}).get("items", [])
check("sync returns the pushed list", any(x["id"] == "list-1" for x in lists))
check("sync returns the pushed item", any(x["id"] == "item-1" for x in items))
check(
    "the item round-tripped its name",
    any(x["fields"]["name"]["value"] == "Milk" for x in items if x["id"] == "item-1"),
)

# --- an expenses list, end to end (T-151, T-157) -------------------------
# Solo on purpose: one member means one vote closes the list, so this covers the
# alone-on-a-list case and the whole close path in the same round trip.
expense = {
    "paid_by": {account_id: "42.00"},
    "equal_by": False,
    "paid_for": {account_id: "42.00"},
    "equal_for": True,
    "date": "2026-09-17",
}
r = c.post(
    f"{API}/sync",
    headers=auth,
    json={
        "cursor": 0,
        "device_id": "smoke-device",
        "changes": {
            "lists": [
                {
                    "id": "trip-1",
                    "created_at": 2,
                    "fields": {
                        "name": {"value": "Trip", **clock},
                        "kind": {"value": "expenses", **clock},
                        "currency": {"value": "EUR", **clock},
                    },
                }
            ],
            "items": [
                {
                    "id": "exp-1",
                    "list_id": "trip-1",
                    "created_at": 2,
                    "fields": {
                        "name": {"value": "Dinner", **clock},
                        "expense": {"value": expense, **clock},
                    },
                }
            ],
        },
    },
)
check("sync accepts an expenses list and an expense", r.status_code == 200, r.get_data(as_text=True)[:160])


def pull_trip():
    resp = c.post(f"{API}/sync", headers=auth, json={"cursor": 0, "device_id": "other-device"})
    changes = (resp.get_json() or {}).get("changes", {}) if resp.status_code == 200 else {}
    rows = [x for x in changes.get("lists", []) if x["id"] == "trip-1"]
    expenses = [x for x in changes.get("items", []) if x["id"] == "exp-1"]
    return (rows[0] if rows else {}), (expenses[0] if expenses else {})


trip, exp = pull_trip()
check("the expenses list round-trips its currency", trip.get("fields", {}).get("currency", {}).get("value") == "EUR")
# The roster rides on the list row, which is what lets both clients name a payer offline.
check("the expenses list carries its roster", len(trip.get("members", [])) == 1, repr(trip.get("members"))[:120])
got = exp.get("fields", {}).get("expense", {}).get("value") or {}
check("the expense round-trips its amounts", got.get("paid_for") == {account_id: "42.00"}, repr(got)[:160])
check("the expense round-trips its equal-split flag", got.get("equal_for") is True, repr(got)[:160])

r = c.post(f"{API}/lists/trip-1/close-votes", headers=auth)
check("a close vote is accepted", r.status_code == 200, r.get_data(as_text=True)[:160])
trip, _ = pull_trip()
check("the sole member's vote closes the list", trip.get("closed_at") is not None, repr(trip.get("closed_at")))

# A closed list is a read-only archive — the wheel has to enforce that, not just describe it.
r = c.post(
    f"{API}/sync",
    headers=auth,
    json={
        "cursor": 0,
        "device_id": "smoke-device",
        "changes": {
            "items": [
                {
                    "id": "exp-2",
                    "list_id": "trip-1",
                    "created_at": 3,
                    "fields": {
                        "name": {"value": "Breakfast", "updated_at": 9, "updated_by": "smoke-device"},
                        "expense": {"value": expense, "updated_at": 9, "updated_by": "smoke-device"},
                    },
                }
            ]
        },
    },
)
body = r.get_json() or {}
check(
    "a write to a closed list is refused",
    r.status_code == 422 and body.get("error") == "list_closed",
    f"{r.status_code} {r.get_data(as_text=True)[:120]}",
)

# --- embedded artifacts --------------------------------------------------
r = c.get("/shopping/")
check("the web client index is served", r.status_code == 200 and b"<div id=\"root\"" in r.data)

index_html = r.get_data(as_text=True) if r.status_code == 200 else ""
asset = ""
for piece in index_html.split('"'):
    if "/assets/" in piece and (piece.endswith(".js") or piece.endswith(".css")):
        asset = piece
        break
check("index.html references a hashed bundle", bool(asset), index_html[:120])
if asset:
    path = asset if asset.startswith("/shopping") else "/shopping" + asset.lstrip(".")
    r = c.get(path)
    check(f"the bundle it references is served ({path.rsplit('/', 1)[-1]})", r.status_code == 200, str(r.status_code))

r = c.get("/shopping/favicon.svg")
check("the favicon is served", r.status_code == 200)

r = c.get("/shopping/shoppinglist.apk")
check("the APK is served", r.status_code == 200 and r.data[:2] == b"PK", str(r.status_code))

# --- the update endpoint, and that what it advertises actually exists -----
r = c.get(f"{API}/app-version")
check("app-version answers 200", r.status_code == 200, str(r.status_code))
if r.status_code == 200:
    payload = r.get_json()
    check(
        "app-version reports the installed version",
        payload.get("version") == installed,
        f"{payload.get('version')} != {installed}",
    )
    check(
        "app-version reports the protocol version",
        payload.get("protocol") == PROTOCOL_VERSION,
        f"{payload.get('protocol')} != {PROTOCOL_VERSION}",
    )
    url = payload.get("download_url", "")
    check(
        "its download_url is absolute and prefix-aware",
        url == "http://smoke.example.com/shopping/shoppinglist.apk",
        url,
    )
    # Follow it for real, by path, rather than trusting the string.
    r2 = c.get("/" + url.split("/", 3)[-1] if url.count("/") > 2 else "/shopping/shoppinglist.apk")
    check("the advertised URL really serves the APK", r2.status_code == 200 and r2.data[:2] == b"PK")

# --- invite landing page + security headers ------------------------------
r = c.get("/shopping/invite/not-a-real-token")
check("the invite landing page renders", r.status_code in (200, 400, 404) and b"<html" in r.data.lower())
check("HTML carries a CSP", "Content-Security-Policy" in r.headers)
check("the invite page sends no referrer", r.headers.get("Referrer-Policy") == "no-referrer")

r = c.get(f"{API}/lists")
check("an unauthenticated API call is refused", r.status_code == 401, str(r.status_code))
check("errors use the JSON envelope", set(r.get_json() or {}) >= {"error", "message"})

# --- the protocol gate ----------------------------------------------------
# A client that declares nothing is every app built before 3.0.0.
old = app.test_client()
r = old.get(f"{API}/lists")
check("a client with no protocol header is refused", r.status_code == 426, str(r.status_code))
check(
    "the refusal names the server's protocol",
    (r.get_json() or {}).get("error") == "client_outdated"
    and (r.get_json() or {}).get("protocol") == PROTOCOL_VERSION,
    r.get_data(as_text=True)[:120],
)
r = old.get(f"{API}/app-version")
check("app-version stays reachable by an outdated client", r.status_code == 200, str(r.status_code))
r = old.get("/shopping/")
check("the web client stays reachable by a browser", r.status_code == 200, str(r.status_code))

print()
if failures:
    print(f"{len(failures)} of {checks} checks FAILED: {', '.join(failures)}")
    sys.exit(1)
print(f"all {checks} checks passed")
PY
STATUS=$?

echo
if [ $STATUS -eq 0 ]; then
  echo "wheel smoke test PASSED"
else
  echo "wheel smoke test FAILED" >&2
fi
exit $STATUS
