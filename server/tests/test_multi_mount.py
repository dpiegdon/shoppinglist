"""Validates the blueprint can be mounted at any url_prefix, including
multiple isolated instances on the same app (T-27).

Each test builds its own Flask app + one or two blueprint instances rather
than using conftest's single-instance `app`/`client` fixtures, since the
whole point here is exercising configurations conftest doesn't cover.
"""

import pytest
from flask import Flask

from shoppinglist_server import create_blueprint, get_config_by_name
from shoppinglist_server import db as db_module
from shoppinglist_server.cli import shoppinglist_cli

PW = "password123"


def _init_db(tmp_path, filename):
    path = str(tmp_path / filename)
    conn = db_module.connect(path)
    db_module.init_db(conn)
    conn.close()
    return path


def _mount_two(tmp_path, **overrides):
    """Two fully independent instances on one app: different DB, different
    HMAC key, different url_prefix, different blueprint name."""
    db_a = _init_db(tmp_path, "a.db")
    db_b = _init_db(tmp_path, "b.db")

    app = Flask(__name__)
    app.config["TESTING"] = True

    args_a = {
        "database_path": db_a,
        "invite_hmac_key": b"key-a",
        "base_url": "http://a.example.com",
        "url_prefix": "/tenant-a/api",
        "name": "tenant_a",
        "serve_web_client": False,
        "serve_invite_landing_page": False,
        "serve_android_apk": False,
        **overrides.get("a", {}),
    }
    args_b = {
        "database_path": db_b,
        "invite_hmac_key": b"key-b",
        "base_url": "http://b.example.com",
        "url_prefix": "/tenant-b/api",
        "name": "tenant_b",
        "serve_web_client": False,
        "serve_invite_landing_page": False,
        "serve_android_apk": False,
        **overrides.get("b", {}),
    }
    bp_a = create_blueprint(**args_a)
    bp_b = create_blueprint(**args_b)
    app.register_blueprint(bp_a)
    app.register_blueprint(bp_b)
    app.cli.add_command(shoppinglist_cli)
    return app, db_a, db_b


def test_two_instances_mount_on_one_app_with_distinct_names(tmp_path):
    app, _, _ = _mount_two(tmp_path)
    client = app.test_client()

    resp_a = client.post("/tenant-a/api/lists")  # wrong method, but proves routing exists
    resp_b = client.post("/tenant-b/api/lists")
    assert resp_a.status_code != 404
    assert resp_b.status_code != 404


def test_same_name_twice_raises_flasks_own_clear_error(tmp_path):
    app = Flask(__name__)
    db_path = _init_db(tmp_path, "x.db")
    app.register_blueprint(
        create_blueprint(
            database_path=db_path, invite_hmac_key=b"k", base_url="http://x",
            url_prefix="/a", serve_web_client=False, serve_invite_landing_page=False, serve_android_apk=False,
        )
    )
    with pytest.raises(ValueError, match="already registered"):
        app.register_blueprint(
            create_blueprint(
                database_path=db_path, invite_hmac_key=b"k", base_url="http://x",
                url_prefix="/b", serve_web_client=False, serve_invite_landing_page=False, serve_android_apk=False,
            )
        )


def test_accounts_are_isolated_per_instance(tmp_path):
    app, db_a, db_b = _mount_two(tmp_path)
    client = app.test_client()

    resp = client.post(
        "/tenant-a/api/register", json={"email": "alice@example.com", "password": PW}
    )
    assert resp.status_code == 201

    # The account exists in A's database...
    conn_a = db_module.connect(db_a)
    assert conn_a.execute("SELECT 1 FROM accounts WHERE email = ?", ("alice@example.com",)).fetchone()
    conn_a.close()

    # ...and does NOT exist in B's database, nor can B log in with it.
    conn_b = db_module.connect(db_b)
    assert conn_b.execute("SELECT 1 FROM accounts WHERE email = ?", ("alice@example.com",)).fetchone() is None
    conn_b.close()

    resp = client.post(
        "/tenant-b/api/login",
        json={"email": "alice@example.com", "password": PW, "device_label": "dev"},
    )
    assert resp.status_code == 401


def test_a_token_from_one_instance_is_rejected_by_the_other(tmp_path):
    app, _, _ = _mount_two(tmp_path)
    client = app.test_client()

    client.post("/tenant-a/api/register", json={"email": "alice@example.com", "password": PW})
    resp = client.post(
        "/tenant-a/api/login",
        json={"email": "alice@example.com", "password": PW, "device_label": "dev"},
    )
    token = resp.get_json()["token"]

    # Same bearer token, but presented to instance B's routes -> unknown there.
    resp = client.get("/tenant-b/api/lists", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 401

    # Instance A itself accepts it fine (sanity check the token is real).
    resp = client.get("/tenant-a/api/lists", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200


def test_settings_default_currency_do_not_leak_across_instances(tmp_path):
    app, _, _ = _mount_two(tmp_path)
    client = app.test_client()

    client.post("/tenant-a/api/register", json={"email": "alice@example.com", "password": PW})
    token_a = client.post(
        "/tenant-a/api/login",
        json={"email": "alice@example.com", "password": PW, "device_label": "dev"},
    ).get_json()["token"]
    client.patch(
        "/tenant-a/api/settings",
        json={"default_currency": "JPY"},
        headers={"Authorization": f"Bearer {token_a}"},
    )

    client.post("/tenant-b/api/register", json={"email": "bob@example.com", "password": PW})
    token_b = client.post(
        "/tenant-b/api/login",
        json={"email": "bob@example.com", "password": PW, "device_label": "dev"},
    ).get_json()["token"]
    resp = client.get("/tenant-b/api/settings", headers={"Authorization": f"Bearer {token_b}"})
    assert resp.get_json()["default_currency"] == "EUR"  # unaffected by A's change


def test_invite_key_isolation(tmp_path):
    """A token minted with instance A's HMAC key must not decode under B's key."""
    from shoppinglist_server import invites

    app, db_a, db_b = _mount_two(tmp_path)
    client = app.test_client()

    client.post("/tenant-a/api/register", json={"email": "alice@example.com", "password": PW})
    token_a = client.post(
        "/tenant-a/api/login",
        json={"email": "alice@example.com", "password": PW, "device_label": "dev"},
    ).get_json()["token"]
    client.post(
        "/tenant-a/api/sync",
        json={"cursor": 0, "device_id": "dev", "full_lists": [], "changes": {
            "lists": [{"id": "list-1", "fields": {
                "name": {"value": "Groceries", "updated_at": 1, "updated_by": "dev"}
            }}]
        }},
        headers={"Authorization": f"Bearer {token_a}"},
    )
    resp = client.post(
        "/tenant-a/api/lists/list-1/invites",
        json={"invited_email": "bob@example.com"},
        headers={"Authorization": f"Bearer {token_a}"},
    )
    minted_token = resp.get_json()["token"]

    with pytest.raises(Exception):
        invites.decode_token(b"key-b", minted_token)
    # But it decodes fine under the key it was actually minted with.
    invites.decode_token(b"key-a", minted_token)


def test_serve_web_client_twice_on_one_app_raises_clear_error(tmp_path):
    app = Flask(__name__)
    db_1 = _init_db(tmp_path, "1.db")
    db_2 = _init_db(tmp_path, "2.db")
    app.register_blueprint(
        create_blueprint(
            database_path=db_1, invite_hmac_key=b"k1", base_url="http://x",
            url_prefix="/a", name="a", serve_web_client=True, serve_invite_landing_page=False, serve_android_apk=False,
        )
    )
    with pytest.raises(ValueError, match="serve_web_client"):
        app.register_blueprint(
            create_blueprint(
                database_path=db_2, invite_hmac_key=b"k2", base_url="http://y",
                url_prefix="/b", name="b", serve_web_client=True, serve_invite_landing_page=False, serve_android_apk=False,
            )
        )


def test_serve_invite_landing_page_twice_on_one_app_raises_clear_error(tmp_path):
    app = Flask(__name__)
    db_1 = _init_db(tmp_path, "1.db")
    db_2 = _init_db(tmp_path, "2.db")
    app.register_blueprint(
        create_blueprint(
            database_path=db_1, invite_hmac_key=b"k1", base_url="http://x",
            url_prefix="/a", name="a", serve_web_client=False, serve_invite_landing_page=True, serve_android_apk=False,
        )
    )
    with pytest.raises(ValueError, match="serve_invite_landing_page"):
        app.register_blueprint(
            create_blueprint(
                database_path=db_2, invite_hmac_key=b"k2", base_url="http://y",
                url_prefix="/b", name="b", serve_web_client=False, serve_invite_landing_page=True, serve_android_apk=False,
            )
        )


def test_serve_android_apk_twice_on_one_app_raises_clear_error(tmp_path):
    app = Flask(__name__)
    db_1 = _init_db(tmp_path, "1.db")
    db_2 = _init_db(tmp_path, "2.db")
    app.register_blueprint(
        create_blueprint(
            database_path=db_1, invite_hmac_key=b"k1", base_url="http://x",
            url_prefix="/a", name="a", serve_web_client=False, serve_invite_landing_page=False,
        )
    )
    with pytest.raises(ValueError, match="serve_android_apk"):
        app.register_blueprint(
            create_blueprint(
                database_path=db_2, invite_hmac_key=b"k2", base_url="http://y",
                url_prefix="/b", name="b", serve_web_client=False, serve_invite_landing_page=False,
            )
        )


def test_landing_page_enabled_on_only_one_instance_uses_that_instances_key(tmp_path):
    from shoppinglist_server import auth as auth_module
    from shoppinglist_server import invites

    app, db_a, db_b = _mount_two(
        tmp_path, a={"serve_invite_landing_page": True}
    )
    client = app.test_client()

    conn_a = db_module.connect(db_a)
    account_id = auth_module.register(conn_a, "alice@example.com", PW)
    conn_a.execute(
        "INSERT INTO lists (id, created_at, change_seq, name, name_ts, name_by) "
        "VALUES ('list-1', 0, 1, 'Groceries', 0, 'dev')"
    )
    conn_a.execute(
        "INSERT INTO memberships (account_id, list_id, joined_at) VALUES (?, 'list-1', 0)",
        (account_id,),
    )
    conn_a.commit()
    result = invites.mint(conn_a, b"key-a", "http://a.example.com", "list-1", "bob@example.com", account_id)
    conn_a.close()

    resp = client.get(f"/invite/{result['token']}")
    assert resp.status_code == 200
    assert result["token"] in resp.get_data(as_text=True)


# ---- CLI instance resolution -------------------------------------------------


def test_get_config_by_name_single_instance_needs_no_name(tmp_path):
    app = Flask(__name__)
    db_path = _init_db(tmp_path, "x.db")
    app.register_blueprint(
        create_blueprint(
            database_path=db_path, invite_hmac_key=b"k", base_url="http://x",
            url_prefix="/a", serve_web_client=False, serve_invite_landing_page=False, serve_android_apk=False,
        )
    )
    with app.app_context():
        config = get_config_by_name(app)
    assert config["database_path"] == db_path


def test_get_config_by_name_multiple_instances_requires_a_name(tmp_path):
    app, db_a, db_b = _mount_two(tmp_path)
    with app.app_context():
        with pytest.raises(ValueError, match="Multiple blueprint instances"):
            get_config_by_name(app)
        assert get_config_by_name(app, "tenant_a")["database_path"] == db_a
        assert get_config_by_name(app, "tenant_b")["database_path"] == db_b
        with pytest.raises(ValueError, match="No such instance"):
            get_config_by_name(app, "nonexistent")


def test_cli_requires_instance_flag_with_multiple_mounted(tmp_path):
    app, db_a, db_b = _mount_two(tmp_path)
    runner = app.test_cli_runner()

    result = runner.invoke(args=["shoppinglist", "init-db"])
    assert result.exit_code != 0
    assert "Multiple blueprint instances" in result.output

    result = runner.invoke(args=["shoppinglist", "init-db", "--instance", "tenant_a"])
    assert result.exit_code == 0
    assert db_a in result.output
