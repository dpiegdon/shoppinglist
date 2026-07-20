"""Admin users + runtime server settings (T-107)."""

from flask import Flask

from shoppinglist_server import boot, create_blueprint
from shoppinglist_server import db as db_module

PW = "password123"
ADMIN_EMAIL = "boss@example.com"


def _admin_app(tmp_path, admin_emails=(ADMIN_EMAIL,), allow_registration=True):
    database_path = str(tmp_path / "admin.db")
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
            allow_registration=allow_registration,
            admin_emails=list(admin_emails),
        )
    )
    return app


def _register(client, email, password=PW):
    client.post("/api/v1/register", json={"email": email, "password": password})


def _login(client, email, password=PW):
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": password, "device_label": "web"}
    )
    return resp.get_json()


def _bearer(token):
    return {"Authorization": f"Bearer {token}"}


# ---- admin identity -------------------------------------------------------


def test_login_reports_is_admin_for_a_configured_admin(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, ADMIN_EMAIL)

    assert _login(client, ADMIN_EMAIL)["is_admin"] is True


def test_login_reports_non_admin_for_everyone_else(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, "user@example.com")

    assert _login(client, "user@example.com")["is_admin"] is False


def test_admin_match_is_case_insensitive(tmp_path):
    # Configured lowercase; registered/login with different casing still counts as admin.
    client = _admin_app(tmp_path, admin_emails=["boss@example.com"]).test_client()
    _register(client, "Boss@Example.com")

    assert _login(client, "Boss@Example.com")["is_admin"] is True


def test_non_admin_is_forbidden_from_admin_endpoints(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, "user@example.com")
    token = _login(client, "user@example.com")["token"]

    resp = client.get("/api/v1/admin/users", headers=_bearer(token))
    assert resp.status_code == 403
    assert resp.get_json()["error"] == "not_admin"


def test_admin_endpoint_needs_auth(tmp_path):
    client = _admin_app(tmp_path).test_client()
    assert client.get("/api/v1/admin/users").status_code == 401


# ---- users list -----------------------------------------------------------


def test_admin_lists_all_users_with_admin_flag(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, ADMIN_EMAIL)
    _register(client, "a@example.com")
    _register(client, "b@example.com")
    token = _login(client, ADMIN_EMAIL)["token"]

    users = client.get("/api/v1/admin/users", headers=_bearer(token)).get_json()["users"]

    by_email = {u["email"]: u for u in users}
    assert set(by_email) == {ADMIN_EMAIL, "a@example.com", "b@example.com"}
    assert by_email[ADMIN_EMAIL]["is_admin"] is True
    assert by_email["a@example.com"]["is_admin"] is False


# ---- registration override (runtime, non-durable) -------------------------


def test_admin_can_disable_registration_at_runtime(tmp_path):
    app = _admin_app(tmp_path, allow_registration=True)
    client = app.test_client()
    _register(client, ADMIN_EMAIL)
    token = _login(client, ADMIN_EMAIL)["token"]

    # Turn registration off; a new registration is now refused even though the config default is on.
    resp = client.put(
        "/api/v1/admin/server-settings",
        json={"allow_registration": False},
        headers=_bearer(token),
    )
    assert resp.status_code == 200
    assert client.get("/api/v1/registration-status").get_json()["allow_registration"] is False
    assert client.post("/api/v1/register", json={"email": "n@example.com", "password": PW}).status_code == 403


def test_registration_override_resets_after_a_restart(tmp_path, monkeypatch):
    app = _admin_app(tmp_path, allow_registration=True)
    client = app.test_client()
    _register(client, ADMIN_EMAIL)
    token = _login(client, ADMIN_EMAIL)["token"]

    client.put(
        "/api/v1/admin/server-settings",
        json={"allow_registration": False},
        headers=_bearer(token),
    )
    assert client.get("/api/v1/registration-status").get_json()["allow_registration"] is False

    # Simulate a restart: the boot id changes, so the stored override is stale and must be ignored
    # (and cleared), reverting to the config default (open).
    monkeypatch.setattr(boot, "current_boot_id", lambda: "a-different-boot-id")
    assert client.get("/api/v1/registration-status").get_json()["allow_registration"] is True

    # And it was self-cleaned: even back on the original boot id, the override is gone.
    monkeypatch.undo()
    assert client.get("/api/v1/registration-status").get_json()["allow_registration"] is True


def test_set_server_settings_rejects_non_boolean(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, ADMIN_EMAIL)
    token = _login(client, ADMIN_EMAIL)["token"]

    resp = client.put(
        "/api/v1/admin/server-settings", json={"allow_registration": "yes"}, headers=_bearer(token)
    )
    assert resp.status_code == 422


# ---- reset password (step-up) ---------------------------------------------


def test_admin_resets_a_users_password_with_step_up(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, ADMIN_EMAIL)
    _register(client, "victim@example.com")
    token = _login(client, ADMIN_EMAIL)["token"]
    victim_id = _login(client, "victim@example.com")["account_id"]

    resp = client.post(
        f"/api/v1/admin/users/{victim_id}/reset-password",
        json={"password": PW},  # the admin's OWN password
        headers=_bearer(token),
    )
    assert resp.status_code == 200
    new_password = resp.get_json()["password"]
    assert new_password and new_password != PW

    # Old password no longer works; the new one does.
    assert "token" not in _login(client, "victim@example.com", PW)
    assert "token" in _login(client, "victim@example.com", new_password)


def test_admin_reset_password_rejects_a_wrong_step_up_password(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, ADMIN_EMAIL)
    _register(client, "victim@example.com")
    token = _login(client, ADMIN_EMAIL)["token"]
    victim_id = _login(client, "victim@example.com")["account_id"]

    resp = client.post(
        f"/api/v1/admin/users/{victim_id}/reset-password",
        json={"password": "wrong-password"},
        headers=_bearer(token),
    )
    assert resp.status_code == 403
    assert resp.get_json()["error"] == "invalid_credentials"


# ---- delete user (step-up + guards) ---------------------------------------


def test_admin_deletes_a_user(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, ADMIN_EMAIL)
    _register(client, "gone@example.com")
    token = _login(client, ADMIN_EMAIL)["token"]
    victim_id = _login(client, "gone@example.com")["account_id"]

    resp = client.delete(
        f"/api/v1/admin/users/{victim_id}", json={"password": PW}, headers=_bearer(token)
    )
    assert resp.status_code == 204

    users = client.get("/api/v1/admin/users", headers=_bearer(token)).get_json()["users"]
    assert "gone@example.com" not in {u["email"] for u in users}


def test_admin_cannot_delete_themselves_via_admin(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, ADMIN_EMAIL)
    session = _login(client, ADMIN_EMAIL)
    token = session["token"]

    resp = client.delete(
        f"/api/v1/admin/users/{session['account_id']}", json={"password": PW}, headers=_bearer(token)
    )
    assert resp.status_code == 403
    assert resp.get_json()["error"] == "cannot_delete_self"


def test_admin_cannot_delete_another_admin(tmp_path):
    client = _admin_app(tmp_path, admin_emails=[ADMIN_EMAIL, "other@example.com"]).test_client()
    _register(client, ADMIN_EMAIL)
    _register(client, "other@example.com")
    token = _login(client, ADMIN_EMAIL)["token"]
    other_id = _login(client, "other@example.com")["account_id"]

    resp = client.delete(
        f"/api/v1/admin/users/{other_id}", json={"password": PW}, headers=_bearer(token)
    )
    assert resp.status_code == 403
    assert resp.get_json()["error"] == "cannot_delete_admin"


def test_admin_delete_needs_step_up_password(tmp_path):
    client = _admin_app(tmp_path).test_client()
    _register(client, ADMIN_EMAIL)
    _register(client, "gone@example.com")
    token = _login(client, ADMIN_EMAIL)["token"]
    victim_id = _login(client, "gone@example.com")["account_id"]

    resp = client.delete(
        f"/api/v1/admin/users/{victim_id}", json={"password": "nope"}, headers=_bearer(token)
    )
    assert resp.status_code == 403
    # Not deleted.
    users = client.get("/api/v1/admin/users", headers=_bearer(token)).get_json()["users"]
    assert "gone@example.com" in {u["email"] for u in users}
