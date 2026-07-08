import sqlite3

import pytest

from shoppinglist_server import db as db_module
from shoppinglist_server.errors import ApiError

NOW = 1751970000000


def _insert_list(conn, list_id="list-1"):
    conn.execute(
        "INSERT INTO lists (id, created_at, change_seq, name, name_ts, name_by) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (list_id, NOW, 1, "Groceries", NOW, "dev-1"),
    )
    conn.commit()
    return list_id


def _insert_item(conn, item_id, list_id, name, change_seq, deleted=0):
    conn.execute(
        "INSERT INTO items "
        "(id, list_id, created_at, change_seq, name, name_ts, name_by, "
        " status, status_ts, status_by, deleted, deleted_ts, deleted_by) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            item_id, list_id, NOW, change_seq, name, NOW, "dev-1",
            "todo", NOW, "dev-1", deleted, NOW, "dev-1",
        ),
    )
    conn.commit()


def test_init_db_is_idempotent(tmp_path):
    path = tmp_path / "idempotent.db"
    conn = db_module.connect(str(path))
    db_module.init_db(conn)
    db_module.init_db(conn)  # must not raise
    conn.close()


def test_next_change_seq_increments(db_conn):
    assert db_module.next_change_seq(db_conn) == 1
    assert db_module.next_change_seq(db_conn) == 2
    assert db_module.next_change_seq(db_conn) == 3


def test_duplicate_item_name_case_insensitive_rejected(db_conn):
    list_id = _insert_list(db_conn)
    _insert_item(db_conn, "item-1", list_id, "Milk", 2)

    with pytest.raises(sqlite3.IntegrityError):
        _insert_item(db_conn, "item-2", list_id, "milk", 3)


def test_duplicate_item_name_allowed_when_prior_is_deleted(db_conn):
    list_id = _insert_list(db_conn)
    _insert_item(db_conn, "item-1", list_id, "Milk", 2, deleted=1)

    # Must not raise: the live-name uniqueness index excludes deleted rows.
    _insert_item(db_conn, "item-2", list_id, "milk", 3, deleted=0)


def test_api_error_renders_error_envelope(app, client):
    @app.route("/__test_error__")
    def _raise():
        raise ApiError(418, "teapot", "I am a teapot")

    resp = client.get("/__test_error__")
    assert resp.status_code == 418
    assert resp.get_json() == {"error": "teapot", "message": "I am a teapot"}
