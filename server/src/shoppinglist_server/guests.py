"""A ledger's guests: participants who have no account on this server (T-364).

Design of record: T-364 in the tracker; the wire side is "Guests" in docs/wire-contract.md.

A guest belongs to exactly one expenses list, has a server-minted id (`g:` + uuid4, so it can
never be mistaken for an account id), a free-text name, a `locked` flag and an optional
`claimed_by` account. Guests take part in entries like members, never vote, and never count
towards closing. Locking a guest freezes their amounts the way a close vote freezes a member's;
claiming one makes its amounts the claimant's, by alias on the clients — no stored entry is ever
rewritten — and by deferring to the claimant's own freeze on the server (closing.is_frozen).

Guests are server-maintained state on the list object, like `members`: they ride the synced list
row, and every change here bumps the list's change_seq so it reaches every device through the
ordinary incremental sync. They are written only through their own endpoints, plus the one
creation-time exception: a list row that creates a ledger may carry `guest_names`.

Nothing in this module commits; the route (or the sync request) owns the transaction.
"""

import json
import uuid

from .auth import initials_of, now_ms
from .errors import ApiError
from .invites import touch_list
from .server_settings import one_line_text

GUEST_ID_PREFIX = "g:"
NAME_MAX_CHARS = 64
# How many guests a ledger's creation row may bring along. A guest a person types is a guest a
# person knows; fifty is far beyond any trip, and the cap only blocks bloat.
CREATION_NAMES_MAX = 50


# ---- names ----------------------------------------------------------------------


def clean_name(value, field: str = "name", row_id: str | None = None) -> str:
    """A guest's name as it will be stored, or `422 invalid_guest_name`.

    The server message's rule (shared-test-cases/server-message.json): trimmed of tab, CR, LF,
    space and every Zs; refused if it then holds a control character, a line or paragraph
    separator, a bidi embedding or override or a lone surrogate; at most 64 code points. A name
    that is empty after trimming, or holds nothing but invisible format characters, is blank and
    refused too: a guest has to be told apart from the others by something you can read.
    """
    text = one_line_text(value, NAME_MAX_CHARS) if isinstance(value, str) else None
    if not text:
        details = {"field": field}
        if row_id is not None:
            details["row_id"] = row_id
        raise ApiError(
            422,
            "invalid_guest_name",
            f"A guest's name must be one line of 1 to {NAME_MAX_CHARS} characters.",
            details=details,
        )
    return text


# ---- reading ----------------------------------------------------------------------


def _to_wire(row) -> dict:
    return {
        "id": row["id"],
        "name": row["name"],
        # Resolved here, as a member's are, so a badge needs no second lookup.
        "initials": initials_of(row["name"]),
        "locked": bool(row["locked"]),
        "claimed_by": row["claimed_by"],
        "created_at": row["created_at"],
    }


def for_lists(conn, list_ids) -> dict:
    """Every list's guests in one query, oldest first then by id, as served on the list object."""
    result = {list_id: [] for list_id in list_ids}
    if not result:
        return result
    placeholders = ", ".join("?" * len(result))
    for row in conn.execute(
        f"SELECT * FROM guests WHERE list_id IN ({placeholders}) ORDER BY created_at, id",
        list(result),
    ):
        result[row["list_id"]].append(_to_wire(row))
    return result


def states(conn, list_id: str) -> dict:
    """{guest id: (locked, claimed_by)} for one list — what the freeze and the participant rule
    need to know about each guest."""
    return {
        row["id"]: (bool(row["locked"]), row["claimed_by"])
        for row in conn.execute(
            "SELECT id, locked, claimed_by FROM guests WHERE list_id = ?", (list_id,)
        )
    }


def _get(conn, list_id: str, guest_id: str):
    """The guest, scoped to THIS list: a guest of another list is as unknown here as one that
    never existed, so the answer reveals nothing about other lists."""
    row = conn.execute(
        "SELECT * FROM guests WHERE id = ? AND list_id = ?", (guest_id, list_id)
    ).fetchone()
    if row is None:
        raise ApiError(404, "guest_not_found", "This list has no such guest.")
    return row


def _reload(conn, guest_id: str) -> dict:
    return _to_wire(conn.execute("SELECT * FROM guests WHERE id = ?", (guest_id,)).fetchone())


# ---- writing ----------------------------------------------------------------------


def _insert(conn, list_id: str, name: str, account_id: str, created_at: int) -> str:
    guest_id = GUEST_ID_PREFIX + str(uuid.uuid4())
    conn.execute(
        "INSERT INTO guests (id, list_id, name, locked, claimed_by, created_at, created_by) "
        "VALUES (?, ?, ?, 0, NULL, ?, ?)",
        (guest_id, list_id, name, created_at, account_id),
    )
    return guest_id


def check_creation_names(value, row_id: str) -> list[str]:
    """Validate a creation row's `guest_names` (T-364): an array of at most 50 guest names.

    Blank names are refused, never skipped: dropping empty boxes is the client's job, and a
    server that quietly dropped some would leave the person wondering where a guest went.
    """

    def refuse(message):
        raise ApiError(
            422,
            "invalid_guest_name",
            message,
            details={"row_id": row_id, "field": "guest_names"},
        )

    if not isinstance(value, list):
        refuse("guest_names must be an array of names.")
    if len(value) > CREATION_NAMES_MAX:
        refuse(f"A new ledger may bring at most {CREATION_NAMES_MAX} guests.")
    return [clean_name(name, field="guest_names", row_id=row_id) for name in value]


def create_at_list_creation(conn, list_id: str, names: list[str], account_id: str) -> None:
    """Mint a new ledger's first guests, in the order typed (strictly increasing created_at, so
    the served order is the typed order). The list row was inserted moments ago with a fresh
    change_seq, so there is nothing more to touch."""
    start = now_ms()
    for offset, name in enumerate(names):
        _insert(conn, list_id, name, account_id, start + offset)


def _require_writable(conn, account_id: str, list_id: str) -> None:
    """The checks every guest endpoint shares, in the contract's order, after `not_a_member`:
    another kind of list, a closed one, a caller who has voted to close."""
    from . import closing  # deferred: closing -> sync -> this module

    closing.require_expenses_list(conn, list_id)
    closing.require_open(conn, list_id)
    # A voter changes nothing, and a claim or unclaim would move their own balance.
    closing.check_voter_may_change(conn, list_id, account_id, list_id, True)


def add(conn, account_id: str, list_id: str, name) -> dict:
    _require_writable(conn, account_id, list_id)
    clean = clean_name(name)
    guest_id = _insert(conn, list_id, clean, account_id, now_ms())
    touch_list(conn, list_id)
    return _reload(conn, guest_id)


def update(conn, account_id: str, list_id: str, guest_id: str, body: dict) -> dict:
    """Rename and/or lock or unlock. A claimed guest can still be renamed — the name is what the
    old entries show — but not locked or unlocked: the claimant's vote governs its numbers now."""
    _require_writable(conn, account_id, list_id)
    row = _get(conn, list_id, guest_id)
    if "name" not in body and "locked" not in body:
        raise ApiError(422, "invalid_request", "Send a name, locked, or both.")
    if "locked" in body and not isinstance(body["locked"], bool):
        raise ApiError(422, "invalid_request", "locked must be a boolean.")
    assignments = {}
    if "name" in body:
        assignments["name"] = clean_name(body["name"])
    if "locked" in body:
        if row["claimed_by"] is not None:
            raise ApiError(
                409,
                "guest_claimed",
                "This guest has been claimed; the claimant's own vote governs these amounts.",
            )
        assignments["locked"] = 1 if body["locked"] else 0
    conn.execute(
        f"UPDATE guests SET {', '.join(f'{k} = ?' for k in assignments)} WHERE id = ?",
        [*assignments.values(), guest_id],
    )
    touch_list(conn, list_id)
    return _reload(conn, guest_id)


def is_named_in_live_entry(conn, list_id: str, guest_id: str) -> bool:
    """Whether any live entry on the list names the guest, on either side. A tombstoned one does
    not count: if it is ever restored, the "already in the stored entry" rule admits the id."""
    for row in conn.execute(
        "SELECT expense FROM items WHERE list_id = ? AND deleted = 0 AND expense IS NOT NULL",
        (list_id,),
    ):
        expense = json.loads(row["expense"])
        if guest_id in (expense.get("paid_by") or {}) or guest_id in (
            expense.get("paid_for") or {}
        ):
            return True
    return False


def remove(conn, account_id: str, list_id: str, guest_id: str) -> None:
    _require_writable(conn, account_id, list_id)
    _get(conn, list_id, guest_id)
    if is_named_in_live_entry(conn, list_id, guest_id):
        raise ApiError(
            409,
            "guest_in_use",
            "Entries on this list still name this guest. Edit those entries first.",
        )
    conn.execute("DELETE FROM guests WHERE id = ?", (guest_id,))
    touch_list(conn, list_id)


def claim(conn, account_id: str, list_id: str, guest_id: str) -> dict:
    """ "This is me": the guest's amounts count towards the caller's balance from now on. No
    stored entry is rewritten; the clients fold the guest into the claimant."""
    _require_writable(conn, account_id, list_id)
    row = _get(conn, list_id, guest_id)
    if row["claimed_by"] is not None:
        raise ApiError(409, "guest_claimed", "Someone has already claimed this guest.")
    if row["locked"]:
        # Claiming moves the caller's balance by the guest's, which is exactly what locked
        # amounts must not do.
        raise ApiError(
            422,
            "participant_frozen",
            "This guest's amounts are locked. Unlock them before claiming the guest.",
            details={"account_id": guest_id},
        )
    conn.execute("UPDATE guests SET claimed_by = ? WHERE id = ?", (account_id, guest_id))
    touch_list(conn, list_id)
    return _reload(conn, guest_id)


def unclaim(conn, account_id: str, list_id: str, guest_id: str) -> dict:
    """Undo a claim — the claimant's alone, and only while the list is open."""
    _require_writable(conn, account_id, list_id)
    row = _get(conn, list_id, guest_id)
    if row["claimed_by"] != account_id:
        raise ApiError(409, "guest_claimed", "Only the account that claimed a guest can undo it.")
    conn.execute("UPDATE guests SET claimed_by = NULL WHERE id = ?", (guest_id,))
    touch_list(conn, list_id)
    return _reload(conn, guest_id)
