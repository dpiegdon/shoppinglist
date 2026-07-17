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
    ("notes", "notes_ts", "notes_by"),
    ("deleted", "deleted_ts", "deleted_by"),
]
NOTES_MAX_LENGTH = 5000

# ---- validation caps (T-85) ------------------------------------------------
# The /sync engine used to trust pushed field payloads almost entirely: a
# mis-typed value (e.g. `stores` as a string) was stored verbatim and served
# back to every member, permanently wedging strict clients' pulls, and a
# structurally junk value (id as a dict, an out-of-range int) 500'd instead of
# returning the row-scoped 422 the Android quarantine flow (T-32) needs. These
# caps bound each field to what a real client legitimately sends; each is
# generous enough for genuine usage while blocking bloat/poisoning.

# SQLite stores integers as signed 64-bit; anything outside this range raises
# OverflowError at bind time. Timestamps (ms epoch) and created_at live here.
SQLITE_INT_MIN = -(2 ** 63)
SQLITE_INT_MAX = 2 ** 63 - 1

ID_MAX_LENGTH = 128  # client-minted row ids are UUID/ULID-scale (~26-36 chars); 128 is ample headroom.
NAME_MAX_LENGTH = 500  # item/list names are short labels; 500 covers verbose entries, blocks KB-scale bloat.
CATEGORY_MAX_LENGTH = 200  # a single category label; dozens of them fit in a list's category_order.
QUANTITY_MAX_LENGTH = 200  # free text like "2 l" / "3 boxes"; 200 is generous for any real quantity.
ITEM_NOTE_MAX_LENGTH = 5000  # freeform per-item annotation; same generosity as list notes.
UPDATED_BY_MAX_LENGTH = 128  # device/author id string, same scale as a row id.
PRICE_AMOUNT_MAX_LENGTH = 32  # decimal string; 32 digits is billions-with-cents, far beyond any real price.
PRICE_CURRENCY_MAX_LENGTH = 16  # ISO 4217 codes are 3 chars; 16 leaves room for any sane variant.
STRING_LIST_MAX_ITEMS = 200  # element cap for stores / category_order; dozens are normal, 200 is comfortable.
STRING_LIST_ELEM_MAX_LENGTH = 200  # each store name / category label, same scale as a category label.

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
        # Whole-item, account-scoped (T-64) — not a syncable field the client can set itself, so it
        # rides outside `fields`. NULL for a row whose column predates its first post-migration edit.
        "last_touched_by": row["last_touched_by_account_id"],
    }


def _list_to_wire(row) -> dict:
    fields = {
        key: {"value": _list_load_field(key, row), "updated_at": row[ts], "updated_by": row[by]}
        for key, ts, by in LIST_FIELD_META
    }
    return {"id": row["id"], "created_at": row["created_at"], "fields": fields}


# ---- parsing / validation --------------------------------------------------


def _is_int(value) -> bool:
    # bool is an int subclass in Python, but a JSON true/false is never a valid
    # timestamp/created_at — treat it as the wrong type.
    return isinstance(value, int) and not isinstance(value, bool)


def _require_int64(key, value, label):
    if not _is_int(value):
        raise ApiError(422, "invalid_field", f"Field '{key}' requires an integer {label}.")
    if not (SQLITE_INT_MIN <= value <= SQLITE_INT_MAX):
        raise ApiError(422, "invalid_field", f"Field '{key}' {label} is out of range.")


def _require_str(key, value, max_length, *, nullable, code="invalid_field"):
    if value is None:
        if nullable:
            return
        raise ApiError(422, code, f"Field '{key}' must not be null.")
    if not isinstance(value, str):
        raise ApiError(422, code, f"Field '{key}' must be a string.")
    if len(value) > max_length:
        raise ApiError(422, code, f"Field '{key}' must be {max_length} characters or fewer.")


def _require_str_list(key, value, *, nullable):
    if value is None:
        if nullable:
            return  # null is coerced to [] downstream — a legitimate client shape.
        raise ApiError(422, "invalid_field", f"Field '{key}' must not be null.")
    if not isinstance(value, list):
        raise ApiError(422, "invalid_field", f"Field '{key}' must be a list of strings.")
    if len(value) > STRING_LIST_MAX_ITEMS:
        raise ApiError(
            422, "invalid_field", f"Field '{key}' may contain at most {STRING_LIST_MAX_ITEMS} entries."
        )
    for elem in value:
        if not isinstance(elem, str):
            raise ApiError(422, "invalid_field", f"Field '{key}' entries must be strings.")
        if len(elem) > STRING_LIST_ELEM_MAX_LENGTH:
            raise ApiError(
                422, "invalid_field",
                f"Field '{key}' entries must be {STRING_LIST_ELEM_MAX_LENGTH} characters or fewer.",
            )


def _parse_clock(key, clock, device_id):
    if not isinstance(clock, dict) or "value" not in clock:
        raise ApiError(422, "invalid_field", f"Field '{key}' must be an object with a value.")
    _require_int64(key, clock.get("updated_at"), "updated_at")
    by = clock.get("updated_by")
    if not by:  # None / "" / other falsy -> fall back to the request's device id.
        by = device_id or ""
    if not isinstance(by, str):
        raise ApiError(422, "invalid_field", f"Field '{key}' updated_by must be a string.")
    if len(by) > UPDATED_BY_MAX_LENGTH:
        raise ApiError(
            422, "invalid_field", f"Field '{key}' updated_by must be {UPDATED_BY_MAX_LENGTH} characters or fewer."
        )
    return clock["value"], clock["updated_at"], by


def _validate_deleted(value):
    # `deleted` is a boolean flag (JSON true/false on the wire); anything else is junk.
    if not isinstance(value, bool):
        raise ApiError(422, "invalid_field", "deleted must be a boolean.")


def _validate_item_field(key, value):
    if key == "name":
        # Empty/null keeps the established invalid_name code; a wrong type is a new invalid_field.
        if value is None or (isinstance(value, str) and not value.strip()):
            raise ApiError(422, "invalid_name", "Item name must not be empty.")
        _require_str(key, value, NAME_MAX_LENGTH, nullable=False)
    elif key == "category":
        _require_str(key, value, CATEGORY_MAX_LENGTH, nullable=True)
    elif key == "quantity":
        _require_str(key, value, QUANTITY_MAX_LENGTH, nullable=True)
    elif key == "note":
        _require_str(key, value, ITEM_NOTE_MAX_LENGTH, nullable=True)
    elif key == "stores":
        _require_str_list(key, value, nullable=True)
    elif key == "status":
        if value not in STATUS_VALUES:
            raise ApiError(422, "invalid_status", f"status must be one of {sorted(STATUS_VALUES)}.")
    elif key == "price":
        _validate_price(value)
    elif key == "deleted":
        _validate_deleted(value)


def _validate_price(value):
    if value is None:
        return
    if not isinstance(value, dict):
        raise ApiError(422, "invalid_price", "price must be an object with a decimal amount string.")
    amount = value.get("amount")
    if not isinstance(amount, str) or not PRICE_AMOUNT_RE.match(amount):
        raise ApiError(422, "invalid_price", "price amount must be a decimal string.")
    if len(amount) > PRICE_AMOUNT_MAX_LENGTH:
        raise ApiError(422, "invalid_price", f"price amount must be {PRICE_AMOUNT_MAX_LENGTH} characters or fewer.")
    currency = value.get("currency")
    if currency is not None:
        if not isinstance(currency, str):
            raise ApiError(422, "invalid_price", "price currency must be a string or null.")
        if len(currency) > PRICE_CURRENCY_MAX_LENGTH:
            raise ApiError(
                422, "invalid_price", f"price currency must be {PRICE_CURRENCY_MAX_LENGTH} characters or fewer."
            )


def _validate_list_field(key, value):
    if key == "name":
        if value is None or (isinstance(value, str) and not value.strip()):
            raise ApiError(422, "invalid_name", "List name must not be empty.")
        _require_str(key, value, NAME_MAX_LENGTH, nullable=False)
    elif key == "category_order":
        _require_str_list(key, value, nullable=True)
    elif key == "notes":
        # Preserve the exact invalid_notes code+message on the length path (existing contract);
        # a wrong type is a new invalid_field rejection.
        if value is not None and not isinstance(value, str):
            raise ApiError(422, "invalid_field", "List notes must be a string.")
        if value is not None and len(value) > NOTES_MAX_LENGTH:
            raise ApiError(422, "invalid_notes", f"List notes must be {NOTES_MAX_LENGTH} characters or fewer.")
    elif key == "deleted":
        _validate_deleted(value)


def _parse_row(obj, keys, tsby, validate, device_id):
    if not isinstance(obj, dict) or "id" not in obj:
        raise ApiError(422, "invalid_row", "Each change requires an id.")
    row_id = obj["id"]
    # id must be a clean, non-empty string within cap. A junk id (dict/int/…) means the row
    # isn't identifiable, so no row_id detail can help a client quarantine it.
    if not isinstance(row_id, str) or not row_id.strip():
        raise ApiError(422, "invalid_row", "Change id must be a non-empty string.")
    if len(row_id) > ID_MAX_LENGTH:
        raise ApiError(
            422, "invalid_row", f"Change id must be {ID_MAX_LENGTH} characters or fewer.",
            details={"row_id": row_id},
        )
    created_at = obj.get("created_at")
    if created_at is None:
        created_at = _now_ms()  # absent/null created_at is a legitimate client shape.
    elif not _is_int(created_at):
        raise ApiError(422, "invalid_row", "created_at must be an integer.", details={"row_id": row_id})
    elif not (SQLITE_INT_MIN <= created_at <= SQLITE_INT_MAX):
        raise ApiError(422, "invalid_row", "created_at is out of range.", details={"row_id": row_id})
    fields_obj = obj.get("fields")
    if fields_obj is None:
        fields_obj = {}  # absent/null fields is a legitimate (if pointless) shape.
    elif not isinstance(fields_obj, dict):
        raise ApiError(422, "invalid_row", "fields must be an object.", details={"row_id": row_id})
    fields = {}
    for key, clock in fields_obj.items():
        if key not in keys:
            continue  # forward-compatible: ignore unknown fields
        try:
            value, ts, by = _parse_clock(key, clock, device_id)
            validate(key, value)
        except ApiError as exc:
            # Name the offending row + field so a client can quarantine just this row
            # rather than have one bad value wedge its entire push queue (T-32).
            exc.details = {"row_id": row_id, "field": key}
            raise
        fields[key] = (value, ts, by)
    return row_id, created_at, fields


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


def _new_item_columns(item_id, list_id, created_at, fields, account_id):
    cols = {
        "id": item_id, "list_id": list_id, "created_at": created_at,
        "name": "", "name_ts": 0, "name_by": "",
        "category": None, "category_ts": 0, "category_by": "",
        "stores": "[]", "stores_ts": 0, "stores_by": "",
        "quantity": None, "quantity_ts": 0, "quantity_by": "",
        "price_amount": None, "price_currency": None, "price_ts": 0, "price_by": "",
        "note": None, "note_ts": 0, "note_by": "",
        "status": "todo", "status_ts": 0, "status_by": "",
        "last_touched_by_account_id": None, "last_touched_ts": 0,
        "deleted": 0, "deleted_ts": 0, "deleted_by": "",
    }
    for key, (value, ts, by) in fields.items():
        cols.update(_item_field_to_columns(key, value))
        ts_col, by_col = ITEM_TSBY[key]
        cols[ts_col] = ts
        cols[by_col] = by
    # Whole-item authorship (T-64): whoever creates the item is its "last touched by" until
    # some other field write later beats it — the creating account, at the latest of this
    # payload's field timestamps (every provided field is a "write" on a brand-new row).
    if fields:
        cols["last_touched_by_account_id"] = account_id
        cols["last_touched_ts"] = max(ts for _, ts, _ in fields.values())
    return cols


def _new_list_columns(list_id, created_at, fields):
    cols = {
        "id": list_id, "created_at": created_at,
        "name": "", "name_ts": 0, "name_by": "",
        "category_order": "[]", "category_order_ts": 0, "category_order_by": "",
        "notes": None, "notes_ts": 0, "notes_by": "",
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

    # Whole-item authorship (T-64) — same "pick the max across the group" shape as each
    # per-field winner above, but item-level: whichever row was touched most recently.
    last_touched_source = max(
        rows, key=lambda r: (r["last_touched_ts"], r["last_touched_by_account_id"] or "")
    )
    merged["last_touched_by_account_id"] = last_touched_source["last_touched_by_account_id"]
    merged["last_touched_ts"] = last_touched_source["last_touched_ts"]

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
            raise ApiError(
                422, "missing_list_id", "Each item change requires a list_id.",
                details={"row_id": item_id},
            )
        # A mis-typed list_id (dict/list/…) would crash at the SQL bind below; reject it
        # with the row-scoped 422 the quarantine flow needs (T-85).
        if not isinstance(list_id, str):
            raise ApiError(
                422, "invalid_row", "Item list_id must be a string.",
                details={"row_id": item_id},
            )
        if not _list_exists(conn, list_id):
            raise ApiError(422, "unknown_list", "Item refers to an unknown list_id.")
    if not _is_member(conn, account_id, list_id):
        raise ApiError(403, "not_a_member", "You are not a member of this list.")

    if existing is None:
        if "name" not in fields:
            raise ApiError(422, "invalid_name", "Creating an item requires a name.")
        cols = _new_item_columns(item_id, list_id, created_at, fields, account_id)
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
    # Whole-item authorship (T-64): only among the fields that actually WON this round —
    # a losing (stale) field write must not look like a more recent "touch" than really
    # happened. account_id is the whole push's authenticated account, not a per-field device.
    won_ts = max((set_cols[ts_col] for ts_col, _ in ITEM_TSBY.values() if ts_col in set_cols), default=None)
    if won_ts is not None and won_ts > existing["last_touched_ts"]:
        set_cols["last_touched_by_account_id"] = account_id
        set_cols["last_touched_ts"] = won_ts
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
    if not isinstance(changes, dict):
        raise ApiError(422, "invalid_changes", "changes must be an object.")
    lists = changes.get("lists") or []
    items = changes.get("items") or []
    if not isinstance(lists, list):
        raise ApiError(422, "invalid_changes", "changes.lists must be a list.")
    if not isinstance(items, list):
        raise ApiError(422, "invalid_changes", "changes.items must be a list.")
    for obj in lists:
        _apply_list(conn, account_id, device_id, obj)
    for obj in items:
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
