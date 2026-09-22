"""Field-level last-write-wins sync engine (Spec §6).

Pure service layer — no HTTP, no commits. Callers own the transaction boundary:
S5 runs ``check_cursor`` → ``apply_changes`` → ``name_merge`` → ``delta`` and
commits once at the end.

That sequence is a single transaction only when the request actually writes —
sqlite3 opens one just before the first DML statement, which is the first
``change_seq`` bump. A pull-only request never reaches DML, so it runs in
autocommit and each of its reads takes its own snapshot; ``delta`` is written to
stay correct under that (see the note on the cursor there).

LWW resolution: every syncable field carries ``(updated_at, updated_by)``. The
field value with the greatest ``(updated_at, updated_by)`` tuple wins; edits to
different fields of the same row therefore merge, while edits to the same field
resolve to the latest. ``updated_by`` is a deterministic tiebreak on equal
timestamps (larger string wins).
"""

import datetime
import json
import re
import sqlite3
import time

from . import auth
from . import db as db_module
from .errors import ApiError

STATUS_VALUES = {"backlog", "todo", "checked"}

# [0-9], NOT \d (T-125). Python's `\d` is Unicode-aware for str patterns, so it matches Eastern
# Arabic, Devanagari and every other Unicode decimal digit — while JS's `\d` and Java/Kotlin's are
# ASCII-only. The same-looking pattern therefore meant three different things, and the SERVER was
# the permissive one: it accepted "٥.٩٩", stored it, and served it to every member of the list,
# where both clients then failed to parse it (`parseFloat("٥.٩٩")` is NaN). One crafted row could
# render as NaN for everyone and survive in the database.
#
# The explicit character class is deliberate over `re.ASCII`: it is local and obvious, and cannot
# be undone by someone later recompiling the pattern without the flag.
#
# The identical constants live in web/src/lib/priceParse.ts and android's ItemFormViewModel.kt.
# Keep all three in step — the failure mode here was precisely that they LOOK identical and were
# not.
PRICE_AMOUNT_RE = re.compile(r"^[0-9]+(\.[0-9]{1,2})?$")
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
    ("expense", "expense_ts", "expense_by"),
    ("deleted", "deleted_ts", "deleted_by"),
]
LIST_FIELD_META = [
    ("name", "name_ts", "name_by"),
    ("category_order", "category_order_ts", "category_order_by"),
    ("notes", "notes_ts", "notes_by"),
    ("kind", "kind_ts", "kind_by"),
    ("currency", "currency_ts", "currency_by"),
    ("deleted", "deleted_ts", "deleted_by"),
]
# T-110: which item fields the clients render for a list. For shopping and checklist the server
# stores and syncs `kind` but no server logic depends on it — a checklist simply never sends
# stores/price/quantity, and those fields keep their existing validation if a client does send them.
#
# `expenses` (T-151) is different in kind, not just display: its items carry the `expense` money
# tuple and nothing else of the shopping shape, its item names are not unique, and the kind is
# fixed for the list's whole life in both directions. See
# docs/archive/specs/2026-09-17-expense-lists-design.md for why.
EXPENSES_KIND = "expenses"
LIST_KINDS = ("shopping", "checklist", EXPENSES_KIND)
DEFAULT_LIST_KIND = "shopping"
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
SQLITE_INT_MIN = -(2**63)
SQLITE_INT_MAX = 2**63 - 1

ID_MAX_LENGTH = (
    128  # client-minted row ids are UUID/ULID-scale (~26-36 chars); 128 is ample headroom.
)
NAME_MAX_LENGTH = (
    500  # item/list names are short labels; 500 covers verbose entries, blocks KB-scale bloat.
)
CATEGORY_MAX_LENGTH = 200  # a single category label; dozens of them fit in a list's category_order.
QUANTITY_MAX_LENGTH = (
    200  # free text like "2 l" / "3 boxes"; 200 is generous for any real quantity.
)
ITEM_NOTE_MAX_LENGTH = 5000  # freeform per-item annotation; same generosity as list notes.
UPDATED_BY_MAX_LENGTH = 128  # device/author id string, same scale as a row id.
PRICE_AMOUNT_MAX_LENGTH = (
    32  # decimal string; 32 digits is billions-with-cents, far beyond any real price.
)
PRICE_CURRENCY_MAX_LENGTH = 16  # ISO 4217 codes are 3 chars; 16 leaves room for any sane variant.
STRING_LIST_MAX_ITEMS = (
    200  # element cap for stores / category_order; dozens are normal, 200 is comfortable.
)
STRING_LIST_ELEM_MAX_LENGTH = (
    200  # each store name / category label, same scale as a category label.
)
# Free-text currency label of an expenses list (T-151): an ISO code, a symbol, or "pizza slices".
CURRENCY_LABEL_MAX_LENGTH = 32
# Entries per share map of one expense. Real lists have a handful of members; this only blocks bloat.
EXPENSE_SHARES_MAX = 200
EXPENSE_DATE_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

# The three kinds of entry an expenses list (a ledger) holds. The sign lives in the type, never in
# the amounts — everything on the wire stays a positive decimal — so an entry is structurally
# exactly one of the three: money the group spent, money it received, or money one member handed
# another. An absent `type` means `expense`, which is what every row written before this existed
# is; the server stores it normalised so a reader never has to infer it.
EXPENSE_TYPE_EXPENSE = "expense"
EXPENSE_TYPE_INCOME = "income"
EXPENSE_TYPE_TRANSFER = "transfer"
EXPENSE_TYPES = (EXPENSE_TYPE_EXPENSE, EXPENSE_TYPE_INCOME, EXPENSE_TYPE_TRANSFER)

# ---- clock clamping (T-86) --------------------------------------------------
# A client-supplied updated_at/created_at that is wildly in the future (broken
# or malicious clock) would otherwise beat every honest edit until that moment
# arrives, wedging a field for every member for years. Real clock skew is on
# the order of minutes, so anything further ahead than this is a broken or
# malicious clock; clamp it rather than reject the push outright (a 422 would
# quarantine an innocent device whose clock is merely wrong).
CLOCK_SKEW_ALLOWANCE_MS = 60 * 60 * 1000  # 1 hour

# ---- batch size cap (T-114) -------------------------------------------------
# MAX_CONTENT_LENGTH bounds the request in BYTES, which is the wrong unit: 4 MB of minimal item
# rows is ~38,900 of them (108 bytes each, measured), and applying them costs ~0.31 ms/row while
# holding SQLite's single write lock from the first change_seq bump to the final commit. That is
# ~12 s during which every other write on the instance — every login, registration, sync and
# settings change — blocks and then fails, because stock sqlite3 gives up after a 5 s busy timeout.
# One authenticated member could therefore stall the whole server at will, repeatedly.
#
# 250 rows is ~78 ms of work: comfortably below the busy timeout with room for a slow host, and far
# above any real edit burst. Only a bulk import exceeds it, and clients chunk to stay under.
# Note this bounds PUSHES only — a first-sync pull is a delta, which this does not touch.
MAX_CHANGES_PER_SYNC = 250
# The pull-side twin of the row cap (T-237): `delta` runs a snapshot query pair per
# full_lists entry, so an uncapped list let one member multiply the read work of a single
# request without pushing a single row. Same number as the push cap — a client joining more
# lists than this at once can ask for the rest in the next sync.
MAX_FULL_LISTS_PER_SYNC = MAX_CHANGES_PER_SYNC

ITEM_TSBY = {key: (ts, by) for key, ts, by in ITEM_FIELD_META}
LIST_TSBY = {key: (ts, by) for key, ts, by in LIST_FIELD_META}
ITEM_KEYS = set(ITEM_TSBY)
LIST_KEYS = set(LIST_TSBY)


def _now_ms() -> int:
    return time.time_ns() // 1_000_000


def _clamp_future_ms(value: int) -> int:
    """Cap a client-supplied ms-epoch timestamp at server-now + allowance (T-86).

    Values at or before server-now pass through untouched — an offline edit
    pushed late legitimately carries an old timestamp and must not be bumped
    forward.
    """
    return min(value, _now_ms() + CLOCK_SKEW_ALLOWANCE_MS)


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
    if key == "expense":
        return {"expense": None if value is None else _encode_expense(value)}
    if key == "deleted":
        return {"deleted": 1 if value else 0}
    return {key: value}


def expense_type(expense) -> str:
    """The type of one decoded entry, defaulting an absent one to `expense`.

    Rows written before the type existed carry no `type` key, and a client may legitimately omit
    it; both mean the same thing.
    """
    if not expense:
        return EXPENSE_TYPE_EXPENSE
    return expense.get("type") or EXPENSE_TYPE_EXPENSE


def _encode_expense(value) -> str:
    """Canonical JSON of a validated expense: exactly the six known keys, stable key order.

    `type` is written normalised, so an absent one survives only on rows stored before it existed,
    and only until their next write.

    Unknown keys a client sent are dropped rather than stored, so a newer client cannot bloat a row
    with fields this server does not understand and then have them served to every member.
    """
    canonical = {
        key: value[key] for key in ("paid_by", "equal_by", "paid_for", "equal_for", "date")
    }
    canonical["type"] = expense_type(value)
    return json.dumps(canonical, sort_keys=True, separators=(",", ":"))


def _decode_expense(stored: str) -> dict:
    """A stored expense as it goes on the wire: `type` filled in for a row written before the type
    existed, so every reader sees one (clients still default it defensively)."""
    value = json.loads(stored)
    if isinstance(value, dict):
        value.setdefault("type", EXPENSE_TYPE_EXPENSE)
    return value


def _item_load_field(key, row):
    if key == "stores":
        return json.loads(row["stores"])
    if key == "price":
        if row["price_amount"] is None:
            return None
        return {"amount": row["price_amount"], "currency": row["price_currency"]}
    if key == "expense":
        return None if row["expense"] is None else _decode_expense(row["expense"])
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


def _list_to_wire(row, members, close_votes) -> dict:
    fields = {
        key: {"value": _list_load_field(key, row), "updated_at": row[ts], "updated_by": row[by]}
        for key, ts, by in LIST_FIELD_META
    }
    return {
        "id": row["id"],
        "created_at": row["created_at"],
        "fields": fields,
        # Server-maintained, outside `fields` like an item's last_touched_by, never client-written
        # (T-152). The roster rides here so both clients have it offline with no cache of their
        # own; every membership, email or initials change bumps the list's change_seq to carry it.
        "members": members,
        "close_votes": close_votes,
        "closed_at": row["closed_at"],
    }


def _rosters(conn, list_ids) -> dict:
    """Current members of each list, oldest membership first, as served in `members`.

    joined_at is milliseconds, so two people who join in the same millisecond (a list created and
    shared by a script, or a test) would otherwise come back in whatever order SQLite chose.
    Email breaks the tie: stable across devices, and alphabetical is what a reader expects.
    """
    rosters = {list_id: [] for list_id in list_ids}
    if not rosters:
        return rosters
    placeholders = ", ".join("?" * len(rosters))
    for row in conn.execute(
        "SELECT memberships.list_id AS list_id, accounts.id AS account_id, "
        "accounts.email AS email, account_settings.initials AS initials "
        "FROM memberships "
        "JOIN accounts ON accounts.id = memberships.account_id "
        "LEFT JOIN account_settings ON account_settings.account_id = accounts.id "
        f"WHERE memberships.list_id IN ({placeholders}) "
        "ORDER BY memberships.joined_at, lower(accounts.email), accounts.id",
        list(rosters),
    ):
        rosters[row["list_id"]].append(
            {
                "account_id": row["account_id"],
                "email": row["email"],
                "initials": auth.resolve_initials(row["email"], row["initials"]),
            }
        )
    return rosters


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
            422,
            "invalid_field",
            f"Field '{key}' may contain at most {STRING_LIST_MAX_ITEMS} entries.",
        )
    for elem in value:
        if not isinstance(elem, str):
            raise ApiError(422, "invalid_field", f"Field '{key}' entries must be strings.")
        if len(elem) > STRING_LIST_ELEM_MAX_LENGTH:
            raise ApiError(
                422,
                "invalid_field",
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
            422,
            "invalid_field",
            f"Field '{key}' updated_by must be {UPDATED_BY_MAX_LENGTH} characters or fewer.",
        )
    updated_at = _clamp_future_ms(clock["updated_at"])
    return clock["value"], updated_at, by


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
        # isinstance first: an unhashable value (dict/list) would TypeError on the set
        # membership test itself (T-85).
        if not isinstance(value, str) or value not in STATUS_VALUES:
            raise ApiError(422, "invalid_status", f"status must be one of {sorted(STATUS_VALUES)}.")
    elif key == "price":
        _validate_price(value)
    elif key == "expense":
        _validate_expense(value)
    elif key == "deleted":
        _validate_deleted(value)


def amount_cents(amount: str) -> int:
    """Cents of an amount already matched by PRICE_AMOUNT_RE: "12" -> 1200, "12.5" -> 1250."""
    whole, _, fraction = amount.partition(".")
    return int(whole) * 100 + int(fraction.ljust(2, "0") or "0")


def _validate_share_map(key, shares) -> int:
    """Validate one of an expense's share maps; returns its total in cents."""
    if not isinstance(shares, dict) or not shares:
        raise ApiError(
            422, "invalid_expense", f"expense {key} must be a non-empty object of amounts."
        )
    if len(shares) > EXPENSE_SHARES_MAX:
        raise ApiError(
            422,
            "invalid_expense",
            f"expense {key} may name at most {EXPENSE_SHARES_MAX} participants.",
        )
    total = 0
    for account_id, amount in shares.items():
        if not account_id.strip() or len(account_id) > ID_MAX_LENGTH:
            raise ApiError(422, "invalid_expense", f"expense {key} has an invalid participant id.")
        if (
            not isinstance(amount, str)
            or len(amount) > PRICE_AMOUNT_MAX_LENGTH
            or not PRICE_AMOUNT_RE.match(amount)
        ):
            raise ApiError(
                422, "invalid_expense", f"expense {key} amounts must be decimal strings."
            )
        cents = amount_cents(amount)
        # Strictly positive: a participant with no share is absent from the map, never zero.
        if cents <= 0:
            raise ApiError(422, "invalid_expense", f"expense {key} amounts must be positive.")
        total += cents
    return total


def _validate_expense(value):
    """Shape of the expense money tuple (T-151). Context-free: which list it may sit on, and
    whether its participants are members, is checked in _check_expense_against_list."""
    if value is None:
        return  # permitted by shape; _check_expense_against_list decides per list kind
    if not isinstance(value, dict):
        raise ApiError(422, "invalid_expense", "expense must be an object.")
    # Absent is the one thing other than the three words that is accepted: rows written before the
    # type existed have none. An explicit null, a wrong case or an unknown word is refused rather
    # than coerced — silently filing someone's income as an expense flips a whole ledger's sign.
    # isinstance first: an unhashable value would otherwise be compared against the tuple's words.
    entry_type = value.get("type", EXPENSE_TYPE_EXPENSE)
    if not isinstance(entry_type, str) or entry_type not in EXPENSE_TYPES:
        raise ApiError(
            422, "invalid_expense", f"expense type must be one of: {', '.join(EXPENSE_TYPES)}."
        )
    paid_by = _validate_share_map("paid_by", value.get("paid_by"))
    paid_for = _validate_share_map("paid_for", value.get("paid_for"))
    for key in ("equal_by", "equal_for"):
        if not isinstance(value.get(key), bool):
            raise ApiError(422, "invalid_expense", f"expense {key} must be a boolean.")
    date = value.get("date")
    if not isinstance(date, str) or not EXPENSE_DATE_RE.match(date):
        raise ApiError(422, "invalid_expense", "expense date must be a YYYY-MM-DD date.")
    try:
        datetime.date.fromisoformat(date)
    except ValueError:
        raise ApiError(
            422, "invalid_expense", "expense date must be a real calendar date."
        ) from None
    # The one invariant that spans both maps, and the reason they are one LWW field rather than
    # several: compared in whole cents, never as floats.
    if paid_by != paid_for:
        raise ApiError(
            422, "invalid_expense", "expense paid_by and paid_for must sum to the same amount."
        )
    if entry_type == EXPENSE_TYPE_TRANSFER:
        # One member hands another money, so the two maps name one account each and not the same
        # one. That the amounts match is already the equal-sum rule above.
        if len(value["paid_by"]) != 1 or len(value["paid_for"]) != 1:
            raise ApiError(
                422,
                "invalid_expense",
                "a transfer must have exactly one sender and exactly one recipient.",
            )
        if next(iter(value["paid_by"])) == next(iter(value["paid_for"])):
            raise ApiError(
                422, "invalid_expense", "a transfer's sender and recipient must be different."
            )


def _validate_price(value):
    if value is None:
        return
    if not isinstance(value, dict):
        raise ApiError(
            422, "invalid_price", "price must be an object with a decimal amount string."
        )
    amount = value.get("amount")
    if not isinstance(amount, str) or not PRICE_AMOUNT_RE.match(amount):
        raise ApiError(422, "invalid_price", "price amount must be a decimal string.")
    if len(amount) > PRICE_AMOUNT_MAX_LENGTH:
        raise ApiError(
            422,
            "invalid_price",
            f"price amount must be {PRICE_AMOUNT_MAX_LENGTH} characters or fewer.",
        )
    currency = value.get("currency")
    if currency is not None:
        if not isinstance(currency, str):
            raise ApiError(422, "invalid_price", "price currency must be a string or null.")
        if len(currency) > PRICE_CURRENCY_MAX_LENGTH:
            raise ApiError(
                422,
                "invalid_price",
                f"price currency must be {PRICE_CURRENCY_MAX_LENGTH} characters or fewer.",
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
            raise ApiError(
                422, "invalid_notes", f"List notes must be {NOTES_MAX_LENGTH} characters or fewer."
            )
    elif key == "currency":
        # Its own code, not the settings one (T-199): default_currency wants a 3-letter ISO code
        # while a list's currency is free text under a length cap, and one code cannot carry both
        # rules — clients told users to type "EUR" when the list label was merely too long.
        _require_str(
            key, value, CURRENCY_LABEL_MAX_LENGTH, nullable=True, code="invalid_list_currency"
        )
    elif key == "kind":
        # Unknown kinds are rejected rather than coerced: a client sending a kind this server
        # doesn't know would otherwise get silent, surprising display behaviour (T-110).
        if not isinstance(value, str) or value not in LIST_KINDS:
            raise ApiError(
                422, "invalid_field", f"List kind must be one of: {', '.join(LIST_KINDS)}."
            )
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
            422,
            "invalid_row",
            f"Change id must be {ID_MAX_LENGTH} characters or fewer.",
            details={"row_id": row_id},
        )
    created_at = obj.get("created_at")
    if created_at is None:
        created_at = _now_ms()  # absent/null created_at is a legitimate client shape.
    elif not _is_int(created_at):
        raise ApiError(
            422, "invalid_row", "created_at must be an integer.", details={"row_id": row_id}
        )
    elif not (SQLITE_INT_MIN <= created_at <= SQLITE_INT_MAX):
        raise ApiError(
            422, "invalid_row", "created_at is out of range.", details={"row_id": row_id}
        )
    else:
        # T-86: bounds a far-future created_at (same _clamp_future_ms mechanism as
        # each field's updated_at in _parse_clock). Unlike updated_at, created_at
        # plays no role in per-field LWW resolution — it only feeds the merge-survivor
        # tiebreak (min(created_at, id), earliest wins, see _merge_group), which a
        # future-only clamp can't bound anyway: survivorship is gamed with a
        # small/past created_at, not a future one.
        created_at = _clamp_future_ms(created_at)
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


def _is_nonblank(value) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _wins(incoming, existing, ts_col, by_col) -> bool:
    """Whether a pushed field would beat what is stored — the same comparison _lww_update_columns
    makes, asked ahead of time so a rule can be applied to the value that will actually survive."""
    if incoming is None:
        return False
    if existing is None:
        return True
    _, ts, by = incoming
    return (ts, by) > (existing[ts_col], existing[by_col])


def _changes_anything(existing, fields, meta) -> bool:
    """Whether a pushed row would change anything: a new row always does, an existing one only
    if some field would win last-write-wins (see _wins)."""
    if existing is None:
        return True
    return any(
        _wins(fields[name], existing, ts_col, by_col)
        for name, ts_col, by_col in meta
        if name in fields
    )


def _expense_before_and_after(existing, fields):
    """The item's expense as it stands and as it will stand, with deletion folded in.

    A tombstoned expense counts for nothing, so deleting one is a change to None and restoring it
    a change from None. Both have to be visible to the freeze rule or they would be ways round it.
    """
    stored = None
    was_deleted = False
    if existing is not None:
        stored = _decode_expense(existing["expense"]) if existing["expense"] else None
        was_deleted = bool(existing["deleted"])

    after = (
        fields["expense"][0]
        if _wins(fields.get("expense"), existing, "expense_ts", "expense_by")
        else stored
    )
    deleted_after = (
        bool(fields["deleted"][0])
        if _wins(fields.get("deleted"), existing, "deleted_ts", "deleted_by")
        else was_deleted
    )
    return (None if was_deleted else stored), (None if deleted_after else after)


def _check_expense_against_list(conn, list_id, list_kind, item_id, existing, fields):
    """The rules on `expense` that depend on which list the item is on (T-151).

    - Only an expenses list holds expenses; any other kind may send null but never a value.
    - On an expenses list every item is an expense: required on create, never null afterwards.
    - Every participant must be a current member, or already be in the row's stored expense —
      so an expense involving someone who has since left stays editable.

    Both the null-expense rule and the participant rule are only applied to a write that would WIN
    last-write-wins (T-206): a stale write is discarded anyway, and rejecting it would quarantine
    an innocent offline edit on the client for a value that never took effect.
    """

    def reject(message, **extra):
        raise ApiError(
            422,
            "invalid_expense",
            message,
            details={"row_id": item_id, "field": "expense", **extra},
        )

    incoming = fields.get("expense")
    if list_kind != EXPENSES_KIND:
        if incoming is not None and incoming[0] is not None:
            reject("Only an expenses list can hold expenses.")
        return

    if incoming is None:
        if existing is None:
            reject("Creating an item on an expenses list requires an expense.")
        return
    # A create has no existing clock to lose against, so _wins is True there too (T-206): the
    # "requires an expense" rule above and the null check below both still bind on create.
    if not _wins(incoming, existing, "expense_ts", "expense_by"):
        return
    value = incoming[0]
    if value is None:
        reject("An item on an expenses list must have an expense.")

    already_present = set()
    if existing is not None and existing["expense"] is not None:
        stored = _decode_expense(existing["expense"])
        already_present = set(stored["paid_by"]) | set(stored["paid_for"])
    members = {
        row["account_id"]
        for row in conn.execute("SELECT account_id FROM memberships WHERE list_id = ?", (list_id,))
    }
    for participant in sorted(set(value["paid_by"]) | set(value["paid_for"])):
        if participant not in members and participant not in already_present:
            reject(
                "Every participant in an expense must be a member of the list.",
                account_id=participant,
            )


# ---- membership helpers ----------------------------------------------------


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
        "id": item_id,
        "list_id": list_id,
        "created_at": created_at,
        "name": "",
        "name_ts": 0,
        "name_by": "",
        "category": None,
        "category_ts": 0,
        "category_by": "",
        "stores": "[]",
        "stores_ts": 0,
        "stores_by": "",
        "quantity": None,
        "quantity_ts": 0,
        "quantity_by": "",
        "price_amount": None,
        "price_currency": None,
        "price_ts": 0,
        "price_by": "",
        "note": None,
        "note_ts": 0,
        "note_by": "",
        "status": "todo",
        "status_ts": 0,
        "status_by": "",
        "expense": None,
        "expense_ts": 0,
        "expense_by": "",
        "last_touched_by_account_id": None,
        "last_touched_ts": 0,
        "deleted": 0,
        "deleted_ts": 0,
        "deleted_by": "",
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
        "id": list_id,
        "created_at": created_at,
        "name": "",
        "name_ts": 0,
        "name_by": "",
        "category_order": "[]",
        "category_order_ts": 0,
        "category_order_by": "",
        "notes": None,
        "notes_ts": 0,
        "notes_by": "",
        "kind": DEFAULT_LIST_KIND,
        "kind_ts": 0,
        "kind_by": "",
        "currency": None,
        "currency_ts": 0,
        "currency_by": "",
        "deleted": 0,
        "deleted_ts": 0,
        "deleted_by": "",
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
        _update(
            conn,
            "items",
            loser["id"],
            {
                "deleted": 1,
                "deleted_ts": now,
                "deleted_by": SERVER_MERGE,
                "change_seq": _bump(conn),
            },
        )

    merged.update(
        {"deleted": 0, "deleted_ts": now, "deleted_by": SERVER_MERGE, "change_seq": _bump(conn)}
    )
    _update(conn, "items", survivor["id"], merged)


# ---- apply -----------------------------------------------------------------


def _apply_list(conn, account_id, device_id, obj):
    list_id, created_at, fields = _parse_row(
        obj, LIST_KEYS, LIST_TSBY, _validate_list_field, device_id
    )
    existing = conn.execute("SELECT * FROM lists WHERE id = ?", (list_id,)).fetchone()

    if existing is None:
        if "name" not in fields:
            raise ApiError(422, "invalid_name", "Creating a list requires a name.")
        kind = fields["kind"][0] if "kind" in fields else DEFAULT_LIST_KIND
        if kind == EXPENSES_KIND and not _is_nonblank(fields.get("currency", (None,))[0]):
            raise ApiError(
                422,
                "invalid_list_currency",
                "An expenses list requires a currency.",
                details={"row_id": list_id, "field": "currency"},
            )
        cols = _new_list_columns(list_id, created_at, fields)
        cols["change_seq"] = _bump(conn)
        _insert(conn, "lists", cols)
        conn.execute(
            "INSERT OR IGNORE INTO memberships (account_id, list_id, joined_at) VALUES (?, ?, ?)",
            (account_id, list_id, _now_ms()),
        )
        return

    # Residual, deliberate (T-120): unlike _apply_item above, this branch cannot be made uniform.
    # Pushing an unused list id CREATES the list, so "created" vs "403" still distinguishes a free
    # id from a taken one — and it can't be closed without either refusing legitimate creates or
    # lying about them. Closing it properly means the server minting list ids instead of accepting
    # client-minted ones, which the offline-first model rules out. Real clients use UUIDs, so what
    # leaks is only whether a *guessed* id is in use.
    if not _is_member(conn, account_id, list_id):
        raise ApiError(403, "not_a_member", "You are not a member of this list.")
    if "kind" in fields:
        new_kind = fields["kind"][0]
        # Fixed for life in both directions (T-151), whatever the clock says: an expenses list's
        # items have a different shape from every other kind's, so there is no conversion that
        # keeps the data meaningful. Shopping <-> checklist stays a free display toggle.
        if new_kind != existing["kind"] and EXPENSES_KIND in (new_kind, existing["kind"]):
            raise ApiError(
                422,
                "invalid_field",
                "A list cannot be converted to or from the expenses kind.",
                details={"row_id": list_id, "field": "kind"},
            )
    if existing["kind"] == EXPENSES_KIND:
        # Deferred import: closing reads this module's constants, so importing it at module level
        # would close a cycle. By the time any write happens, both modules are fully loaded.
        from . import closing

        if closing.is_closed(conn, list_id):
            raise ApiError(
                422,
                "list_closed",
                "This list is closed; nothing on it can be changed.",
                details={"row_id": list_id},
            )
        # No delete for an expenses list, ever (T-157): any member tombstoning a shared ledger is
        # a larger hole than leaving it. A closed list is left one member at a time instead, and
        # the last one out orphans it.
        if "deleted" in fields and fields["deleted"][0]:
            raise ApiError(
                422,
                "cannot_delete_expense_list",
                "An expenses list cannot be deleted. Close it and leave it instead.",
                details={"row_id": list_id, "field": "deleted"},
            )
        # Nor its own fields — name, notes — by someone who has agreed to close it (T-193).
        closing.check_voter_may_change(
            conn, list_id, account_id, list_id, _changes_anything(existing, fields, LIST_FIELD_META)
        )
    if existing["kind"] == EXPENSES_KIND and "currency" in fields:
        new_currency = fields["currency"][0]
        if not _is_nonblank(new_currency):
            raise ApiError(
                422,
                "invalid_list_currency",
                "An expenses list requires a currency.",
                details={"row_id": list_id, "field": "currency"},
            )
        # Fixed for life like the kind (T-207), whatever the clock says. Both clients have always
        # promised this — neither offers a way to change it, and both say so on screen — while the
        # server only forbade blanking it. Every amount already recorded is in this label's units,
        # so a change would silently relabel the whole ledger. Re-sending the same value is not a
        # change: clients push the whole list row on every edit.
        if new_currency != existing["currency"]:
            raise ApiError(
                422,
                "invalid_field",
                "An expenses list's currency cannot be changed.",
                details={"row_id": list_id, "field": "currency"},
            )
    set_cols = _lww_update_columns(existing, fields, _list_field_to_columns, LIST_TSBY)
    if set_cols:
        set_cols["change_seq"] = _bump(conn)
        _update(conn, "lists", list_id, set_cols)


def _apply_item(conn, account_id, device_id, obj):
    item_id, created_at, fields = _parse_row(
        obj, ITEM_KEYS, ITEM_TSBY, _validate_item_field, device_id
    )
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
                422,
                "missing_list_id",
                "Each item change requires a list_id.",
                details={"row_id": item_id},
            )
        # A mis-typed list_id (dict/list/…) would crash at the SQL bind below; reject it
        # with the row-scoped 422 the quarantine flow needs (T-85).
        if not isinstance(list_id, str):
            raise ApiError(
                422,
                "invalid_row",
                "Item list_id must be a string.",
                details={"row_id": item_id},
            )
    # One answer for "no such list" and "exists, but not yours" (T-120). Splitting them — 422
    # unknown_list vs 403 not_a_member — let a non-member probe which list ids are in use, which is
    # exactly the leak routes/lists.py's uniform 403 on GET /members exists to prevent. Membership
    # implies existence (memberships.list_id is a FK), so the single check covers both.
    #
    # 422-with-row_id, not 403: both clients quarantine a 422 naming a row (T-32) and hard-fail a
    # 403, so answering 403 here would wedge the whole push queue behind one unpushable row. This
    # also improves the legitimate case — a device pushing items for a list the account has since
    # left now parks that row instead of blocking every later edit.
    if not _is_member(conn, account_id, list_id):
        raise ApiError(
            422,
            "unknown_list",
            "Item refers to an unknown list_id.",
            details={"row_id": item_id},
        )
    list_kind = conn.execute("SELECT kind FROM lists WHERE id = ?", (list_id,)).fetchone()["kind"]
    if list_kind == EXPENSES_KIND:
        from . import closing  # deferred: see the note in _apply_list

        # Closed first (T-206): nothing on a closed list is worth validating further, and the
        # Android client keys its "drop the row and re-pull" handling on list_closed, so a row
        # that also breaks an expense rule must still be answered with list_closed, not that rule.
        if closing.is_closed(conn, list_id):
            raise ApiError(
                422,
                "list_closed",
                "This list is closed; nothing on it can be changed.",
                details={"row_id": item_id},
            )
    _check_expense_against_list(conn, list_id, list_kind, item_id, existing, fields)
    if list_kind == EXPENSES_KIND:
        # Before the freeze check, so a voter is told the rule that actually applies to them.
        closing.check_voter_may_change(
            conn, list_id, account_id, item_id, _changes_anything(existing, fields, ITEM_FIELD_META)
        )
        before, after = _expense_before_and_after(existing, fields)
        closing.check_write_against_freeze(conn, list_id, item_id, before, after)

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
    won_ts = max(
        (set_cols[ts_col] for ts_col, _ in ITEM_TSBY.values() if ts_col in set_cols), default=None
    )
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
    # Checked before any row is applied, so an over-cap batch costs nothing (T-114). Deliberately
    # carries NO row_id: the batch is too big, no individual row is at fault, and a row_id would
    # make the clients quarantine an innocent row instead of chunking.
    total = len(lists) + len(items)
    if total > MAX_CHANGES_PER_SYNC:
        raise ApiError(
            422,
            "too_many_changes",
            f"A sync batch may contain at most {MAX_CHANGES_PER_SYNC} changes; "
            f"got {total}. Split the push into smaller batches.",
            details={"max_changes": MAX_CHANGES_PER_SYNC},
        )
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

    Never on an expenses list (T-151): two "Dinner at Luigi's" there are two dinners.
    """
    kind_row = conn.execute("SELECT kind FROM lists WHERE id = ?", (list_id,)).fetchone()
    if kind_row is not None and kind_row["kind"] == EXPENSES_KIND:
        return
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
    # The cursor is taken BEFORE the row queries, deliberately (T-253). A pull-only
    # request pushes nothing, so sqlite3 issues no implicit BEGIN for it (it does that
    # only ahead of DML) and every statement below reads its own snapshot. Reading the
    # high-water mark last meant a row another device committed mid-request landed in
    # neither the rows nor the cursor: the response omitted it and still told the client
    # it was up to date, so `change_seq > cursor` never matched it again and the row was
    # invisible on that device until somebody edited it.
    #
    # Taken first, the cursor can only ever lag the rows: such a row is either delivered
    # anyway (a later snapshot sees it) or arrives on the next pull, because the cursor
    # stays below it. Over-delivery is idempotent under LWW; under-delivery loses data.
    #
    # The alternative — wrapping the whole request in an explicit transaction — was
    # rejected: a DEFERRED transaction that reads first and writes later gets
    # SQLITE_BUSY_SNAPSHOT (which the busy timeout does not retry) as soon as another
    # connection commits in between, turning a concurrent push into a hard failure, and
    # BEGIN IMMEDIATE would make every pull-only request queue for the single write lock.
    new_cursor = conn.execute("SELECT change_seq FROM meta WHERE id = 1").fetchone()["change_seq"]
    # A request that DOES write is still exact: its own bumps are already in change_seq
    # here, and its rows are read inside the transaction those bumps opened.
    member_ids = {
        r["list_id"]
        for r in conn.execute("SELECT list_id FROM memberships WHERE account_id = ?", (account_id,))
    }
    for list_id in full_lists:
        if list_id not in member_ids:
            raise ApiError(403, "not_a_member", "You are not a member of this list.")

    list_rows, items_out = {}, {}
    if member_ids:
        placeholders = ", ".join("?" * len(member_ids))
        params = (cursor, *member_ids)
        for row in conn.execute(
            f"SELECT * FROM lists WHERE change_seq > ? AND id IN ({placeholders})", params
        ):
            list_rows[row["id"]] = row
        for row in conn.execute(
            f"SELECT * FROM items WHERE change_seq > ? AND list_id IN ({placeholders})", params
        ):
            items_out[row["id"]] = _item_to_wire(row)

    for list_id in full_lists:
        lrow = conn.execute(
            "SELECT * FROM lists WHERE id = ? AND deleted = 0", (list_id,)
        ).fetchone()
        if lrow is not None:
            list_rows[lrow["id"]] = lrow
        for row in conn.execute(
            "SELECT * FROM items WHERE list_id = ? AND deleted = 0", (list_id,)
        ):
            items_out[row["id"]] = _item_to_wire(row)

    rosters = _rosters(conn, list_rows)
    from . import closing  # deferred: see the note in _apply_list

    vote_state = closing.votes_for_lists(conn, list_rows)
    lists_out = [
        _list_to_wire(row, rosters[list_id], vote_state[list_id])
        for list_id, row in list_rows.items()
    ]
    return {
        "cursor": new_cursor,
        "changes": {"lists": lists_out, "items": list(items_out.values())},
    }
