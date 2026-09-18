"""Closing an expenses list by unanimous vote, and the freeze that protects a voter (T-157).

Design and reasons: docs/archive/specs/2026-09-17-expense-lists-design.md.

There is no proposal step. Any member may cast a close vote at any time and withdraw it while the
list is open; the list closes the instant the last CURRENT member's vote lands, in the same
transaction. Someone who joins while votes are pending raises the bar; someone who has left does
not count and cannot block.

Two rules hang off that:

* A closed list is a read-only archive — no writes at all, no new members — but it can be left,
  which an open one cannot. That asymmetry is the point: leaving is how a settled list is
  eventually let go, and how an unsettled one would be walked away from.
* While a list is open, a participant who has voted (or has left) has their numbers frozen: no
  write may change what they paid or what they owe. That is what makes a vote meaningful — without
  it, agreeing to close would not stop the total moving underneath you.
"""

from . import db as db_module
from .auth import now_ms
from .errors import ApiError
from .sync import EXPENSES_KIND, amount_cents


def _list_row(conn, list_id):
    return conn.execute("SELECT kind, closed_at FROM lists WHERE id = ?", (list_id,)).fetchone()


def is_closed(conn, list_id: str) -> bool:
    row = _list_row(conn, list_id)
    return row is not None and row["closed_at"] is not None


def closed_at(conn, list_id: str):
    row = _list_row(conn, list_id)
    return row["closed_at"] if row is not None else None


def votes(conn, list_id: str) -> list[str]:
    """Account ids that have voted to close, oldest vote first."""
    return [
        row["account_id"]
        for row in conn.execute(
            "SELECT account_id FROM close_votes WHERE list_id = ? ORDER BY voted_at, account_id",
            (list_id,),
        )
    ]


def votes_for_lists(conn, list_ids) -> dict:
    """Every list's votes in one query, for the sync delta."""
    result = {list_id: [] for list_id in list_ids}
    if not result:
        return result
    placeholders = ", ".join("?" * len(result))
    for row in conn.execute(
        f"SELECT list_id, account_id FROM close_votes WHERE list_id IN ({placeholders}) "
        "ORDER BY voted_at, account_id",
        list(result),
    ):
        result[row["list_id"]].append(row["account_id"])
    return result


def _members(conn, list_id: str) -> set:
    return {
        row["account_id"]
        for row in conn.execute("SELECT account_id FROM memberships WHERE list_id = ?", (list_id,))
    }


def require_expenses_list(conn, list_id: str) -> None:
    row = _list_row(conn, list_id)
    if row is None or row["kind"] != EXPENSES_KIND:
        raise ApiError(409, "not_an_expenses_list", "Only an expenses list can be voted closed.")


def require_open(conn, list_id: str) -> None:
    if is_closed(conn, list_id):
        raise ApiError(409, "list_closed", "This list is closed.")


def is_frozen(conn, list_id: str, account_id: str, members=None, voters=None) -> bool:
    """Whether this participant's amounts may not move while the list is open.

    Voters, because that is what a vote buys them. And anyone who is not a member any more: their
    debts and credits belong to someone who is no longer around to object to them being edited.
    """
    members = _members(conn, list_id) if members is None else members
    voters = set(votes(conn, list_id)) if voters is None else voters
    return account_id in voters or account_id not in members


def cast_vote(conn, account_id: str, list_id: str) -> None:
    """Record this account's agreement to close, and close the list if it was the last one needed.

    Does not commit — the route owns the transaction, so the vote and the close that it triggers
    land together or not at all.
    """
    require_expenses_list(conn, list_id)
    require_open(conn, list_id)
    conn.execute(
        "INSERT OR IGNORE INTO close_votes (list_id, account_id, voted_at) VALUES (?, ?, ?)",
        (list_id, account_id, now_ms()),
    )
    _close_if_unanimous(conn, list_id)


def withdraw_vote(conn, account_id: str, list_id: str) -> None:
    require_expenses_list(conn, list_id)
    require_open(conn, list_id)
    conn.execute(
        "DELETE FROM close_votes WHERE list_id = ? AND account_id = ?", (list_id, account_id)
    )
    _touch(conn, list_id)


def _close_if_unanimous(conn, list_id: str) -> bool:
    """Close iff every current member has voted. Returns whether this call closed the list."""
    members = _members(conn, list_id)
    voted = set(votes(conn, list_id))
    # Current means current: a member who joined while votes were pending raises the bar, and a
    # vote left behind by someone who has since left does not help reach it.
    if members and members <= voted:
        conn.execute(
            "UPDATE lists SET closed_at = ?, change_seq = ? WHERE id = ?",
            (now_ms(), db_module.next_change_seq(conn), list_id),
        )
        return True
    _touch(conn, list_id)
    return False


def _touch(conn, list_id: str) -> None:
    """Move the list row so the vote state reaches every device (T-152's mechanism)."""
    conn.execute(
        "UPDATE lists SET change_seq = ? WHERE id = ?",
        (db_module.next_change_seq(conn), list_id),
    )


def close_if_unanimous_after_membership_change(conn, list_id: str) -> None:
    """Re-check unanimity after the roster changed.

    Someone leaving can be the event that completes a vote — the remaining members had all agreed
    and were waiting only on them.

    Their own vote is already gone: both ways out of a list (leave, delete the account) remove it
    as part of removing the membership, so there is no stale-vote sweep to do here.
    """
    row = _list_row(conn, list_id)
    if row is None or row["kind"] != EXPENSES_KIND or row["closed_at"] is not None:
        return
    _close_if_unanimous(conn, list_id)


def expense_amounts(expense) -> dict:
    """Flatten one expense to {account_id: (paid_by_cents, paid_for_cents)} for comparison."""
    amounts = {}
    if not expense:
        return amounts
    for key, index in (("paid_by", 0), ("paid_for", 1)):
        for account_id, amount in (expense.get(key) or {}).items():
            current = amounts.setdefault(account_id, [0, 0])
            current[index] += amount_cents(amount)
    return {account_id: tuple(pair) for account_id, pair in amounts.items()}


def check_voter_may_add(conn, list_id: str, account_id: str, item_id: str) -> None:
    """Refuse a new expense from a member who has agreed to close the list (T-192).

    The freeze protects a voter's own amounts from everyone else; this is the other half.
    Agreeing to close means being done with the list, so a voter adds nothing more, not even an
    expense between two other people, until they withdraw the vote. 422 with the row id, not
    409: a device that added the expense offline, before its owner voted, must park that row
    rather than wedge its whole push queue (T-32).
    """
    if account_id in votes(conn, list_id):
        raise ApiError(
            422,
            "voted_to_close",
            "You have agreed to close this list, so you cannot add expenses to it. "
            "Withdraw your vote to add one.",
            details={"row_id": item_id},
        )


def check_write_against_freeze(conn, list_id: str, item_id: str, before_value, after_value) -> None:
    """Refuse a write that would move a frozen participant's paid or owed amount.

    Both arguments are decoded expenses or None — the caller has already resolved which value wins
    last-write-wins, and whether the row ends up deleted. A new row therefore arrives as a change
    from None and a deletion as a change to None, so neither is a way around the rule.
    """
    before = expense_amounts(before_value)
    after = expense_amounts(after_value)
    if before == after:
        return
    members = _members(conn, list_id)
    voters = set(votes(conn, list_id))
    if not voters and not (set(before) | set(after)) - members:
        return  # nobody is frozen on this list, which is the normal case
    for account_id in sorted(set(before) | set(after)):
        if before.get(account_id, (0, 0)) == after.get(account_id, (0, 0)):
            continue
        if is_frozen(conn, list_id, account_id, members=members, voters=voters):
            raise ApiError(
                422,
                "participant_frozen",
                "This expense involves someone whose amounts are frozen: they have agreed to "
                "close the list, or are no longer a member.",
                details={"row_id": item_id, "field": "expense", "account_id": account_id},
            )
