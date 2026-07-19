CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_email_lower ON accounts (lower(email));

CREATE TABLE IF NOT EXISTS auth_tokens (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL,
    account_id TEXT NOT NULL REFERENCES accounts (id),
    device_label TEXT,
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    -- Sliding inactivity window (T-104): the session dies once
    -- `last_seen_at + idle_ttl_ms` is in the past. Resolved server-side at
    -- login from the client's declared platform (auth.PLATFORM_IDLE_TTL_MS),
    -- never taken from the client directly. The DEFAULT is the conservative
    -- long (Android) window, used for pre-T-104 rows and for logins from
    -- clients too old to declare a platform.
    idle_ttl_ms INTEGER NOT NULL DEFAULT 5356800000
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_auth_tokens_hash ON auth_tokens (token_hash);
CREATE INDEX IF NOT EXISTS idx_auth_tokens_account ON auth_tokens (account_id);

CREATE TABLE IF NOT EXISTS account_settings (
    account_id TEXT PRIMARY KEY REFERENCES accounts (id),
    default_currency TEXT NOT NULL,
    -- NULL = derive a default from the account's email (T-64); the account can
    -- override with any 1-3 characters via PATCH /settings.
    initials TEXT,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS lists (
    id TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    change_seq INTEGER NOT NULL,

    name TEXT NOT NULL,
    name_ts INTEGER NOT NULL,
    name_by TEXT NOT NULL,

    category_order TEXT NOT NULL DEFAULT '[]',
    category_order_ts INTEGER NOT NULL DEFAULT 0,
    category_order_by TEXT NOT NULL DEFAULT '',

    notes TEXT,
    notes_ts INTEGER NOT NULL DEFAULT 0,
    notes_by TEXT NOT NULL DEFAULT '',

    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_ts INTEGER NOT NULL DEFAULT 0,
    deleted_by TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_lists_change_seq ON lists (change_seq);

CREATE TABLE IF NOT EXISTS memberships (
    account_id TEXT NOT NULL REFERENCES accounts (id),
    list_id TEXT NOT NULL REFERENCES lists (id),
    joined_at INTEGER NOT NULL,
    PRIMARY KEY (account_id, list_id)
);
CREATE INDEX IF NOT EXISTS idx_memberships_list ON memberships (list_id);
CREATE INDEX IF NOT EXISTS idx_memberships_account ON memberships (account_id);

CREATE TABLE IF NOT EXISTS items (
    id TEXT PRIMARY KEY,
    list_id TEXT NOT NULL REFERENCES lists (id),
    created_at INTEGER NOT NULL,
    change_seq INTEGER NOT NULL,

    name TEXT NOT NULL,
    name_ts INTEGER NOT NULL,
    name_by TEXT NOT NULL,

    category TEXT,
    category_ts INTEGER NOT NULL DEFAULT 0,
    category_by TEXT NOT NULL DEFAULT '',

    stores TEXT NOT NULL DEFAULT '[]',
    stores_ts INTEGER NOT NULL DEFAULT 0,
    stores_by TEXT NOT NULL DEFAULT '',

    quantity TEXT,
    quantity_ts INTEGER NOT NULL DEFAULT 0,
    quantity_by TEXT NOT NULL DEFAULT '',

    price_amount TEXT,
    price_currency TEXT,
    price_ts INTEGER NOT NULL DEFAULT 0,
    price_by TEXT NOT NULL DEFAULT '',

    note TEXT,
    note_ts INTEGER NOT NULL DEFAULT 0,
    note_by TEXT NOT NULL DEFAULT '',

    status TEXT NOT NULL DEFAULT 'todo',
    status_ts INTEGER NOT NULL,
    status_by TEXT NOT NULL,

    -- Item-level (not per-field, unlike the *_by columns above), updated whenever
    -- any field-level write wins for this item (T-64). NULL until the item's
    -- first post-migration edit for rows that predate this column.
    last_touched_by_account_id TEXT,
    last_touched_ts INTEGER NOT NULL DEFAULT 0,

    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_ts INTEGER NOT NULL DEFAULT 0,
    deleted_by TEXT NOT NULL DEFAULT ''
);
-- Live (non-deleted) item names are unique per list, case-insensitively;
-- deleted rows are excluded so a name can be reused after deletion (Spec S3/S4).
CREATE UNIQUE INDEX IF NOT EXISTS idx_items_list_name_live
    ON items (list_id, lower(name))
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_items_change_seq ON items (change_seq);
CREATE INDEX IF NOT EXISTS idx_items_list ON items (list_id);

CREATE TABLE IF NOT EXISTS invites (
    id TEXT PRIMARY KEY,
    list_id TEXT NOT NULL REFERENCES lists (id),
    invited_email TEXT NOT NULL,
    created_by TEXT NOT NULL REFERENCES accounts (id),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    revoked INTEGER NOT NULL DEFAULT 0,
    used_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_invites_list ON invites (list_id);

-- Single-row table: monotonic change_seq counter driving sync cursors (Spec S6),
-- plus tombstone GC bookkeeping (Spec S6/S8).
CREATE TABLE IF NOT EXISTS meta (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    change_seq INTEGER NOT NULL DEFAULT 0,
    gc_horizon INTEGER NOT NULL DEFAULT 0,
    last_gc_at INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO meta (id, change_seq, gc_horizon, last_gc_at) VALUES (1, 0, 0, 0);
