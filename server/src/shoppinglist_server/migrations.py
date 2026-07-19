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
"""

# (version, [sql statements]) — statements run individually via conn.execute(),
# in order, in one transaction per migration (all-or-nothing: a failure rolls
# back that migration's statements and PRAGMA user_version is not advanced).
MIGRATIONS: list[tuple[int, list[str]]] = [
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
]

CURRENT_VERSION = MIGRATIONS[-1][0] if MIGRATIONS else 0
