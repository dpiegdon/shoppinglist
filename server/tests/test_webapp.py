"""Tests for the embedded web client serving (routes/webapp.py, W5/T-26).

Route-precedence is the critical property here: the SPA catch-all must never
shadow /api/v1/* or /invite/<token>, even though Werkzeug ranks by rule
specificity rather than registration order — verified with real requests,
not just a route dump.
"""

import os

from flask import Flask

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server.routes.webapp import DEFAULT_WEB_DIST_DIR


def _make_app(tmp_path, serve_web_client=True, web_dist_dir=None):
    database_path = str(tmp_path / "webapp_test.db")
    conn = db_module.connect(database_path)
    db_module.init_db(conn)
    conn.close()

    flask_app = Flask(__name__)
    flask_app.config["TESTING"] = True
    bp = create_blueprint(
        database_path=database_path,
        invite_hmac_key=b"test-hmac-key",
        base_url="http://testserver",
        serve_web_client=serve_web_client,
        web_dist_dir=web_dist_dir,
    )
    flask_app.register_blueprint(bp)
    return flask_app


def test_web_dist_is_actually_built():
    # Sanity precondition for every other test in this file: `npm run build`
    # must have been run so web_dist/index.html exists.
    assert os.path.isfile(os.path.join(DEFAULT_WEB_DIST_DIR, "index.html")), (
        "server/src/shoppinglist_server/web_dist/index.html is missing — "
        "run `npm run build` in web/ first."
    )


def test_serves_index_html_at_root(tmp_path):
    app = _make_app(tmp_path)
    client = app.test_client()

    resp = client.get("/")

    assert resp.status_code == 200
    assert resp.content_type.startswith("text/html")
    body = resp.get_data(as_text=True)
    assert '<div id="root">' in body


def test_serves_spa_fallback_for_client_side_routes(tmp_path):
    app = _make_app(tmp_path)
    client = app.test_client()

    resp = client.get("/list/some-list-id/registry")

    assert resp.status_code == 200
    assert resp.content_type.startswith("text/html")
    assert '<div id="root">' in resp.get_data(as_text=True)


def test_serves_hashed_asset_files(tmp_path):
    app = _make_app(tmp_path)
    client = app.test_client()

    assets_dir = os.path.join(DEFAULT_WEB_DIST_DIR, "assets")
    js_files = [f for f in os.listdir(assets_dir) if f.endswith(".js")]
    assert js_files, "expected at least one built JS asset"

    resp = client.get(f"/assets/{js_files[0]}")

    assert resp.status_code == 200
    assert "javascript" in resp.content_type


def test_favicon_served(tmp_path):
    app = _make_app(tmp_path)
    client = app.test_client()

    resp = client.get("/favicon.svg")

    assert resp.status_code == 200


def test_api_routes_unaffected_by_spa_catchall(tmp_path):
    app = _make_app(tmp_path)
    client = app.test_client()

    resp = client.get("/api/v1/lists")

    assert resp.status_code == 401
    assert resp.content_type.startswith("application/json")


def test_invite_landing_page_unaffected_by_spa_catchall(tmp_path):
    app = _make_app(tmp_path)
    client = app.test_client()

    client.post("/api/v1/register", json={"email": "a@example.com", "password": "password123"})
    token_resp = client.post(
        "/api/v1/login",
        json={"email": "a@example.com", "password": "password123", "device_label": "dev"},
    )
    token = token_resp.get_json()["token"]
    auth = {"Authorization": f"Bearer {token}"}

    client.post(
        "/api/v1/sync",
        json={
            "cursor": 0, "device_id": "dev", "full_lists": [],
            "changes": {"lists": [{"id": "list-1", "fields": {
                "name": {"value": "Groceries", "updated_at": 100, "updated_by": "dev"}
            }}]},
        },
        headers=auth,
    )
    invite_resp = client.post(
        "/api/v1/lists/list-1/invites", json={"invited_email": "b@example.com"}, headers=auth
    )
    token_str = invite_resp.get_json()["token"]

    resp = client.get(f"/invite/{token_str}")

    assert resp.status_code == 200
    assert resp.content_type.startswith("text/html")
    body = resp.get_data(as_text=True)
    assert token_str in body
    assert '<div id="root">' not in body  # this is the S7 landing page, not the SPA


def test_serve_web_client_false_disables_root_route(tmp_path):
    app = _make_app(tmp_path, serve_web_client=False)
    client = app.test_client()

    resp = client.get("/")
    assert resp.status_code == 404

    # API is unaffected either way.
    resp = client.get("/api/v1/lists")
    assert resp.status_code == 401


def test_missing_web_dist_dir_skips_registration_without_crashing(tmp_path):
    missing_dir = str(tmp_path / "nonexistent_web_dist")
    app = _make_app(tmp_path, web_dist_dir=missing_dir)
    client = app.test_client()

    resp = client.get("/")
    assert resp.status_code == 404

    resp = client.get("/api/v1/lists")
    assert resp.status_code == 401
