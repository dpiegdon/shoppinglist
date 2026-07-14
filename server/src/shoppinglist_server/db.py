import sqlite3
from importlib import resources

from . import migrations as migrations_module


def _has_meta_table(conn: sqlite3.Connection) -> bool:
    return (
        conn.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'").fetchone()
        is not None
    )


def _migrate(conn: sqlite3.Connection) -> None:
    """Bring an existing database up to migrations.CURRENT_VERSION.

    Safe to call on an already-current database (no-op) or repeatedly (each
    migration runs at most once, tracked via PRAGMA user_version). Not safe to
    call on a database with no tables yet — callers must check _has_meta_table
    first; init_db()/connect() below do this so a brand-new file skips straight
    to schema.sql's full-latest-structure creation instead of replaying
    migrations against tables that don't exist yet.
    """
    current = conn.execute("PRAGMA user_version").fetchone()[0]
    for version, statements in migrations_module.MIGRATIONS:
        if version <= current:
            continue
        try:
            for stmt in statements:
                conn.execute(stmt)
            conn.execute(f"PRAGMA user_version = {version}")
        except Exception:
            conn.rollback()
            raise
        conn.commit()


def connect(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    # Only an already-initialized database has anything to migrate; a brand-new
    # file is about to get init_db()'d (schema.sql already reflects every
    # migration, so it stamps CURRENT_VERSION directly rather than running
    # them). This is also what makes migrations self-healing in production:
    # create_blueprint()'s per-request connect() never calls init_db(), so this
    # is the only place an existing deployment's schema actually catches up.
    if _has_meta_table(conn):
        try:
            _migrate(conn)
        except Exception:
            conn.close()
            raise
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    was_fresh = not _has_meta_table(conn)
    schema_sql = resources.files("shoppinglist_server").joinpath("schema.sql").read_text()
    conn.executescript(schema_sql)
    conn.commit()
    if was_fresh:
        conn.execute(f"PRAGMA user_version = {migrations_module.CURRENT_VERSION}")
        conn.commit()
    else:
        _migrate(conn)


def next_change_seq(conn: sqlite3.Connection) -> int:
    # Deliberately does NOT commit: the sync engine bumps change_seq many times
    # per request and must apply changes + name-merge + build the delta in a
    # single transaction (Spec §6). The caller owns the transaction boundary.
    conn.execute("UPDATE meta SET change_seq = change_seq + 1 WHERE id = 1")
    row = conn.execute("SELECT change_seq FROM meta WHERE id = 1").fetchone()
    return row["change_seq"]
