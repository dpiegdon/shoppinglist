"""Runtime, non-durable server settings an admin can toggle (T-107).

Currently just the registration override. It is stored in the DB so every worker
of a multi-worker deployment sees the same value, but tagged with the current
boot id (see boot.py) so it is void after a restart — a mismatched boot id means
the override was set during a previous run, so it's cleared and the config
default (set at deploy time) reasserts. This gives "resets on restart" without
needing a once-per-start hook.
"""

import sqlite3

from . import boot


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
