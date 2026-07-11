"""Tests for the invite landing page (S7)."""

PW = "password123"


def _auth(token):
    return {"Authorization": f"Bearer {token}"}


def _register_and_login(client, email="owner@example.com", device="dev"):
    client.post("/api/v1/register", json={"email": email, "password": PW})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": PW, "device_label": device}
    )
    return resp.get_json()["token"]


def _mint_invite(client, token, list_id="list-1", invited_email="invitee@example.com"):
    client.post(
        "/api/v1/sync",
        json={
            "cursor": 0, "device_id": "dev", "full_lists": [],
            "changes": {
                "lists": [
                    {"id": list_id, "fields": {
                        "name": {"value": "Groceries", "updated_at": 100, "updated_by": "dev"}
                    }}
                ]
            },
        },
        headers=_auth(token),
    )
    resp = client.post(
        f"/api/v1/lists/{list_id}/invites",
        json={"invited_email": invited_email},
        headers=_auth(token),
    )
    return resp.get_json()


def test_valid_token_renders_html_with_full_token(client):
    owner_token = _register_and_login(client)
    invite = _mint_invite(client, owner_token)

    resp = client.get(f"/invite/{invite['token']}")
    assert resp.status_code == 200
    assert resp.content_type.startswith("text/html")
    body = resp.get_data(as_text=True)
    assert invite["token"] in body
    assert "org.p23q.shoppinglist" in body
    assert "invitee@example.com" in body


def test_valid_token_offers_redeem_in_browser_when_web_client_is_served(client):
    owner_token = _register_and_login(client)
    invite = _mint_invite(client, owner_token)

    body = client.get(f"/invite/{invite['token']}").get_data(as_text=True)

    # The default test app serves the web client, so the landing page links to its redeem route (T-44).
    assert "Redeem in your browser" in body
    assert "/redeem?token=" in body


def test_no_redeem_in_browser_link_when_web_client_not_served(tmp_path):
    from flask import Flask

    from shoppinglist_server import create_blueprint
    from shoppinglist_server import db as db_module

    database_path = str(tmp_path / "noweb.db")
    conn = db_module.connect(database_path)
    db_module.init_db(conn)
    conn.close()
    app = Flask(__name__)
    app.config["TESTING"] = True
    app.register_blueprint(
        create_blueprint(
            database_path=database_path,
            invite_hmac_key=b"test-hmac-key",
            base_url="http://testserver",
            serve_web_client=False,
        )
    )
    client = app.test_client()

    owner_token = _register_and_login(client)
    invite = _mint_invite(client, owner_token)
    body = client.get(f"/invite/{invite['token']}").get_data(as_text=True)

    # The valid invite still renders, but with no browser-redeem link (the SPA isn't served here).
    assert invite["token"] in body
    assert "Redeem in your browser" not in body


def test_tampered_token_404(client):
    owner_token = _register_and_login(client)
    invite = _mint_invite(client, owner_token)
    payload, _sig = invite["token"].split(".", 1)
    tampered = payload + ".not-a-real-signature"

    resp = client.get(f"/invite/{tampered}")
    assert resp.status_code == 404
    assert resp.content_type.startswith("text/html")


def test_expired_token_410(client, monkeypatch):
    owner_token = _register_and_login(client)
    invite = _mint_invite(client, owner_token)

    from shoppinglist_server.routes import landing

    monkeypatch.setattr(landing, "now_ms", lambda: invite["expires_at"] + 1)

    resp = client.get(f"/invite/{invite['token']}")
    assert resp.status_code == 410
    assert resp.content_type.startswith("text/html")


def test_garbage_token_404(client):
    resp = client.get("/invite/not-a-real-token")
    assert resp.status_code == 404
    assert resp.content_type.startswith("text/html")


def test_json_api_prefix_unaffected(client):
    resp = client.get("/api/v1/lists")
    assert resp.status_code == 401

    resp = client.post(
        "/api/v1/register", json={"email": "checkjson@example.com", "password": PW}
    )
    assert resp.status_code == 201
    assert resp.content_type.startswith("application/json")
