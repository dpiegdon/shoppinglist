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
    """Does not commit: the caller owns the transaction (T-316)."""
    conn.execute(
        "UPDATE server_runtime SET registration_override = ?, boot_id = ? WHERE id = 1",
        (1 if allow else 0, boot.current_boot_id()),
    )


def get_message(conn: sqlite3.Connection) -> str:
    """The current server message, `''` when none is set."""
    row = conn.execute("SELECT message FROM server_runtime WHERE id = 1").fetchone()
    return "" if row is None or row["message"] is None else row["message"]


# The message rule (T-316), pinned for the server and both clients by
# shared-test-cases/server-message.json. Trimmed from both ends: tab, LF, CR, space and every Zs.
# Refused anywhere: any Cc, the line and paragraph separators (Zl, Zp), the bidi embeddings and
# overrides U+202A-U+202E (they can reorder the rest of the page), and an unpaired surrogate (not
# text at all). Isolates, ZWJ/ZWNJ and the marks stay: scripts need them. Nothing but Cf and Zs
# left means no message.
_TRIM_CHARS = frozenset("\t\n\r ")
_REFUSED_CATEGORIES = frozenset({"Cc", "Zl", "Zp", "Cs"})
_REFUSED_CHARS = frozenset("\u202a\u202b\u202c\u202d\u202e")


def _trimmed(c: str) -> bool:
    return c in _TRIM_CHARS or unicodedata.category(c) == "Zs"


def validate_message(value) -> str:
    """The message as it will be stored, `''` for none, by the rule above; otherwise
    `422 invalid_message`. Any other text, links included, is allowed; clients show it as plain
    text and never make it clickable."""
    if not isinstance(value, str):
        raise ApiError(422, "invalid_message", "message must be a string.")
    start, end = 0, len(value)
    while start < end and _trimmed(value[start]):
        start += 1
    while end > start and _trimmed(value[end - 1]):
        end -= 1
    text = value[start:end]
    if len(text) > MAX_MESSAGE_CHARS or any(
        c in _REFUSED_CHARS or unicodedata.category(c) in _REFUSED_CATEGORIES for c in text
    ):
        raise ApiError(
            422,
            "invalid_message",
            f"The message must be one line of at most {MAX_MESSAGE_CHARS} characters.",
        )
    if all(unicodedata.category(c) in ("Cf", "Zs") for c in text):
        return ""
    return text


def set_message(conn: sqlite3.Connection, text: str) -> None:
    """Does not commit: the caller owns the transaction (T-316)."""
    conn.execute("UPDATE server_runtime SET message = ? WHERE id = 1", (text,))
