"""Server settings an admin can change at runtime: the registration override (T-107) and the
server message (T-315), both in the single `server_runtime` row.

The server message is durable: it is a plain column, survives a restart, and is `''` when
there is none. The rest of this docstring concerns the registration override.

The registration override It is stored in the DB so every worker
of a multi-worker deployment sees the same value, but tagged with the current
boot id (see boot.py) so it is void after a restart — a mismatched boot id means
the override was set during a previous run, so it's cleared and the config
default (set at deploy time) reasserts. This gives "resets on restart" without
needing a once-per-start hook.
"""

import sqlite3
import unicodedata

from . import boot
from .errors import ApiError

MAX_MESSAGE_CHARS = 200


def effective_allow_registration(conn: sqlite3.Connection, config_default: bool) -> bool:
    """The registration flag actually in force: a live override if one was set
    during THIS server run, otherwise the deploy-time config default."""
    row = conn.execute(
        "SELECT registration_override, boot_id FROM server_runtime WHERE id = 1"
    ).fetchone()
    if row is None or row["registration_override"] is None:
        return config_default
    if row["boot_id"] != boot.current_boot_id():
        # Set during a previous run — void it (self-cleaning) and fall back.
        conn.execute(
            "UPDATE server_runtime SET registration_override = NULL, boot_id = NULL WHERE id = 1"
        )
        conn.commit()
        return config_default
    return bool(row["registration_override"])


def set_registration_override(conn: sqlite3.Connection, allow: bool) -> None:
    conn.execute(
        "UPDATE server_runtime SET registration_override = ?, boot_id = ? WHERE id = 1",
        (1 if allow else 0, boot.current_boot_id()),
    )
    conn.commit()


def get_message(conn: sqlite3.Connection) -> str:
    """The current server message, `''` when none is set."""
    row = conn.execute("SELECT message FROM server_runtime WHERE id = 1").fetchone()
    return "" if row is None or row["message"] is None else row["message"]


def validate_message(value) -> str:
    """The message as it will be stored: trimmed, at most `MAX_MESSAGE_CHARS` characters, one
    line. `422 invalid_message` otherwise. Any other text, links included, is allowed; clients
    show it as plain text and never make it clickable."""
    if not isinstance(value, str):
        raise ApiError(422, "invalid_message", "message must be a string.")
    text = value.strip()
    if len(text) > MAX_MESSAGE_CHARS or any(unicodedata.category(c) == "Cc" for c in text):
        raise ApiError(
            422,
            "invalid_message",
            f"The message must be one line of at most {MAX_MESSAGE_CHARS} characters.",
        )
    return text


def set_message(conn: sqlite3.Connection, text: str) -> None:
    conn.execute("UPDATE server_runtime SET message = ? WHERE id = 1", (text,))
    conn.commit()
