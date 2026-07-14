import pytest

from shoppinglist_server import accounts, auth
from shoppinglist_server.errors import ApiError

EMAIL = "bob@example.com"
PASSWORD = "correct horse battery staple"
DEVICE = "test-device"


def _register_and_login(conn, email=EMAIL, password=PASSWORD, device=DEVICE):
    account_id = auth.register(conn, email, password)
    token, _ = auth.login(conn, email, password, device)
    return account_id, token


def _insert_list_and_membership(conn, list_id, account_id):
    now = auth.now_ms()
    conn.execute(
        "INSERT INTO lists (id, created_at, change_seq, name, name_ts, name_by) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (list_id, now, 1, "Groceries", now, "dev-1"),
    )
    conn.execute(
        "INSERT INTO memberships (account_id, list_id, joined_at) VALUES (?, ?, ?)",
        (account_id, list_id, now),
    )
    conn.commit()


# ---- service layer: change password / email --------------------------------


def test_change_password_service_happy_path(db_conn):
    account_id, token = _register_and_login(db_conn)
    accounts.change_password(db_conn, account_id, PASSWORD, "a new password 2", token)

    row = db_conn.execute(
        "SELECT password_hash FROM accounts WHERE id = ?", (account_id,)
    ).fetchone()
    from werkzeug.security import check_password_hash

    assert check_password_hash(row["password_hash"], "a new password 2")


def test_change_password_wrong_current_raises_401(db_conn):
    account_id, token = _register_and_login(db_conn)
    with pytest.raises(ApiError) as excinfo:
        accounts.change_password(db_conn, account_id, "not the password", "a new password 2", token)
    assert excinfo.value.status == 401


def test_change_password_revokes_other_sessions_but_keeps_current(db_conn):
    account_id, token = _register_and_login(db_conn)
    # A second, older session on another device — the one we expect to be revoked.
    other_token, _ = auth.login(db_conn, EMAIL, PASSWORD, "other-device")

    accounts.change_password(db_conn, account_id, PASSWORD, "a new password 2", token)

    remaining = db_conn.execute(
        "SELECT token_hash FROM auth_tokens WHERE account_id = ?", (account_id,)
    ).fetchall()
    hashes = {row["token_hash"] for row in remaining}
    assert hashes == {auth.hash_token(token)}
    assert auth.hash_token(other_token) not in hashes


def test_change_email_service_happy_path(db_conn):
    account_id, _ = _register_and_login(db_conn)
    accounts.change_email(db_conn, account_id, PASSWORD, "newbob@example.com")

    row = db_conn.execute("SELECT email FROM accounts WHERE id = ?", (account_id,)).fetchone()
    assert row["email"] == "newbob@example.com"


def test_change_email_duplicate_raises_409(db_conn):
    account_id, _ = _register_and_login(db_conn)
    auth.register(db_conn, "other@example.com", PASSWORD)

    with pytest.raises(ApiError) as excinfo:
        accounts.change_email(db_conn, account_id, PASSWORD, "other@example.com")
    assert excinfo.value.status == 409


def test_change_email_wrong_password_raises_401(db_conn):
    account_id, _ = _register_and_login(db_conn)
    with pytest.raises(ApiError) as excinfo:
        accounts.change_email(db_conn, account_id, "not the password", "newbob@example.com")
    assert excinfo.value.status == 401


# ---- service layer: sessions ------------------------------------------------


def test_list_sessions_marks_current_token(db_conn):
    account_id, token = _register_and_login(db_conn)
    auth.login(db_conn, EMAIL, PASSWORD, "second-device")

    sessions = accounts.list_sessions(db_conn, account_id, token)

    assert len(sessions) == 2
    current = [s for s in sessions if s["current"]]
    assert len(current) == 1
    assert current[0]["device_label"] == DEVICE
    other = [s for s in sessions if not s["current"]][0]
    assert other["device_label"] == "second-device"


def test_revoke_session_removes_it_and_invalidates_token(db_conn, app):
    account_id, token = _register_and_login(db_conn)
    sessions = accounts.list_sessions(db_conn, account_id, token)
    session_id = sessions[0]["id"]

    accounts.revoke_session(db_conn, account_id, session_id)

    with app.test_request_context("/", headers={"Authorization": f"Bearer {token}"}):
        from flask import request

        with pytest.raises(ApiError) as excinfo:
            auth.require_account(db_conn, request)
    assert excinfo.value.status == 401


def test_revoke_session_unknown_id_raises_404(db_conn):
    account_id, _ = _register_and_login(db_conn)
    with pytest.raises(ApiError) as excinfo:
        accounts.revoke_session(db_conn, account_id, "not-a-real-session-id")
    assert excinfo.value.status == 404


# ---- service layer: settings ------------------------------------------------


def test_get_settings_default_currency_is_eur(db_conn):
    account_id, _ = _register_and_login(db_conn)
    # initials default to the email's local-part when never set (T-64) — bob@... -> "BO".
    assert accounts.get_settings(db_conn, account_id) == {"default_currency": "EUR", "initials": "BO"}


def test_update_settings_valid_currency(db_conn):
    account_id, _ = _register_and_login(db_conn)
    result = accounts.update_settings(db_conn, account_id, "USD")
    assert result == {"default_currency": "USD", "initials": "BO"}
    assert accounts.get_settings(db_conn, account_id) == {"default_currency": "USD", "initials": "BO"}


@pytest.mark.parametrize("bad_currency", ["usd", "US", "USDD", "", None])
def test_update_settings_invalid_currency_raises_422(db_conn, bad_currency):
    account_id, _ = _register_and_login(db_conn)
    with pytest.raises(ApiError) as excinfo:
        accounts.update_settings(db_conn, account_id, bad_currency)
    assert excinfo.value.status == 422


# ---- service layer: initials (T-64) -----------------------------------------


def test_default_initials_derived_from_email_local_part():
    assert accounts._default_initials("alice@example.com") == "AL"
    assert accounts._default_initials("a@example.com") == "A"


def test_update_settings_with_initials_override(db_conn):
    account_id, _ = _register_and_login(db_conn)
    result = accounts.update_settings(db_conn, account_id, "EUR", "XY")
    assert result == {"default_currency": "EUR", "initials": "XY"}


@pytest.mark.parametrize("bad_initials", ["ABCD", "TooLong"])
def test_update_settings_initials_over_max_length_raises_422(db_conn, bad_initials):
    account_id, _ = _register_and_login(db_conn)
    with pytest.raises(ApiError) as excinfo:
        accounts.update_settings(db_conn, account_id, "EUR", bad_initials)
    assert excinfo.value.status == 422
    assert excinfo.value.code == "invalid_initials"


def test_update_settings_initials_none_clears_override_back_to_default(db_conn):
    account_id, _ = _register_and_login(db_conn)
    accounts.update_settings(db_conn, account_id, "EUR", "XY")

    result = accounts.update_settings(db_conn, account_id, "EUR", None)

    assert result == {"default_currency": "EUR", "initials": "BO"}  # bob@... derived default


def test_settings_http_patch_with_initials(client):
    token = _register_and_login_http(client)
    resp = client.patch(
        "/api/v1/settings",
        json={"default_currency": "EUR", "initials": "XY"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    assert resp.get_json() == {"default_currency": "EUR", "initials": "XY"}


# ---- service layer: delete account ------------------------------------------


def test_delete_account_wrong_password_raises_401(db_conn):
    account_id, _ = _register_and_login(db_conn)
    with pytest.raises(ApiError) as excinfo:
        accounts.delete_account(db_conn, account_id, "not the password")
    assert excinfo.value.status == 401


def test_delete_account_cascades_and_orphans_memberships(db_conn):
    account_id, token = _register_and_login(db_conn)
    _insert_list_and_membership(db_conn, "list-1", account_id)

    accounts.delete_account(db_conn, account_id, PASSWORD)

    assert db_conn.execute(
        "SELECT 1 FROM accounts WHERE id = ?", (account_id,)
    ).fetchone() is None
    assert db_conn.execute(
        "SELECT 1 FROM account_settings WHERE account_id = ?", (account_id,)
    ).fetchone() is None
    assert db_conn.execute(
        "SELECT 1 FROM auth_tokens WHERE account_id = ?", (account_id,)
    ).fetchone() is None
    assert db_conn.execute(
        "SELECT 1 FROM memberships WHERE account_id = ?", (account_id,)
    ).fetchone() is None


# ---- CLI: reset-password -----------------------------------------------------


def test_reset_password_cli_allows_login_with_new_password(db_conn, cli_runner, app):
    auth.register(db_conn, EMAIL, PASSWORD)
    # The CLI operates on the app's own configured database, so register there too.
    from shoppinglist_server import db as db_module
    from shoppinglist_server import get_config_by_name

    config = get_config_by_name(app)

    app_conn = db_module.connect(config["database_path"])
    auth.register(app_conn, EMAIL, PASSWORD)
    app_conn.close()

    result = cli_runner.invoke(args=["shoppinglist", "reset-password", EMAIL])
    assert result.exit_code == 0
    assert f"New password for {EMAIL}" in result.output

    new_password = result.output.strip().rsplit(": ", 1)[1]

    app_conn = db_module.connect(config["database_path"])
    token, _ = auth.login(app_conn, EMAIL, new_password, DEVICE)
    assert token
    app_conn.close()


def test_reset_password_cli_unknown_email_fails(cli_runner):
    result = cli_runner.invoke(args=["shoppinglist", "reset-password", "nobody@example.com"])
    assert result.exit_code != 0


# ---- HTTP layer --------------------------------------------------------------


def _register_and_login_http(client, email=EMAIL, password=PASSWORD, device=DEVICE):
    client.post("/api/v1/register", json={"email": email, "password": password})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": password, "device_label": device}
    )
    return resp.get_json()["token"]


def test_change_password_http_flow(client):
    token = _register_and_login_http(client)
    resp = client.post(
        "/api/v1/account/change-password",
        json={"current_password": PASSWORD, "new_password": "a new password 2"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 204

    resp = client.post(
        "/api/v1/login",
        json={"email": EMAIL, "password": "a new password 2", "device_label": DEVICE},
    )
    assert resp.status_code == 200


def test_change_password_http_wrong_current_401(client):
    token = _register_and_login_http(client)
    resp = client.post(
        "/api/v1/account/change-password",
        json={"current_password": "nope", "new_password": "a new password 2"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 401


def test_change_password_http_invalidates_other_devices(client):
    token = _register_and_login_http(client)
    # A second device logs in, then the first device changes the password.
    other = client.post(
        "/api/v1/login",
        json={"email": EMAIL, "password": PASSWORD, "device_label": "other-device"},
    ).get_json()["token"]

    client.post(
        "/api/v1/account/change-password",
        json={"current_password": PASSWORD, "new_password": "a new password 2"},
        headers={"Authorization": f"Bearer {token}"},
    )

    # The other device's token is now revoked; the changing device stays authenticated.
    assert client.get(
        "/api/v1/account/sessions", headers={"Authorization": f"Bearer {other}"}
    ).status_code == 401
    assert client.get(
        "/api/v1/account/sessions", headers={"Authorization": f"Bearer {token}"}
    ).status_code == 200


def test_change_email_http_flow(client):
    token = _register_and_login_http(client)
    resp = client.post(
        "/api/v1/account/change-email",
        json={"password": PASSWORD, "new_email": "newbob@example.com"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 204


def test_change_email_http_duplicate_409(client):
    token = _register_and_login_http(client)
    client.post("/api/v1/register", json={"email": "other@example.com", "password": PASSWORD})
    resp = client.post(
        "/api/v1/account/change-email",
        json={"password": PASSWORD, "new_email": "other@example.com"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 409


def test_sessions_http_list_and_revoke(client):
    token = _register_and_login_http(client)

    resp = client.get("/api/v1/account/sessions", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    sessions = resp.get_json()["sessions"]
    assert len(sessions) == 1
    assert sessions[0]["current"] is True
    session_id = sessions[0]["id"]

    resp = client.delete(
        f"/api/v1/account/sessions/{session_id}", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 204

    resp = client.get("/api/v1/account/sessions", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 401


def test_settings_http_get_and_patch(client):
    token = _register_and_login_http(client)

    resp = client.get("/api/v1/settings", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    assert resp.get_json() == {"default_currency": "EUR", "initials": "BO"}

    resp = client.patch(
        "/api/v1/settings",
        json={"default_currency": "USD"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    assert resp.get_json() == {"default_currency": "USD", "initials": "BO"}

    resp = client.get("/api/v1/settings", headers={"Authorization": f"Bearer {token}"})
    assert resp.get_json() == {"default_currency": "USD", "initials": "BO"}


def test_settings_http_patch_invalid_currency_422(client):
    token = _register_and_login_http(client)
    resp = client.patch(
        "/api/v1/settings",
        json={"default_currency": "usd"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 422


def test_delete_account_http_flow(client):
    token = _register_and_login_http(client)
    resp = client.delete(
        "/api/v1/account",
        json={"password": PASSWORD},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 204

    resp = client.post(
        "/api/v1/login",
        json={"email": EMAIL, "password": PASSWORD, "device_label": DEVICE},
    )
    assert resp.status_code == 401


def test_delete_account_http_wrong_password_401(client):
    token = _register_and_login_http(client)
    resp = client.delete(
        "/api/v1/account",
        json={"password": "not the password"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 401
