import sqlite3

import pytest

from shoppinglist_server import db as db_module
from shoppinglist_server import migrations as migrations_module
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


def test_fresh_init_db_stamps_current_version_without_running_migrations(tmp_path, monkeypatch):
    # A migration that would fail if actually executed against a fresh schema
    # (the column already exists from schema.sql) — proves init_db() on a
    # brand-new file skips straight to CURRENT_VERSION rather than replaying it.
    monkeypatch.setattr(
        migrations_module, "MIGRATIONS", [(1, ["ALTER TABLE lists ADD COLUMN nonexistent_marker TEXT"])]
    )
    monkeypatch.setattr(migrations_module, "CURRENT_VERSION", 1)

    conn = db_module.connect(str(tmp_path / "fresh.db"))
    db_module.init_db(conn)

    assert conn.execute("PRAGMA user_version").fetchone()[0] == 1
    # The column from the "migration" was never applied — proof it didn't run.
    cols = {row["name"] for row in conn.execute("PRAGMA table_info(lists)")}
    assert "nonexistent_marker" not in cols
    conn.close()


def test_connect_applies_pending_migrations_to_an_existing_database(tmp_path, monkeypatch):
    path = tmp_path / "upgrade.db"
    conn = db_module.connect(str(path))
    db_module.init_db(conn)
    conn.close()
    # Real (unpatched) CURRENT_VERSION at the moment this "existing" database was
    # created — the DB is stamped at this version, whatever it is today (grows over
    # time as real migrations, e.g. T-62's, get appended above).
    base_version = migrations_module.CURRENT_VERSION

    # Simulate a schema change shipped after this database was created: a new
    # migration the database doesn't know about yet.
    next_version = base_version + 1
    monkeypatch.setattr(
        migrations_module,
        "MIGRATIONS",
        [(next_version, ["ALTER TABLE lists ADD COLUMN motto TEXT DEFAULT ''"])],
    )
    monkeypatch.setattr(migrations_module, "CURRENT_VERSION", next_version)

    # This is the production path (create_blueprint's per-request connect() never
    # calls init_db()) — migrations must self-apply from connect() alone.
    conn = db_module.connect(str(path))

    assert conn.execute("PRAGMA user_version").fetchone()[0] == next_version
    cols = {row["name"] for row in conn.execute("PRAGMA table_info(lists)")}
    assert "motto" in cols
    conn.close()


def test_connect_does_not_reapply_an_already_applied_migration(tmp_path, monkeypatch):
    monkeypatch.setattr(
        migrations_module,
        "MIGRATIONS",
        [(1, ["ALTER TABLE lists ADD COLUMN motto TEXT DEFAULT ''"])],
    )
    monkeypatch.setattr(migrations_module, "CURRENT_VERSION", 1)

    path = tmp_path / "already_current.db"
    conn = db_module.connect(str(path))
    db_module.init_db(conn)
    conn.close()

    # Reconnecting must not try to add the same column again (which would raise
    # "duplicate column name").
    conn = db_module.connect(str(path))
    conn.close()


def test_a_failing_migration_does_not_advance_user_version(tmp_path, monkeypatch):
    # Database starts at the real current version (no pending migrations)...
    path = tmp_path / "broken_migration.db"
    conn = db_module.connect(str(path))
    db_module.init_db(conn)
    conn.close()
    base_version = migrations_module.CURRENT_VERSION
    assert sqlite3.connect(str(path)).execute("PRAGMA user_version").fetchone()[0] == base_version

    # ...so a migration one version ahead is genuinely pending and will actually run.
    next_version = base_version + 1
    monkeypatch.setattr(
        migrations_module,
        "MIGRATIONS",
        [(next_version, ["ALTER TABLE lists ADD COLUMN"])],  # invalid SQL: guaranteed to fail
    )
    monkeypatch.setattr(migrations_module, "CURRENT_VERSION", next_version)
    with pytest.raises(sqlite3.OperationalError):
        db_module.connect(str(path))

    # A later, correctly-written retry of the same version must still apply —
    # the failed attempt must not have left user_version advanced.
    monkeypatch.setattr(
        migrations_module,
        "MIGRATIONS",
        [(next_version, ["ALTER TABLE lists ADD COLUMN motto TEXT DEFAULT ''"])],
    )
    conn = db_module.connect(str(path))
    assert conn.execute("PRAGMA user_version").fetchone()[0] == next_version
    cols = {row["name"] for row in conn.execute("PRAGMA table_info(lists)")}
    assert "motto" in cols
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
