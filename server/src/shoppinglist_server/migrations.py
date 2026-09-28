"""Versioned schema migrations for existing databases (T-57).

`schema.sql` is the source of truth for a BRAND NEW database: init_db() runs it
and stamps `PRAGMA user_version` at CURRENT_VERSION directly, no migrations
needed — the fresh schema already reflects every migration below. This list
exists for databases that predate a change: db.connect()/init_db() run any
migration whose version is greater than the database's current
`PRAGMA user_version`, in order, then stamp the new version — automatically,
on the very next connection to that file, no operator action required.

Adding a schema change: update schema.sql (so a fresh install gets the new
column directly) AND append a migration here (so an existing install gets it
too) — the two must produce the same end state. Never edit or remove an
already-released migration's SQL; add a new one instead.

A step is normally one SQL statement. It can instead be a function of the
connection, for a backfill SQLite cannot express (Unicode case folding, T-328);
it runs inside the same transaction, and raising from it refuses the migration
just as a failing statement does.
"""

import sqlite3
from collections.abc import Callable

from .emails import normalize_email


class MigrationRefused(RuntimeError):
    """A migration found data it cannot carry forward without an operator's decision.

    Raised from a step, so the migration rolls back and user_version stays where it was: every
    connection refuses again, loudly, until the data is fixed by hand (server/README.md, Upgrading).
    """


def _backfill_normalized_emails(conn: sqlite3.Connection) -> None:
    """Migration 12 (T-328): fill the folded address columns, or refuse if two accounts collide.

    Before T-328 uniqueness was SQLite's ASCII-only lower(), so 'É@x.co' and 'é@x.co' could both
    register. After folding they are one address, and the new unique index cannot hold both. Which
    one keeps it is not for the server to decide — each is a real account with lists and sessions —
    so this names every colliding group and stops.
    """
    groups: dict[str, list[str]] = {}
    for (email,) in conn.execute("SELECT email FROM accounts ORDER BY created_at, id"):
        groups.setdefault(normalize_email(email), []).append(email)
    collisions = [emails for emails in groups.values() if len(emails) > 1]
    if collisions:
        listed = "; ".join(" and ".join(repr(e) for e in emails) for emails in collisions)
        raise MigrationRefused(
            "Cannot upgrade the database to schema version 12: these accounts' e-mail addresses "
            f"are the same address under Unicode case folding: {listed}. Change or delete all but "
            "one account of each group by hand (see server/README.md, Upgrading), then restart."
        )
    for account_id, email in conn.execute("SELECT id, email FROM accounts").fetchall():
        conn.execute(
            "UPDATE accounts SET email_normalized = ? WHERE id = ?",
            (normalize_email(email), account_id),
        )
    for invite_id, invited_email in conn.execute(
        "SELECT id, invited_email FROM invites"
    ).fetchall():
        conn.execute(
            "UPDATE invites SET invited_email_normalized = ? WHERE id = ?",
            (normalize_email(invited_email), invite_id),
        )


Step = str | Callable[[sqlite3.Connection], None]


def run_step(conn: sqlite3.Connection, step: Step) -> None:
    """Apply one migration step: execute a statement, or call a backfill function."""
    if callable(step):
        step(conn)
    else:
        conn.execute(step)


# (version, [steps]) — steps run individually via run_step(), in order,
# inside one explicit BEGIN IMMEDIATE per migration (all-or-nothing: a failure rolls back
# that migration's statements, DDL included, and PRAGMA user_version is not advanced, so
# the next connection retries the migration from a clean schema). The write lock also
# means two workers upgrading the same file at once cannot both run these statements.
MIGRATIONS: list[tuple[int, list[Step]]] = [
    (
        1,
        [
            "ALTER TABLE lists ADD COLUMN notes TEXT",
            "ALTER TABLE lists ADD COLUMN notes_ts INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE lists ADD COLUMN notes_by TEXT NOT NULL DEFAULT ''",
        ],
    ),  # T-62: list notes
    (
        2,
        [
            "ALTER TABLE items ADD COLUMN last_touched_by_account_id TEXT",
            "ALTER TABLE items ADD COLUMN last_touched_ts INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE account_settings ADD COLUMN initials TEXT",
        ],
    ),  # T-64: item last-touched-by + account initials
    (
        3,
        [
            "ALTER TABLE auth_tokens ADD COLUMN idle_ttl_ms INTEGER NOT NULL DEFAULT 5356800000",
            # Backfill: web has always logged in with the hardcoded device_label
            # "web" (AuthContext.tsx), so existing rows can be classified exactly.
            # Everything else (Android's "MANUFACTURER MODEL", curl/no label)
            # keeps the 62-day column default.
            "UPDATE auth_tokens SET idle_ttl_ms = 604800000 WHERE device_label = 'web'",
        ],
    ),  # T-104: per-session sliding inactivity expiry
    (
        4,
        [
            "CREATE TABLE server_runtime ("
            "  id INTEGER PRIMARY KEY CHECK (id = 1),"
            "  registration_override INTEGER,"
            "  boot_id TEXT"
            ")",
            "INSERT OR IGNORE INTO server_runtime (id, registration_override, boot_id) "
            "VALUES (1, NULL, NULL)",
        ],
    ),  # T-107: runtime registration override (non-durable, boot-id tagged)
    (
        5,
        [
            # Existing lists keep today's behaviour: 'shopping'. ts/by 0/'' so any client's
            # explicit kind write wins the LWW comparison.
            "ALTER TABLE lists ADD COLUMN kind TEXT NOT NULL DEFAULT 'shopping'",
            "ALTER TABLE lists ADD COLUMN kind_ts INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE lists ADD COLUMN kind_by TEXT NOT NULL DEFAULT ''",
        ],
    ),  # T-110: list kind (shopping | checklist)
    (
        6,
        [
            "ALTER TABLE lists ADD COLUMN currency TEXT",
            "ALTER TABLE lists ADD COLUMN currency_ts INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE lists ADD COLUMN currency_by TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE items ADD COLUMN expense TEXT",
            "ALTER TABLE items ADD COLUMN expense_ts INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE items ADD COLUMN expense_by TEXT NOT NULL DEFAULT ''",
            # A partial index's WHERE clause cannot be altered in place. No existing row has an
            # expense, so the rebuilt index covers exactly the rows the old one did.
            "DROP INDEX IF EXISTS idx_items_list_name_live",
            "CREATE UNIQUE INDEX idx_items_list_name_live ON items (list_id, lower(name)) "
            "WHERE deleted = 0 AND expense IS NULL",
        ],
    ),  # T-151: expense lists — list currency, item expense, names not unique on expenses
    (
        7,
        [
            "ALTER TABLE lists ADD COLUMN closed_at INTEGER",
            "CREATE TABLE close_votes ("
            "  list_id TEXT NOT NULL REFERENCES lists (id),"
            "  account_id TEXT NOT NULL REFERENCES accounts (id),"
            "  voted_at INTEGER NOT NULL,"
            "  PRIMARY KEY (list_id, account_id)"
            ")",
            "CREATE INDEX IF NOT EXISTS idx_close_votes_list ON close_votes (list_id)",
        ],
    ),  # T-157: closing an expenses list by unanimous vote
    (
        8,
        [
            # 0 = never swept, so an upgraded deployment's first authenticated
            # request sweeps immediately — which is the whole point of T-218 for
            # a server that has been accumulating.
            "ALTER TABLE meta ADD COLUMN last_audit_at INTEGER NOT NULL DEFAULT 0",
            # NULL never equals boot.current_boot_id(), so the same first request
            # is also treated as the first request of a server run.
            "ALTER TABLE server_runtime ADD COLUMN audit_boot_id TEXT",
        ],
    ),  # T-218: housekeeping sweep bookkeeping (last sweep, and the run it ran for)
    (
        9,
        [
            # The DEFAULT only exists because SQLite cannot add a NOT NULL column
            # without one; the backfill below immediately replaces it, and
            # schema.sql carries the same default so a fresh and a migrated
            # database are identical.
            "ALTER TABLE accounts ADD COLUMN email_set_at INTEGER NOT NULL DEFAULT 0",
            # Backfill from created_at. An account that never changed its address
            # has held it since registration, which is the truth; one that did
            # change it gets an earlier instant than the real one, which only
            # keeps invites already visible before the upgrade visible. Nothing
            # recorded the change, so this is the most that can be reconstructed.
            "UPDATE accounts SET email_set_at = created_at",
        ],
    ),  # T-234: when the account started holding its current email address
    (
        10,
        [
            "ALTER TABLE server_runtime ADD COLUMN message TEXT NOT NULL DEFAULT ''",
        ],
    ),  # T-315: the admin-set server message (durable, '' = none)
    (
        11,
        [
            "ALTER TABLE items ADD COLUMN due TEXT",
            "ALTER TABLE items ADD COLUMN due_ts INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE items ADD COLUMN due_by TEXT NOT NULL DEFAULT ''",
        ],
    ),  # T-323: an item's optional due date
    (
        12,
        [
            # The DEFAULTs only exist because SQLite cannot add a NOT NULL column
            # without one; the backfill replaces every value before the unique
            # index is built, and schema.sql carries the same defaults.
            "ALTER TABLE accounts ADD COLUMN email_normalized TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE invites ADD COLUMN invited_email_normalized TEXT NOT NULL DEFAULT ''",
            _backfill_normalized_emails,
            "DROP INDEX IF EXISTS idx_accounts_email_lower",
            "CREATE UNIQUE INDEX idx_accounts_email_normalized ON accounts (email_normalized)",
            "CREATE INDEX idx_invites_email_normalized ON invites (invited_email_normalized)",
        ],
    ),  # T-328: one Unicode case folding for e-mail addresses
]

CURRENT_VERSION = MIGRATIONS[-1][0] if MIGRATIONS else 0
