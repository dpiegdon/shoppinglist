"""Mounting the whole instance under a path prefix (T-60).

base_url's path component is the mount root: with base_url=".../shopping/",
the SPA, its assets, the invite landing page, the APK download, and (by
default) the API all live under /shopping — and the domain root stays
untouched for the host app.
"""

from flask import Flask

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module

PW = "password123"


def _make_app(tmp_path, **kwargs):
    database_path = str(tmp_path / "prefix_test.db")
    conn = db_module.connect(database_path)
    db_module.init_db(conn)
    conn.close()

    flask_app = Flask(__name__)
    flask_app.config["TESTING"] = True
    flask_app.register_blueprint(
        create_blueprint(
            database_path=database_path,
            invite_hmac_key=b"test-hmac-key",
            base_url="http://testserver/shopping/",
            **kwargs,
        )
    )
    return flask_app


def test_api_defaults_under_the_prefix(tmp_path):
    client = _make_app(tmp_path).test_client()

    resp = client.post(
        "/shopping/api/v1/register", json={"email": "a@example.com", "password": PW}
    )
    assert resp.status_code == 201

    # ...and is NOT at the domain root.
    resp = client.post("/api/v1/register", json={"email": "b@example.com", "password": PW})
    assert resp.status_code != 201


def test_explicit_url_prefix_still_wins(tmp_path):
    client = _make_app(tmp_path, url_prefix="/elsewhere/api").test_client()

    resp = client.post(
        "/elsewhere/api/register", json={"email": "a@example.com", "password": PW}
    )
    assert resp.status_code == 201


def test_spa_served_under_the_prefix_with_rewritten_urls(tmp_path):
    client = _make_app(tmp_path).test_client()

    resp = client.get("/shopping/")

    assert resp.status_code == 200
    body = resp.get_data(as_text=True)
    assert '<div id="root">' in body
    # Asset/favicon URLs are rewritten to live under the mount root...
    assert '"/shopping/assets/' in body
    assert '"/assets/' not in body
    # ...and the client is told its router basename via a meta tag.
    assert '<meta name="app-basename" content="/shopping">' in body


def test_config_is_a_meta_tag_not_an_inline_script(tmp_path):
    # The security CSP (T-45) blocks inline scripts in a real browser, so the config
    # MUST be carried by meta tags — an inline <script> silently failed in the field
    # while passing curl/jsdom tests. Guard against a regression to inline config.
    resp = _make_app(tmp_path).test_client().get("/shopping/")
    body = resp.get_data(as_text=True)

    assert '<meta name="app-basename"' in body
    assert "__APP_CONFIG__" not in body

    # And the CSP on that very response really is script-src-strict (no 'unsafe-inline'),
    # so nobody "fixes" a future inline script by weakening the policy instead.
    csp = resp.headers.get("Content-Security-Policy", "")
    assert "'unsafe-inline'" not in csp.split("style-src")[0]  # scripts fall back to default-src 'self'


def test_spa_fallback_for_deep_routes_under_the_prefix(tmp_path):
    client = _make_app(tmp_path).test_client()

    resp = client.get("/shopping/list/some-list-id")

    assert resp.status_code == 200
    assert '<div id="root">' in resp.get_data(as_text=True)


def test_domain_root_is_not_hijacked(tmp_path):
    client = _make_app(tmp_path).test_client()

    # The host app owns everything outside /shopping.
    assert client.get("/").status_code == 404
    assert client.get("/login").status_code == 404
    assert client.get("/shoppinglist.apk").status_code == 404


def test_apk_served_under_the_prefix(tmp_path):
    client = _make_app(tmp_path).test_client()

    resp = client.get("/shopping/shoppinglist.apk")

    assert resp.status_code == 200
    assert resp.content_type == "application/vnd.android.package-archive"


def test_invite_landing_and_its_links_live_under_the_prefix(tmp_path):
    client = _make_app(tmp_path).test_client()

    client.post("/shopping/api/v1/register", json={"email": "a@example.com", "password": PW})
    token = client.post(
        "/shopping/api/v1/login",
        json={"email": "a@example.com", "password": PW, "device_label": "dev"},
    ).get_json()["token"]
    auth = {"Authorization": f"Bearer {token}"}
    client.post(
        "/shopping/api/v1/sync",
        json={
            "cursor": 0, "device_id": "dev", "full_lists": [],
            "changes": {"lists": [{"id": "list-1", "fields": {
                "name": {"value": "Groceries", "updated_at": 100, "updated_by": "dev"}
            }}]},
        },
        headers=auth,
    )
    invite = client.post(
        "/shopping/api/v1/lists/list-1/invites",
        json={"invited_email": "b@example.com"},
        headers=auth,
    ).get_json()

    # The share URL that gets sent around embeds the prefix (built from base_url)...
    assert invite["url"] == f"http://testserver/shopping/invite/{invite['token']}"

    # ...and the landing page actually answers there, with prefixed links.
    resp = client.get(f"/shopping/invite/{invite['token']}")
    assert resp.status_code == 200
    body = resp.get_data(as_text=True)
    assert "/shopping/redeem?token=" in body
    assert "/shopping/shoppinglist.apk" in body
