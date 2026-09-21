import sqlite3

import pytest

from shoppinglist_server import auth
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
            item_id,
            list_id,
            NOW,
            change_seq,
            name,
            NOW,
            "dev-1",
            "todo",
            NOW,
            "dev-1",
            deleted,
            NOW,
            "dev-1",
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
        migrations_module,
        "MIGRATIONS",
        [(1, ["ALTER TABLE lists ADD COLUMN nonexistent_marker TEXT"])],
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


def test_api_error_renders_error_envelope(client, monkeypatch):
    # Raised from one of OUR routes: the ApiError handler is scoped to this blueprint (T-250), so a
    # route on the bare app would no longer be rendered by it — and should not be.
    def _raise():
        raise ApiError(418, "teapot", "I am a teapot")

    monkeypatch.setattr("shoppinglist_server.routes.app_version.apk_present", _raise)

    resp = client.get("/api/v1/app-version")
    assert resp.status_code == 418
    assert resp.get_json() == {"error": "teapot", "message": "I am a teapot"}


def test_migration_3_backfills_idle_ttl_by_device_label(tmp_path):
    """The T-104 backfill classifies pre-existing sessions: web has always sent
    the literal device_label "web", everything else keeps the long default."""
    path = tmp_path / "pre_t104.db"
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    # auth_tokens as it existed before migration 3 (no idle_ttl_ms).
    conn.execute(
        "CREATE TABLE auth_tokens (id TEXT PRIMARY KEY, token_hash TEXT NOT NULL, "
        "account_id TEXT NOT NULL, device_label TEXT, created_at INTEGER NOT NULL, "
        "last_seen_at INTEGER NOT NULL)"
    )
    for token_id, label in [("t1", "web"), ("t2", "Google Pixel 8"), ("t3", None)]:
        conn.execute(
            "INSERT INTO auth_tokens VALUES (?, ?, ?, ?, 0, 0)", (token_id, token_id, "acct", label)
        )
    conn.commit()

    sql_statements = dict(migrations_module.MIGRATIONS)[3]
    for statement in sql_statements:
        conn.execute(statement)
    conn.commit()

    ttls = {
        row["id"]: row["idle_ttl_ms"]
        for row in conn.execute("SELECT id, idle_ttl_ms FROM auth_tokens").fetchall()
    }
    assert ttls == {
        "t1": auth.WEB_IDLE_TTL_MS,
        "t2": auth.ANDROID_IDLE_TTL_MS,
        "t3": auth.ANDROID_IDLE_TTL_MS,
    }
    conn.close()


def test_fresh_schema_and_migrations_agree_on_the_idle_ttl_default(tmp_path):
    """schema.sql and the migration must produce the same end state (see the
    migrations.py docstring) — including the column DEFAULT, which is what a
    login from a pre-T-104 client relies on."""
    conn = db_module.connect(str(tmp_path / "fresh.db"))
    db_module.init_db(conn)

    default = next(
        row["dflt_value"]
        for row in conn.execute("PRAGMA table_info(auth_tokens)").fetchall()
        if row["name"] == "idle_ttl_ms"
    )
    assert int(default) == auth.DEFAULT_IDLE_TTL_MS
    conn.close()


def test_migration_8_adds_the_housekeeping_bookkeeping_columns(tmp_path):
    """An existing deployment has to catch up on `meta.last_audit_at` and
    `server_runtime.audit_boot_id`, and has to land in the "sweep immediately"
    state: 0 is older than any interval, and NULL never equals a boot id
    (T-218)."""
    path = tmp_path / "pre_t218.db"
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    # meta and server_runtime as they existed before migration 8.
    conn.execute(
        "CREATE TABLE meta (id INTEGER PRIMARY KEY CHECK (id = 1), "
        "change_seq INTEGER NOT NULL DEFAULT 0, gc_horizon INTEGER NOT NULL DEFAULT 0, "
        "last_gc_at INTEGER NOT NULL DEFAULT 0)"
    )
    conn.execute("INSERT INTO meta (id, change_seq, gc_horizon, last_gc_at) VALUES (1, 7, 3, 99)")
    conn.execute(
        "CREATE TABLE server_runtime (id INTEGER PRIMARY KEY CHECK (id = 1), "
        "registration_override INTEGER, boot_id TEXT)"
    )
    conn.execute("INSERT INTO server_runtime (id) VALUES (1)")
    conn.commit()

    for statement in dict(migrations_module.MIGRATIONS)[8]:
        conn.execute(statement)
    conn.commit()

    meta = conn.execute("SELECT * FROM meta WHERE id = 1").fetchone()
    assert meta["last_audit_at"] == 0
    assert meta["change_seq"] == 7  # the pre-existing row is otherwise untouched
    assert (
        conn.execute("SELECT audit_boot_id FROM server_runtime WHERE id = 1").fetchone()[0] is None
    )
    conn.close()


def test_fresh_schema_and_migration_8_agree_on_the_housekeeping_columns(tmp_path):
    """schema.sql and the migration must produce the same end state (see the
    migrations.py docstring) — column names, types, NOT NULL and DEFAULT."""
    fresh = db_module.connect(str(tmp_path / "fresh_t218.db"))
    db_module.init_db(fresh)

    migrated = sqlite3.connect(str(tmp_path / "migrated_t218.db"))
    migrated.row_factory = sqlite3.Row
    migrated.execute(
        "CREATE TABLE meta (id INTEGER PRIMARY KEY CHECK (id = 1), "
        "change_seq INTEGER NOT NULL DEFAULT 0, gc_horizon INTEGER NOT NULL DEFAULT 0, "
        "last_gc_at INTEGER NOT NULL DEFAULT 0)"
    )
    migrated.execute(
        "CREATE TABLE server_runtime (id INTEGER PRIMARY KEY CHECK (id = 1), "
        "registration_override INTEGER, boot_id TEXT)"
    )
    for statement in dict(migrations_module.MIGRATIONS)[8]:
        migrated.execute(statement)
    migrated.commit()

    def _columns(conn, table):
        return [
            (row["name"], row["type"], row["notnull"], row["dflt_value"])
            for row in conn.execute(f"PRAGMA table_info({table})").fetchall()
        ]

    for table in ("meta", "server_runtime"):
        assert _columns(fresh, table) == _columns(migrated, table), table
    fresh.close()
    migrated.close()


def test_migration_9_backfills_email_set_at_from_created_at(tmp_path):
    """Nothing recorded when an existing account took its address, so the
    migration credits it with the one instant that is certain to be no later:
    registration (T-234)."""
    path = tmp_path / "pre_t234.db"
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    # accounts as it existed before migration 9.
    conn.execute(
        "CREATE TABLE accounts (id TEXT PRIMARY KEY, email TEXT NOT NULL, "
        "password_hash TEXT NOT NULL, created_at INTEGER NOT NULL)"
    )
    for account_id, created_at in [("a1", 1000), ("a2", 1700000000000)]:
        conn.execute(
            "INSERT INTO accounts (id, email, password_hash, created_at) VALUES (?, ?, 'x', ?)",
            (account_id, f"{account_id}@example.com", created_at),
        )
    conn.commit()

    for statement in dict(migrations_module.MIGRATIONS)[9]:
        conn.execute(statement)
    conn.commit()

    assert {
        row["id"]: row["email_set_at"]
        for row in conn.execute("SELECT id, email_set_at FROM accounts").fetchall()
    } == {"a1": 1000, "a2": 1700000000000}
    conn.close()


def test_fresh_schema_and_migration_9_agree_on_the_accounts_columns(tmp_path):
    """schema.sql and the migration must produce the same end state (see the
    migrations.py docstring) — column names, types, NOT NULL and DEFAULT."""
    fresh = db_module.connect(str(tmp_path / "fresh_t234.db"))
    db_module.init_db(fresh)

    migrated = sqlite3.connect(str(tmp_path / "migrated_t234.db"))
    migrated.row_factory = sqlite3.Row
    migrated.execute(
        "CREATE TABLE accounts (id TEXT PRIMARY KEY, email TEXT NOT NULL, "
        "password_hash TEXT NOT NULL, created_at INTEGER NOT NULL)"
    )
    for statement in dict(migrations_module.MIGRATIONS)[9]:
        migrated.execute(statement)
    migrated.commit()

    def _columns(conn):
        return [
            (row["name"], row["type"], row["notnull"], row["dflt_value"])
            for row in conn.execute("PRAGMA table_info(accounts)").fetchall()
        ]

    assert _columns(fresh) == _columns(migrated)
    fresh.close()
    migrated.close()
