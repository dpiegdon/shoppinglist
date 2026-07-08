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
