"""Admin users + runtime server settings (T-107)."""

import json
from pathlib import Path

import pytest
from flask import Flask

from shoppinglist_server import boot, create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server import server_settings
from shoppinglist_server.errors import ApiError

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


def test_admin_users_are_ordered_by_email_case_insensitively(tmp_path):
    client = _admin_app(tmp_path).test_client()
    # Registered newest-first alphabetically, so created_at order is the reverse of the answer;
    # and the mixed case would put every capital before every lowercase in raw byte order.
    for email in ("Zoe@example.com", "milk@example.com", "carol@example.com", "Adam@example.com"):
        _register(client, email)
    _register(client, ADMIN_EMAIL)
    token = _login(client, ADMIN_EMAIL)["token"]

    users = client.get("/api/v1/admin/users", headers=_bearer(token)).get_json()["users"]

    assert [u["email"] for u in users] == [
        "Adam@example.com",
        ADMIN_EMAIL,
        "carol@example.com",
        "milk@example.com",
        "Zoe@example.com",
    ]


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
    assert (
        client.post("/api/v1/register", json={"email": "n@example.com", "password": PW}).status_code
        == 403
    )


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
        f"/api/v1/admin/users/{session['account_id']}",
        json={"password": PW},
        headers=_bearer(token),
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


# ---- server message (durable, T-315) ---------------------------------------


def _admin_client(tmp_path, **kwargs):
    app = _admin_app(tmp_path, **kwargs)
    client = app.test_client()
    _register(client, ADMIN_EMAIL)
    return client, _login(client, ADMIN_EMAIL)["token"]


def _put_settings(client, token, body):
    return client.put("/api/v1/admin/server-settings", json=body, headers=_bearer(token))


def test_server_message_is_empty_by_default_everywhere(tmp_path):
    client, token = _admin_client(tmp_path)

    assert client.get("/api/v1/admin/server-settings", headers=_bearer(token)).get_json() == {
        "allow_registration": True,
        "message": "",
    }
    assert client.get("/api/v1/registration-status").get_json() == {
        "allow_registration": True,
        "message": None,
    }
    sync = client.post("/api/v1/sync", json={"cursor": 0}, headers=_bearer(token))
    assert sync.status_code == 200
    assert "server_message" in sync.get_json()
    assert sync.get_json()["server_message"] is None


def test_admin_sets_the_server_message_and_every_read_endpoint_shows_it(tmp_path):
    client, token = _admin_client(tmp_path)

    resp = _put_settings(client, token, {"message": "  Down for maintenance Sunday 10:00.  "})
    assert resp.status_code == 200
    # Trimmed on the way in; the response carries both settings.
    assert resp.get_json() == {
        "allow_registration": True,
        "message": "Down for maintenance Sunday 10:00.",
    }
    assert client.get("/api/v1/admin/server-settings", headers=_bearer(token)).get_json() == {
        "allow_registration": True,
        "message": "Down for maintenance Sunday 10:00.",
    }
    assert (
        client.get("/api/v1/registration-status").get_json()["message"]
        == "Down for maintenance Sunday 10:00."
    )
    # Every user sees it with each sync, not only the admin.
    _register(client, "user@example.com")
    user_token = _login(client, "user@example.com")["token"]
    sync = client.post("/api/v1/sync", json={"cursor": 0}, headers=_bearer(user_token))
    assert sync.get_json()["server_message"] == "Down for maintenance Sunday 10:00."


def test_an_empty_message_clears_it(tmp_path):
    client, token = _admin_client(tmp_path)
    _put_settings(client, token, {"message": "Full, please use another server."})

    resp = _put_settings(client, token, {"message": ""})
    assert resp.status_code == 200
    assert resp.get_json()["message"] == ""
    assert client.get("/api/v1/registration-status").get_json()["message"] is None
    sync = client.post("/api/v1/sync", json={"cursor": 0}, headers=_bearer(token))
    assert sync.get_json()["server_message"] is None

    # Whitespace only trims to nothing, which is the same as clearing.
    _put_settings(client, token, {"message": "x"})
    assert _put_settings(client, token, {"message": "   "}).get_json()["message"] == ""


def test_server_settings_put_is_partial(tmp_path):
    client, token = _admin_client(tmp_path)
    _put_settings(client, token, {"message": "Hello"})

    # Only the toggle: the message stays.
    resp = _put_settings(client, token, {"allow_registration": False})
    assert resp.get_json() == {"allow_registration": False, "message": "Hello"}
    # Only the message: the toggle stays.
    resp = _put_settings(client, token, {"message": "Bye"})
    assert resp.get_json() == {"allow_registration": False, "message": "Bye"}
    # Both at once.
    resp = _put_settings(client, token, {"allow_registration": True, "message": ""})
    assert resp.get_json() == {"allow_registration": True, "message": ""}


def test_server_settings_put_needs_at_least_one_setting(tmp_path):
    client, token = _admin_client(tmp_path)

    resp = _put_settings(client, token, {})
    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_request"
    resp = _put_settings(client, token, {"something_else": 1})
    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_request"


def test_server_message_validation(tmp_path):
    client, token = _admin_client(tmp_path)
    _put_settings(client, token, {"message": "Keep me"})

    ok = [
        "x" * 200,
        "  " + "x" * 200 + "  ",  # 200 after trimming
        "See https://example.com/status",  # links are fine; clients never make them clickable
        "Wartung am Sonntag – äöü \U0001f6a7",
    ]
    for text in ok:
        resp = _put_settings(client, token, {"message": text})
        assert resp.status_code == 200, text
        assert resp.get_json()["message"] == text.strip()

    _put_settings(client, token, {"message": "Keep me"})
    bad = [
        "x" * 201,
        "two\nlines",
        "two\rlines",
        "a\ttab",
        "bell\x07",
        "c1\x85control",
        "del\x7fchar",
        5,
        None,
        True,
        ["x"],
    ]
    for value in bad:
        resp = _put_settings(client, token, {"message": value})
        assert resp.status_code == 422, repr(value)
        assert resp.get_json()["error"] == "invalid_message", repr(value)
    # Nothing refused was stored.
    assert client.get("/api/v1/registration-status").get_json()["message"] == "Keep me"


def test_an_invalid_message_does_not_apply_the_toggle_sent_with_it(tmp_path):
    client, token = _admin_client(tmp_path)

    resp = _put_settings(client, token, {"allow_registration": False, "message": "a\nb"})
    assert resp.status_code == 422
    assert client.get("/api/v1/registration-status").get_json()["allow_registration"] is True


def test_server_message_is_admin_only(tmp_path):
    client, _ = _admin_client(tmp_path)
    _register(client, "user@example.com")
    user_token = _login(client, "user@example.com")["token"]

    resp = _put_settings(client, user_token, {"message": "pwned"})
    assert resp.status_code == 403
    assert client.get("/api/v1/registration-status").get_json()["message"] is None


def test_server_message_survives_a_restart(tmp_path, monkeypatch):
    client, token = _admin_client(tmp_path)
    _put_settings(client, token, {"allow_registration": False, "message": "Still here"})

    # A new boot id voids the registration override but not the message.
    monkeypatch.setattr(boot, "current_boot_id", lambda: "a-different-boot-id")
    assert client.get("/api/v1/registration-status").get_json() == {
        "allow_registration": True,
        "message": "Still here",
    }
    # And a whole new app on the same database file reads it back.
    fresh = _admin_app(tmp_path).test_client()
    assert fresh.get("/api/v1/registration-status").get_json()["message"] == "Still here"


def test_server_message_audit_records_the_length_never_the_text(tmp_path, caplog):
    import logging

    from shoppinglist_server import audit

    client, token = _admin_client(tmp_path)
    caplog.set_level(logging.INFO, logger=audit.LOGGER_NAME)

    _put_settings(client, token, {"message": "  Secret-ish maintenance note  "})
    _put_settings(client, token, {"message": ""})
    messages = [r.getMessage() for r in caplog.records if r.name == audit.LOGGER_NAME]

    set_lines = [m for m in messages if "event=admin.message_set" in m]
    cleared_lines = [m for m in messages if "event=admin.message_cleared" in m]
    assert len(set_lines) == 1 and len(cleared_lines) == 1
    assert f"length={len('Secret-ish maintenance note')}" in set_lines[0]
    assert all("Secret" not in m for m in messages)
    # A message-only PUT does not claim the registration toggle changed.
    assert not any("event=admin.registration_toggled" in m for m in messages)


# ---- the message rule and the single transaction (T-316) --------------------


def test_a_lone_surrogate_in_the_message_is_invalid_message_and_applies_nothing(tmp_path):
    client, token = _admin_client(tmp_path)

    resp = client.put(
        "/api/v1/admin/server-settings",
        data='{"allow_registration": false, "message": "a\\ud800b"}',
        content_type="application/json",
        headers=_bearer(token),
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_message"
    assert client.get("/api/v1/registration-status").get_json() == {
        "allow_registration": True,
        "message": None,
    }


def test_a_busy_database_on_the_second_write_applies_neither_setting(tmp_path, monkeypatch):
    # One transaction (T-316): the toggle used to be committed before the message write failed,
    # so a 503 server_busy — "nothing happened, retry" — had in fact half-applied the request.
    import sqlite3

    from shoppinglist_server import server_settings

    client, token = _admin_client(tmp_path)
    _put_settings(client, token, {"message": "Before"})

    def locked(conn, text):
        raise sqlite3.OperationalError("database is locked")

    monkeypatch.setattr(server_settings, "set_message", locked)
    resp = _put_settings(client, token, {"allow_registration": False, "message": "After"})
    assert resp.status_code == 503
    assert resp.get_json()["error"] == "server_busy"
    monkeypatch.undo()
    assert client.get("/api/v1/registration-status").get_json() == {
        "allow_registration": True,
        "message": "Before",
    }


# ---- the server message rule, from the table all three implementations read (T-316) --------
# shared-test-cases/server-message.json pins the rule for the server, the web client and the
# Android app alike; a case changed there must change all three.

TABLE = Path(__file__).resolve().parents[2] / "shared-test-cases" / "server-message.json"
CASES = json.loads(TABLE.read_text(encoding="utf-8"))["cases"]


def test_the_table_is_not_empty():
    assert len(CASES) >= 20


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_validate_message_follows_the_table(case):
    expected = case["result"]
    if "error" in expected:
        with pytest.raises(ApiError) as exc:
            server_settings.validate_message(case["input"])
        assert exc.value.code == expected["error"]
    else:
        stored = server_settings.validate_message(case["input"])
        # Stored as '' when there is none; the wire's null.
        assert (stored or None) == expected["message"]


def test_the_admin_endpoint_follows_the_table(tmp_path):
    client, token = _admin_client(tmp_path)
    for case in CASES:
        _put = client.put(
            "/api/v1/admin/server-settings",
            # json.dumps writes a lone surrogate as its \\u escape, which is what a client sends.
            data=json.dumps({"message": case["input"]}),
            content_type="application/json",
            headers=_bearer(token),
        )
        expected = case["result"]
        if "error" in expected:
            assert _put.status_code == 422, case["name"]
            assert _put.get_json()["error"] == expected["error"], case["name"]
        else:
            assert _put.status_code == 200, case["name"]
            status = client.get("/api/v1/registration-status").get_json()
            assert status["message"] == expected["message"], case["name"]
