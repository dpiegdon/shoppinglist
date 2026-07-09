"""Field-level last-write-wins sync engine (Spec §6).

Pure service layer — no HTTP, no commits. Callers own the transaction boundary
so that S5 can run ``check_cursor`` → ``apply_changes`` → ``name_merge`` →
``delta`` atomically in a single transaction.

LWW resolution: every syncable field carries ``(updated_at, updated_by)``. The
field value with the greatest ``(updated_at, updated_by)`` tuple wins; edits to
different fields of the same row therefore merge, while edits to the same field
resolve to the latest. ``updated_by`` is a deterministic tiebreak on equal
timestamps (larger string wins).
"""

import json
import re
import sqlite3
import time

from . import db as db_module
from .errors import ApiError

STATUS_VALUES = {"backlog", "todo", "checked"}
PRICE_AMOUNT_RE = re.compile(r"^\d+(\.\d{1,2})?$")
SERVER_MERGE = "server-merge"

# (wire key, timestamp column, author column). Order matters for INSERT building.
ITEM_FIELD_META = [
    ("name", "name_ts", "name_by"),
    ("category", "category_ts", "category_by"),
    ("stores", "stores_ts", "stores_by"),
    ("quantity", "quantity_ts", "quantity_by"),
    ("price", "price_ts", "price_by"),
    ("note", "note_ts", "note_by"),
    ("status", "status_ts", "status_by"),
    ("deleted", "deleted_ts", "deleted_by"),
]
LIST_FIELD_META = [
    ("name", "name_ts", "name_by"),
    ("category_order", "category_order_ts", "category_order_by"),
    ("deleted", "deleted_ts", "deleted_by"),
]
ITEM_TSBY = {key: (ts, by) for key, ts, by in ITEM_FIELD_META}
LIST_TSBY = {key: (ts, by) for key, ts, by in LIST_FIELD_META}
ITEM_KEYS = set(ITEM_TSBY)
LIST_KEYS = set(LIST_TSBY)


def _now_ms() -> int:
    return time.time_ns() // 1_000_000


def _bump(conn: sqlite3.Connection) -> int:
    return db_module.next_change_seq(conn)


# ---- field encode / decode -------------------------------------------------


def _item_field_to_columns(key, value) -> dict:
    if key == "stores":
        return {"stores": json.dumps(value if value is not None else [])}
    if key == "price":
        if value is None:
            return {"price_amount": None, "price_currency": None}
        return {"price_amount": value["amount"], "price_currency": value.get("currency")}
    if key == "deleted":
        return {"deleted": 1 if value else 0}
    return {key: value}


def _item_load_field(key, row):
    if key == "stores":
        return json.loads(row["stores"])
    if key == "price":
        if row["price_amount"] is None:
            return None
        return {"amount": row["price_amount"], "currency": row["price_currency"]}
    if key == "deleted":
        return bool(row["deleted"])
    return row[key]


def _list_field_to_columns(key, value) -> dict:
    if key == "category_order":
        return {"category_order": json.dumps(value if value is not None else [])}
    if key == "deleted":
        return {"deleted": 1 if value else 0}
    return {key: value}


def _list_load_field(key, row):
    if key == "category_order":
        return json.loads(row["category_order"])
    if key == "deleted":
        return bool(row["deleted"])
    return row[key]


def _item_to_wire(row) -> dict:
    fields = {
        key: {"value": _item_load_field(key, row), "updated_at": row[ts], "updated_by": row[by]}
        for key, ts, by in ITEM_FIELD_META
    }
    return {
        "id": row["id"],
        "list_id": row["list_id"],
        "created_at": row["created_at"],
        "fields": fields,
    }


def _list_to_wire(row) -> dict:
    fields = {
        key: {"value": _list_load_field(key, row), "updated_at": row[ts], "updated_by": row[by]}
        for key, ts, by in LIST_FIELD_META
    }
    return {"id": row["id"], "created_at": row["created_at"], "fields": fields}


# ---- parsing / validation --------------------------------------------------


def _parse_clock(key, clock, device_id):
    if not isinstance(clock, dict) or "value" not in clock:
        raise ApiError(422, "invalid_field", f"Field '{key}' must be an object with a value.")
    ts = clock.get("updated_at")
    if not isinstance(ts, int):
        raise ApiError(422, "invalid_field", f"Field '{key}' requires an integer updated_at.")
    by = clock.get("updated_by") or device_id or ""
    return clock["value"], ts, by


def _validate_item_field(key, value):
    if key == "name" and (value is None or not str(value).strip()):
        raise ApiError(422, "invalid_name", "Item name must not be empty.")
    if key == "status" and value not in STATUS_VALUES:
        raise ApiError(422, "invalid_status", f"status must be one of {sorted(STATUS_VALUES)}.")
    if key == "price" and value is not None:
        amount = value.get("amount") if isinstance(value, dict) else None
        if amount is None or not PRICE_AMOUNT_RE.match(str(amount)):
            raise ApiError(422, "invalid_price", "price amount must be a decimal string.")


def _validate_list_field(key, value):
    if key == "name" and (value is None or not str(value).strip()):
        raise ApiError(422, "invalid_name", "List name must not be empty.")


def _parse_row(obj, keys, tsby, validate, device_id):
    if not isinstance(obj, dict) or "id" not in obj:
        raise ApiError(422, "invalid_row", "Each change requires an id.")
    fields = {}
    for key, clock in (obj.get("fields") or {}).items():
        if key not in keys:
            continue  # forward-compatible: ignore unknown fields
        value, ts, by = _parse_clock(key, clock, device_id)
        validate(key, value)
        fields[key] = (value, ts, by)
    return obj["id"], obj.get("created_at") or _now_ms(), fields


# ---- membership helpers ----------------------------------------------------


def _list_exists(conn, list_id) -> bool:
    return conn.execute("SELECT 1 FROM lists WHERE id = ?", (list_id,)).fetchone() is not None


def _is_member(conn, account_id, list_id) -> bool:
    return (
        conn.execute(
            "SELECT 1 FROM memberships WHERE account_id = ? AND list_id = ?",
            (account_id, list_id),
        ).fetchone()
        is not None
    )


# ---- low-level row writers -------------------------------------------------


def _insert(conn, table, cols):
    keys = list(cols)
    placeholders = ", ".join("?" * len(keys))
    conn.execute(
        f"INSERT INTO {table} ({', '.join(keys)}) VALUES ({placeholders})",
        [cols[k] for k in keys],
    )


def _update(conn, table, row_id, cols):
    assignments = ", ".join(f"{k} = ?" for k in cols)
    conn.execute(f"UPDATE {table} SET {assignments} WHERE id = ?", [*cols.values(), row_id])


def _new_item_columns(item_id, list_id, created_at, fields):
    cols = {
        "id": item_id, "list_id": list_id, "created_at": created_at,
        "name": "", "name_ts": 0, "name_by": "",
        "category": None, "category_ts": 0, "category_by": "",
        "stores": "[]", "stores_ts": 0, "stores_by": "",
        "quantity": None, "quantity_ts": 0, "quantity_by": "",
        "price_amount": None, "price_currency": None, "price_ts": 0, "price_by": "",
        "note": None, "note_ts": 0, "note_by": "",
        "status": "todo", "status_ts": 0, "status_by": "",
        "deleted": 0, "deleted_ts": 0, "deleted_by": "",
    }
    for key, (value, ts, by) in fields.items():
        cols.update(_item_field_to_columns(key, value))
        ts_col, by_col = ITEM_TSBY[key]
        cols[ts_col] = ts
        cols[by_col] = by
    return cols


def _new_list_columns(list_id, created_at, fields):
    cols = {
        "id": list_id, "created_at": created_at,
        "name": "", "name_ts": 0, "name_by": "",
        "category_order": "[]", "category_order_ts": 0, "category_order_by": "",
        "deleted": 0, "deleted_ts": 0, "deleted_by": "",
    }
    for key, (value, ts, by) in fields.items():
        cols.update(_list_field_to_columns(key, value))
        ts_col, by_col = LIST_TSBY[key]
        cols[ts_col] = ts
        cols[by_col] = by
    return cols


def _lww_update_columns(existing, fields, to_columns, tsby):
    """Columns to set for the fields whose (ts, by) beats the stored clock."""
    set_cols = {}
    for key, (value, ts, by) in fields.items():
        ts_col, by_col = tsby[key]
        if (ts, by) > (existing[ts_col], existing[by_col]):
            set_cols.update(to_columns(key, value))
            set_cols[ts_col] = ts
            set_cols[by_col] = by
    return set_cols


# ---- name-collision merge --------------------------------------------------


def _find_live_by_name(conn, list_id, name):
    return conn.execute(
        "SELECT * FROM items WHERE list_id = ? AND deleted = 0 AND lower(name) = lower(?)",
        (list_id, name),
    ).fetchone()


def _merge_group(conn, list_id, item_ids):
    """Merge items sharing a name into a single survivor (Spec §6 name-merge).

    Survivor = earliest created_at, then lexically-smaller id. Per-field LWW is
    applied across the whole group; losers are tombstoned with a server-authored
    clock. Losers are tombstoned *before* the survivor is set live so the live
    unique-name index is never transiently violated.
    """
    rows = [conn.execute("SELECT * FROM items WHERE id = ?", (i,)).fetchone() for i in item_ids]
    rows = [r for r in rows if r is not None]
    if len(rows) < 2:
        return

    survivor = min(rows, key=lambda r: (r["created_at"], r["id"]))
    losers = [r for r in rows if r["id"] != survivor["id"]]
    now = _now_ms()

    merged = {}
    for key, ts_col, by_col in ITEM_FIELD_META:
        if key == "deleted":
            continue
        best = max(rows, key=lambda r: (r[ts_col], r[by_col]))
        merged.update(_item_field_to_columns(key, _item_load_field(key, best)))
        merged[ts_col] = best[ts_col]
        merged[by_col] = best[by_col]

    for loser in losers:
        _update(conn, "items", loser["id"], {
            "deleted": 1, "deleted_ts": now, "deleted_by": SERVER_MERGE,
            "change_seq": _bump(conn),
        })

    merged.update({"deleted": 0, "deleted_ts": now, "deleted_by": SERVER_MERGE,
                   "change_seq": _bump(conn)})
    _update(conn, "items", survivor["id"], merged)


# ---- apply -----------------------------------------------------------------


def _apply_list(conn, account_id, device_id, obj):
    list_id, created_at, fields = _parse_row(obj, LIST_KEYS, LIST_TSBY, _validate_list_field, device_id)
    existing = conn.execute("SELECT * FROM lists WHERE id = ?", (list_id,)).fetchone()

    if existing is None:
        if "name" not in fields:
            raise ApiError(422, "invalid_name", "Creating a list requires a name.")
        cols = _new_list_columns(list_id, created_at, fields)
        cols["change_seq"] = _bump(conn)
        _insert(conn, "lists", cols)
        conn.execute(
            "INSERT OR IGNORE INTO memberships (account_id, list_id, joined_at) VALUES (?, ?, ?)",
            (account_id, list_id, _now_ms()),
        )
        return

    if not _is_member(conn, account_id, list_id):
        raise ApiError(403, "not_a_member", "You are not a member of this list.")
    set_cols = _lww_update_columns(existing, fields, _list_field_to_columns, LIST_TSBY)
    if set_cols:
        set_cols["change_seq"] = _bump(conn)
        _update(conn, "lists", list_id, set_cols)


def _apply_item(conn, account_id, device_id, obj):
    item_id, created_at, fields = _parse_row(obj, ITEM_KEYS, ITEM_TSBY, _validate_item_field, device_id)
    existing = conn.execute("SELECT * FROM items WHERE id = ?", (item_id,)).fetchone()

    # Authorize against the item's *actual* list: for an existing item that is
    # its stored list_id (a client can't reach into another list by mislabelling
    # the payload); for a new item, the payload's list_id.
    if existing is not None:
        list_id = existing["list_id"]
    else:
        list_id = obj.get("list_id")
        if not list_id:
            raise ApiError(422, "missing_list_id", "Each item change requires a list_id.")
        if not _list_exists(conn, list_id):
            raise ApiError(422, "unknown_list", "Item refers to an unknown list_id.")
    if not _is_member(conn, account_id, list_id):
        raise ApiError(403, "not_a_member", "You are not a member of this list.")

    if existing is None:
        if "name" not in fields:
            raise ApiError(422, "invalid_name", "Creating an item requires a name.")
        cols = _new_item_columns(item_id, list_id, created_at, fields)
        cols["change_seq"] = _bump(conn)
        try:
            _insert(conn, "items", cols)
        except sqlite3.IntegrityError:
            # A live item already holds this name: insert as a placeholder
            # tombstone (so the unique index is satisfied) and merge.
            cols["deleted"] = 1
            cols["deleted_ts"] = _now_ms()
            cols["deleted_by"] = SERVER_MERGE
            _insert(conn, "items", cols)
            rival = _find_live_by_name(conn, list_id, cols["name"])
            if rival is not None:
                _merge_group(conn, list_id, [rival["id"], item_id])
        return

    set_cols = _lww_update_columns(existing, fields, _item_field_to_columns, ITEM_TSBY)
    if not set_cols:
        return
    set_cols["change_seq"] = _bump(conn)
    try:
        _update(conn, "items", item_id, set_cols)
    except sqlite3.IntegrityError:
        # A rename/resurrect would collide with another live name: keep this row
        # dead and merge it into the rival, letting LWW pick the winning fields.
        new_name = set_cols.get("name", existing["name"])
        set_cols.update({"deleted": 1, "deleted_ts": _now_ms(), "deleted_by": SERVER_MERGE})
        _update(conn, "items", item_id, set_cols)
        rival = _find_live_by_name(conn, list_id, new_name)
        if rival is not None and rival["id"] != item_id:
            _merge_group(conn, list_id, [rival["id"], item_id])


def apply_changes(conn, account_id, device_id, changes) -> None:
    """Apply a client's pushed changes (lists first, then items) via field LWW.

    Lists are processed before items so that a batch which creates a list and
    its items in one shot registers the membership before item membership checks
    run. Name collisions are resolved inline (see ``_apply_item``). Does not
    commit — the caller owns the transaction.
    """
    changes = changes or {}
    for obj in changes.get("lists", []):
        _apply_list(conn, account_id, device_id, obj)
    for obj in changes.get("items", []):
        _apply_item(conn, account_id, device_id, obj)


def name_merge(conn, list_id) -> None:
    """Reconcile any live items in a list that share a name (case-insensitive).

    In normal operation ``apply_changes`` resolves collisions inline, so this is
    an idempotent post-apply safety net (S5 calls it per touched list). It is
    also the correct standalone reconciliation should live duplicates ever
    arise by another path.
    """
    rows = conn.execute(
        "SELECT id, lower(name) AS lname FROM items WHERE list_id = ? AND deleted = 0",
        (list_id,),
    ).fetchall()
    groups = {}
    for row in rows:
        groups.setdefault(row["lname"], []).append(row["id"])
    for ids in groups.values():
        if len(ids) > 1:
            _merge_group(conn, list_id, ids)


# ---- read side -------------------------------------------------------------


def check_cursor(conn, cursor) -> None:
    row = conn.execute("SELECT gc_horizon FROM meta WHERE id = 1").fetchone()
    if 0 < cursor < row["gc_horizon"]:
        raise ApiError(
            410, "full_resync_required", "Sync cursor is too old; a full resync is required."
        )


def delta(conn, account_id, cursor, full_lists=None) -> dict:
    """Rows the caller is missing: everything in their lists with
    ``change_seq > cursor`` (tombstones included so deletions propagate), plus a
    cursor-independent snapshot of every live row in ``full_lists`` (used when
    joining a list). Returns the new cursor (the global change_seq high-water)."""
    full_lists = full_lists or []
    member_ids = {
        r["list_id"]
        for r in conn.execute(
            "SELECT list_id FROM memberships WHERE account_id = ?", (account_id,)
        )
    }
    for list_id in full_lists:
        if list_id not in member_ids:
            raise ApiError(403, "not_a_member", "You are not a member of this list.")

    lists_out, items_out = {}, {}
    if member_ids:
        placeholders = ", ".join("?" * len(member_ids))
        params = (cursor, *member_ids)
        for row in conn.execute(
            f"SELECT * FROM lists WHERE change_seq > ? AND id IN ({placeholders})", params
        ):
            lists_out[row["id"]] = _list_to_wire(row)
        for row in conn.execute(
            f"SELECT * FROM items WHERE change_seq > ? AND list_id IN ({placeholders})", params
        ):
            items_out[row["id"]] = _item_to_wire(row)

    for list_id in full_lists:
        lrow = conn.execute(
            "SELECT * FROM lists WHERE id = ? AND deleted = 0", (list_id,)
        ).fetchone()
        if lrow is not None:
            lists_out[lrow["id"]] = _list_to_wire(lrow)
        for row in conn.execute(
            "SELECT * FROM items WHERE list_id = ? AND deleted = 0", (list_id,)
        ):
            items_out[row["id"]] = _item_to_wire(row)

    new_cursor = conn.execute("SELECT change_seq FROM meta WHERE id = 1").fetchone()["change_seq"]
    return {
        "cursor": new_cursor,
        "changes": {"lists": list(lists_out.values()), "items": list(items_out.values())},
    }
