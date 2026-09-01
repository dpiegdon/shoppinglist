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
cd "$(dirname "$0")"

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
"$VENV/bin/pip" install -q "$WHEEL" || exit 1

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
