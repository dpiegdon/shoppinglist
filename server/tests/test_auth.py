import hashlib

import pytest

from shoppinglist_server import auth
from shoppinglist_server.errors import ApiError

EMAIL = "alice@example.com"
PASSWORD = "correct horse battery staple"
DEVICE = "test-device"


# ---- service layer -------------------------------------------------------


def test_register_creates_account_and_default_settings(db_conn):
    account_id = auth.register(db_conn, EMAIL, PASSWORD)

    account_row = db_conn.execute(
        "SELECT id, email, password_hash FROM accounts WHERE id = ?", (account_id,)
    ).fetchone()
    assert account_row is not None
    assert account_row["email"] == EMAIL
    assert account_row["password_hash"] != PASSWORD  # never stored plaintext

    settings_row = db_conn.execute(
        "SELECT default_currency FROM account_settings WHERE account_id = ?",
        (account_id,),
    ).fetchone()
    assert settings_row["default_currency"] == "EUR"


def test_register_duplicate_email_case_insensitive_raises_409(db_conn):
    auth.register(db_conn, EMAIL, PASSWORD)

    with pytest.raises(ApiError) as excinfo:
        auth.register(db_conn, "ALICE@example.com", PASSWORD)
    assert excinfo.value.status == 409


def test_login_returns_token_and_account_id(db_conn):
    account_id = auth.register(db_conn, EMAIL, PASSWORD)

    token, logged_in_account_id = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    assert logged_in_account_id == account_id
    assert isinstance(token, str)
    assert len(token) >= 32


def test_login_wrong_password_raises_401(db_conn):
    auth.register(db_conn, EMAIL, PASSWORD)

    with pytest.raises(ApiError) as excinfo:
        auth.login(db_conn, EMAIL, "wrong password", DEVICE)
    assert excinfo.value.status == 401


def test_login_unknown_email_raises_401(db_conn):
    with pytest.raises(ApiError) as excinfo:
        auth.login(db_conn, "nobody@example.com", PASSWORD, DEVICE)
    assert excinfo.value.status == 401


def test_token_is_stored_as_hash_not_plaintext(db_conn):
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    row = db_conn.execute("SELECT token_hash FROM auth_tokens").fetchone()
    assert row["token_hash"] != token
    assert row["token_hash"] == hashlib.sha256(token.encode("utf-8")).hexdigest()


def test_require_account_missing_header_raises_401(app, db_conn):
    with app.test_request_context("/", headers={}):
        from flask import request

        with pytest.raises(ApiError) as excinfo:
            auth.require_account(db_conn, request)
    assert excinfo.value.status == 401


def test_require_account_invalid_token_raises_401(app, db_conn):
    with app.test_request_context("/", headers={"Authorization": "Bearer not-a-real-token"}):
        from flask import request

        with pytest.raises(ApiError) as excinfo:
            auth.require_account(db_conn, request)
    assert excinfo.value.status == 401


def test_require_account_valid_token_returns_account(app, db_conn):
    account_id = auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    with app.test_request_context("/", headers={"Authorization": f"Bearer {token}"}):
        from flask import request

        account = auth.require_account(db_conn, request)

    assert account.id == account_id
    assert account.email == EMAIL


def _last_seen_at(db_conn, token):
    row = db_conn.execute(
        "SELECT last_seen_at FROM auth_tokens WHERE token_hash = ?",
        (auth.hash_token(token),),
    ).fetchone()
    return row["last_seen_at"]


def test_require_account_first_request_after_login_keeps_sane_last_seen_at(
    app, db_conn, monkeypatch
):
    login_time = 1_000_000
    monkeypatch.setattr(auth, "now_ms", lambda: login_time)
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    with app.test_request_context("/", headers={"Authorization": f"Bearer {token}"}):
        from flask import request

        auth.require_account(db_conn, request)

    # Login already stamped a fresh last_seen_at; the very next request (still
    # within the staleness window) should leave it exactly as login set it —
    # sane, not null, not clobbered.
    assert _last_seen_at(db_conn, token) == login_time


def test_require_account_within_threshold_does_not_update_last_seen_at(
    app, db_conn, monkeypatch
):
    login_time = 1_000_000
    monkeypatch.setattr(auth, "now_ms", lambda: login_time)
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    just_under_threshold = login_time + auth.LAST_SEEN_REFRESH_MS - 1
    monkeypatch.setattr(auth, "now_ms", lambda: just_under_threshold)
    with app.test_request_context("/", headers={"Authorization": f"Bearer {token}"}):
        from flask import request

        auth.require_account(db_conn, request)

    assert _last_seen_at(db_conn, token) == login_time


def test_require_account_after_threshold_updates_last_seen_at(app, db_conn, monkeypatch):
    login_time = 1_000_000
    monkeypatch.setattr(auth, "now_ms", lambda: login_time)
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    past_threshold = login_time + auth.LAST_SEEN_REFRESH_MS + 1
    monkeypatch.setattr(auth, "now_ms", lambda: past_threshold)
    with app.test_request_context("/", headers={"Authorization": f"Bearer {token}"}):
        from flask import request

        auth.require_account(db_conn, request)

    assert _last_seen_at(db_conn, token) == past_threshold


# ---- HTTP layer ------------------------------------------------------------


def test_register_login_logout_http_flow(client):
    resp = client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    assert resp.status_code == 201
    account_id = resp.get_json()["account_id"]

    resp = client.post(
        "/api/v1/login",
        json={"email": EMAIL, "password": PASSWORD, "device_label": DEVICE},
    )
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["account_id"] == account_id
    assert body["email"] == EMAIL
    token = body["token"]

    # Happy-path authed request.
    resp = client.post("/api/v1/logout", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 204

    # Revoked token no longer authenticates.
    resp = client.post("/api/v1/logout", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 401


def test_register_duplicate_email_http_409(client):
    client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    resp = client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    assert resp.status_code == 409
    assert resp.get_json()["error"]


def test_login_wrong_password_http_401(client):
    client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    resp = client.post(
        "/api/v1/login",
        json={"email": EMAIL, "password": "wrong password", "device_label": DEVICE},
    )
    assert resp.status_code == 401


def _closed_registration_app(tmp_path):
    from flask import Flask

    from shoppinglist_server import create_blueprint
    from shoppinglist_server import db as db_module

    database_path = str(tmp_path / "closed.db")
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
            allow_registration=False,
        )
    )
    return app


def test_allow_registration_false_rejects_register_with_403(tmp_path):
    client = _closed_registration_app(tmp_path).test_client()

    resp = client.post(
        "/api/v1/register", json={"email": "new@example.com", "password": "password123"}
    )

    assert resp.status_code == 403
    body = resp.get_json()
    assert body["error"] == "registration_disabled"


def test_allow_registration_false_still_allows_login(tmp_path, monkeypatch):
    app = _closed_registration_app(tmp_path)
    client = app.test_client()

    # Seed an account directly (registration is closed), then log in normally.
    from shoppinglist_server import auth as auth_module
    from shoppinglist_server import get_config_by_name
    from shoppinglist_server import db as db_module

    config = get_config_by_name(app)
    conn = db_module.connect(config["database_path"])
    auth_module.register(conn, "existing@example.com", "password123")
    conn.close()

    resp = client.post(
        "/api/v1/login",
        json={"email": "existing@example.com", "password": "password123", "device_label": "dev"},
    )
    assert resp.status_code == 200


def test_allow_registration_false_is_advertised_to_the_web_client(tmp_path):
    client = _closed_registration_app(tmp_path).test_client()

    body = client.get("/").get_data(as_text=True)

    # Carried as a meta tag, not an inline script: the security CSP blocks inline
    # scripts in a real browser (see routes/webapp.py).
    assert '<meta name="app-allow-registration" content="false">' in body
