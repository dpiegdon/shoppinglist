import sqlite3
from importlib import resources


def connect(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    schema_sql = resources.files("shoppinglist_server").joinpath("schema.sql").read_text()
    conn.executescript(schema_sql)
    conn.commit()


def next_change_seq(conn: sqlite3.Connection) -> int:
    conn.execute("UPDATE meta SET change_seq = change_seq + 1 WHERE id = 1")
    row = conn.execute("SELECT change_seq FROM meta WHERE id = 1").fetchone()
    conn.commit()
    return row["change_seq"]
