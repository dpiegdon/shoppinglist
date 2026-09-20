"""Closing an expenses list, and the freeze that protects a voter (T-157).

Design: docs/archive/specs/2026-09-17-expense-lists-design.md.
"""

import sqlite3

import pytest

from shoppinglist_server import accounts, auth, closing, invites
from shoppinglist_server import migrations as migrations_module
from shoppinglist_server import sync
from shoppinglist_server.errors import ApiError

PW = "password123"
KEY = b"test-hmac-key"


def _clock(value, ts, by="devA"):
    return {"value": value, "updated_at": ts, "updated_by": by}


def _register(conn, email):
    return auth.register(conn, email, PW)


def _expense(paid_by, paid_for, date="2026-09-17", entry_type=...):
    expense = {
        "paid_by": paid_by,
        "equal_by": False,
        "paid_for": paid_for,
        "equal_for": False,
        "date": date,
    }
    if entry_type is not ...:  # ... means "send no type", which reads as an expense
        expense["type"] = entry_type
    return expense


def _item(item_id, expense, name="Dinner", ts=100, by="devA", deleted=None):
    fields = {"name": _clock(name, ts, by)}
    if expense is not ...:
        fields["expense"] = _clock(expense, ts, by)
    if deleted is not None:
        fields["deleted"] = _clock(deleted, ts, by)
    return {"id": item_id, "list_id": "trip", "created_at": 1000, "fields": fields}


def _apply(conn, account_id, lists=(), items=(), device="devA"):
    sync.apply_changes(conn, account_id, device, {"lists": list(lists), "items": list(items)})


def _rejects(conn, account_id, code, lists=(), items=()):
    with pytest.raises(ApiError) as excinfo:
        _apply(conn, account_id, lists=lists, items=items)
    assert excinfo.value.code == code, excinfo.value.code
    return excinfo.value


def _add_member(conn, account_id, list_id="trip"):
    conn.execute(
        "INSERT INTO memberships (account_id, list_id, joined_at) VALUES (?, ?, 0)",
        (account_id, list_id),
    )


def _make_list(conn, owner, kind="expenses", list_id="trip"):
    fields = {"name": _clock("Trip", 100), "kind": _clock(kind, 100)}
    if kind == "expenses":
        fields["currency"] = _clock("EUR", 100)
    _apply(conn, owner, lists=[{"id": list_id, "created_at": 1000, "fields": fields}])


@pytest.fixture
def trip(db_conn):
    """An expenses list with alice, bob and one expense they share."""
    alice = _register(db_conn, "alice@example.com")
    bob = _register(db_conn, "bob@example.com")
    _make_list(db_conn, alice)
    _add_member(db_conn, bob)
    _apply(db_conn, alice, items=[_item("e1", _expense({alice: "30"}, {alice: "15", bob: "15"}))])
    return {"alice": alice, "bob": bob}


# ---- the vote ---------------------------------------------------------------


def test_one_vote_of_two_leaves_the_list_open(db_conn, trip):
    closing.cast_vote(db_conn, trip["alice"], "trip")

    assert closing.votes(db_conn, "trip") == [trip["alice"]]
    assert not closing.is_closed(db_conn, "trip")


def test_the_last_vote_closes_the_list(db_conn, trip):
    closing.cast_vote(db_conn, trip["alice"], "trip")
    closing.cast_vote(db_conn, trip["bob"], "trip")

    assert closing.is_closed(db_conn, "trip")
    assert closing.closed_at(db_conn, "trip") is not None


def test_voting_twice_is_not_two_votes(db_conn, trip):
    closing.cast_vote(db_conn, trip["alice"], "trip")
    closing.cast_vote(db_conn, trip["alice"], "trip")

    assert closing.votes(db_conn, "trip") == [trip["alice"]]
    assert not closing.is_closed(db_conn, "trip")


def test_a_vote_can_be_withdrawn_while_the_list_is_open(db_conn, trip):
    closing.cast_vote(db_conn, trip["alice"], "trip")
    closing.withdraw_vote(db_conn, trip["alice"], "trip")

    assert closing.votes(db_conn, "trip") == []


def test_someone_joining_raises_the_bar(db_conn, trip):
    """Current means current: a new member's agreement is needed too, even mid-vote."""
    closing.cast_vote(db_conn, trip["alice"], "trip")
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)

    closing.cast_vote(db_conn, trip["bob"], "trip")
    assert not closing.is_closed(db_conn, "trip")

    closing.cast_vote(db_conn, carol, "trip")
    assert closing.is_closed(db_conn, "trip")


def test_a_departing_member_can_be_what_completes_the_vote(db_conn, trip):
    """Everyone still here had already agreed and was waiting only on the one who left."""
    closing.cast_vote(db_conn, trip["alice"], "trip")
    accounts.delete_account(db_conn, trip["bob"], PW)

    assert closing.is_closed(db_conn, "trip")


def test_a_departed_accounts_vote_does_not_stand_in_for_a_members(db_conn, trip):
    closing.cast_vote(db_conn, trip["bob"], "trip")
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    accounts.delete_account(db_conn, trip["bob"], PW)

    # Alice and carol are left, neither has voted, and bob's vote left with his account.
    assert closing.votes(db_conn, "trip") == []
    assert not closing.is_closed(db_conn, "trip")


def test_a_closed_list_takes_no_more_votes(db_conn, trip):
    closing.cast_vote(db_conn, trip["alice"], "trip")
    closing.cast_vote(db_conn, trip["bob"], "trip")

    for action in (closing.cast_vote, closing.withdraw_vote):
        with pytest.raises(ApiError) as excinfo:
            action(db_conn, trip["alice"], "trip")
        assert excinfo.value.status == 409
        assert excinfo.value.code == "list_closed"


def test_only_an_expenses_list_can_be_closed(db_conn):
    alice = _register(db_conn, "alice@example.com")
    _make_list(db_conn, alice, kind="shopping")

    with pytest.raises(ApiError) as excinfo:
        closing.cast_vote(db_conn, alice, "trip")
    assert excinfo.value.code == "not_an_expenses_list"


def test_the_vote_state_rides_on_the_synced_list(db_conn, trip):
    cursor = sync.delta(db_conn, trip["alice"], 0)["cursor"]
    closing.cast_vote(db_conn, trip["bob"], "trip")

    wire = sync.delta(db_conn, trip["alice"], cursor)["changes"]["lists"][0]
    assert wire["close_votes"] == [trip["bob"]]
    assert wire["closed_at"] is None

    closing.cast_vote(db_conn, trip["alice"], "trip")
    wire = sync.delta(db_conn, trip["alice"], cursor)["changes"]["lists"][0]
    assert sorted(wire["close_votes"]) == sorted([trip["alice"], trip["bob"]])
    assert wire["closed_at"] is not None


# ---- what a closed list refuses ---------------------------------------------


def _close(conn, trip):
    closing.cast_vote(conn, trip["alice"], "trip")
    closing.cast_vote(conn, trip["bob"], "trip")


def test_a_closed_list_takes_no_item_writes(db_conn, trip):
    _close(db_conn, trip)
    a = trip["alice"]

    error = _rejects(db_conn, a, "list_closed", items=[_item("e2", _expense({a: "5"}, {a: "5"}))])
    assert error.details == {"row_id": "e2"}
    _rejects(db_conn, a, "list_closed", items=[_item("e1", ..., name="Renamed", ts=300)])
    _rejects(db_conn, a, "list_closed", items=[_item("e1", ..., ts=300, deleted=True)])


def test_a_closed_lists_broken_participant_rule_still_answers_list_closed(db_conn, trip):
    """list_closed comes before every expense-shaped rule (T-206): the Android client keys its
    "drop the row and re-pull" handling on list_closed, and would otherwise quarantine this row
    on invalid_expense instead."""
    _close(db_conn, trip)
    a = trip["alice"]
    stranger = _register(db_conn, "stranger@example.com")
    expense = _expense({a: "10"}, {a: "5", stranger: "5"})

    error = _rejects(db_conn, a, "list_closed", items=[_item("e1", expense, ts=300)])
    assert error.details["row_id"] == "e1"


def test_a_closed_lists_new_item_without_an_expense_still_answers_list_closed(db_conn, trip):
    _close(db_conn, trip)

    error = _rejects(db_conn, trip["alice"], "list_closed", items=[_item("e2", ...)])
    assert error.details["row_id"] == "e2"


def test_a_closed_list_takes_no_list_writes(db_conn, trip):
    _close(db_conn, trip)
    rename = {"id": "trip", "fields": {"name": _clock("Renamed", 300)}}

    _rejects(db_conn, trip["alice"], "list_closed", lists=[rename])


def test_a_closed_list_takes_no_new_members(db_conn, trip):
    _close(db_conn, trip)
    with pytest.raises(ApiError) as excinfo:
        invites.mint(db_conn, KEY, "http://x", "trip", "carol@example.com", trip["alice"])
    assert excinfo.value.code == "list_closed"


def test_an_invite_minted_before_closing_stops_working(db_conn, trip):
    """Otherwise closing would not be final: a link in someone's inbox could still admit them."""
    carol = _register(db_conn, "carol@example.com")
    minted = invites.mint(db_conn, KEY, "http://x", "trip", "carol@example.com", trip["alice"])
    _close(db_conn, trip)

    with pytest.raises(ApiError) as excinfo:
        invites.redeem(
            db_conn, KEY, auth.Account(id=carol, email="carol@example.com"), minted["token"]
        )
    assert excinfo.value.code == "list_closed"


# ---- leaving ----------------------------------------------------------------


def test_an_open_expenses_list_cannot_be_left(db_conn, trip):
    with pytest.raises(ApiError) as excinfo:
        invites.leave(db_conn, trip["bob"], "trip")
    assert excinfo.value.status == 409
    assert excinfo.value.code == "list_open"


def test_a_closed_expenses_list_can_be_left(db_conn, trip):
    _close(db_conn, trip)
    invites.leave(db_conn, trip["bob"], "trip")

    assert closing.votes(db_conn, "trip") == [trip["alice"]]
    remaining = db_conn.execute(
        "SELECT COUNT(*) AS n FROM memberships WHERE list_id = 'trip'"
    ).fetchone()["n"]
    assert remaining == 1


def test_the_last_member_out_orphans_a_closed_list(db_conn, trip):
    _close(db_conn, trip)
    invites.leave(db_conn, trip["bob"], "trip")
    invites.leave(db_conn, trip["alice"], "trip")

    row = db_conn.execute("SELECT deleted FROM lists WHERE id = 'trip'").fetchone()
    assert row["deleted"] == 1


def test_other_kinds_of_list_are_still_free_to_leave(db_conn):
    alice = _register(db_conn, "alice@example.com")
    bob = _register(db_conn, "bob@example.com")
    _make_list(db_conn, alice, kind="shopping")
    _add_member(db_conn, bob)

    invites.leave(db_conn, bob, "trip")


def test_deleting_an_account_is_never_refused(db_conn, trip):
    """GDPR: an open list cannot be a reason to keep someone's account alive."""
    accounts.delete_account(db_conn, trip["bob"], PW)

    assert db_conn.execute("SELECT COUNT(*) AS n FROM accounts").fetchone()["n"] == 1


# ---- no delete, ever ---------------------------------------------------------


@pytest.mark.parametrize("close_first", [False, True])
def test_an_expenses_list_can_never_be_deleted(db_conn, trip, close_first):
    if close_first:
        _close(db_conn, trip)
    tombstone = {"id": "trip", "fields": {"deleted": _clock(True, 300)}}

    error = _rejects(
        db_conn,
        trip["alice"],
        "cannot_delete_expense_list" if not close_first else "list_closed",
        lists=[tombstone],
    )
    assert error.details["row_id"] == "trip"


def test_other_kinds_of_list_are_still_deletable(db_conn):
    alice = _register(db_conn, "alice@example.com")
    _make_list(db_conn, alice, kind="shopping")

    _apply(db_conn, alice, lists=[{"id": "trip", "fields": {"deleted": _clock(True, 300)}}])
    assert db_conn.execute("SELECT deleted FROM lists").fetchone()["deleted"] == 1


# ---- the freeze --------------------------------------------------------------


def test_nothing_is_frozen_before_anyone_votes(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    _apply(db_conn, a, items=[_item("e1", _expense({a: "40"}, {a: "20", b: "20"}), ts=200)])


def test_a_voters_amount_cannot_be_changed(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    closing.cast_vote(db_conn, b, "trip")

    error = _rejects(
        db_conn,
        a,
        "participant_frozen",
        items=[_item("e1", _expense({a: "40"}, {a: "20", b: "20"}), ts=200)],
    )
    assert error.details == {"row_id": "e1", "field": "expense", "account_id": b}


def test_a_voter_can_still_be_left_exactly_as_they_were(db_conn, trip):
    """The rule is about their numbers, not about the row: everyone else can still be corrected."""
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    _apply(db_conn, a, items=[_item("e2", _expense({a: "30"}, {a: "10", b: "10", carol: "10"}))])
    closing.cast_vote(db_conn, b, "trip")

    _apply(
        db_conn, a, items=[_item("e2", _expense({a: "30"}, {a: "15", b: "10", carol: "5"}), ts=200)]
    )


def test_a_new_expense_cannot_involve_a_voter(db_conn, trip):
    """A new row is a change from zero, which is the common case the rule exists for."""
    a, b = trip["alice"], trip["bob"]
    closing.cast_vote(db_conn, b, "trip")

    _rejects(db_conn, a, "participant_frozen", items=[_item("e2", _expense({a: "10"}, {b: "10"}))])


def test_a_new_expense_among_the_unfrozen_is_still_fine(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    closing.cast_vote(db_conn, b, "trip")

    _apply(db_conn, a, items=[_item("e2", _expense({a: "10"}, {a: "5", carol: "5"}))])


def test_a_voter_cannot_add_an_expense_even_between_other_people(db_conn, trip):
    """The reported case (T-192): the freeze guarded the voter's amounts, not the list."""
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    closing.cast_vote(db_conn, a, "trip")

    error = _rejects(
        db_conn, a, "voted_to_close", items=[_item("e2", _expense({b: "10"}, {b: "5", carol: "5"}))]
    )
    # A row id, so a device that added it offline parks the row instead of wedging its queue.
    assert error.details == {"row_id": "e2"}


def test_a_voter_is_told_they_voted_rather_than_that_they_are_frozen(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    closing.cast_vote(db_conn, a, "trip")

    _rejects(
        db_conn, a, "voted_to_close", items=[_item("e2", _expense({a: "10"}, {a: "5", b: "5"}))]
    )


def test_a_voter_cannot_edit_an_expense_even_one_not_involving_them(db_conn, trip):
    """T-193: a voter changes nothing, not just their own amounts."""
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    _apply(db_conn, b, items=[_item("e2", _expense({b: "10"}, {b: "5", carol: "5"}))])
    closing.cast_vote(db_conn, a, "trip")

    _rejects(db_conn, a, "voted_to_close", items=[_item("e2", ..., name="Renamed", ts=300)])


def test_a_voter_cannot_delete_an_expense(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    _apply(db_conn, b, items=[_item("e2", _expense({b: "10"}, {b: "5", carol: "5"}))])
    closing.cast_vote(db_conn, a, "trip")

    _rejects(db_conn, a, "voted_to_close", items=[_item("e2", ..., ts=300, deleted=True)])


def test_a_voter_cannot_change_the_list_itself(db_conn, trip):
    a = trip["alice"]
    closing.cast_vote(db_conn, a, "trip")

    for field, value in (("name", "Renamed"), ("notes", "Gate code 4471")):
        error = _rejects(
            db_conn,
            a,
            "voted_to_close",
            lists=[{"id": "trip", "fields": {field: _clock(value, 300)}}],
        )
        assert error.details == {"row_id": "trip"}


def test_a_voters_stale_offline_edit_is_discarded_rather_than_refused(db_conn, trip):
    """A write that loses last-write-wins changes nothing, so there is nothing to refuse: a device
    that edited offline before its owner voted does not end up with a quarantined row."""
    a = trip["alice"]
    closing.cast_vote(db_conn, a, "trip")

    # e1 was written at ts=100; this edit is older and loses.
    _apply(db_conn, a, items=[_item("e1", ..., name="Stale", ts=50)])
    row = db_conn.execute("SELECT name FROM items WHERE id = 'e1'").fetchone()
    assert row["name"] == "Dinner"


def test_other_members_still_edit_what_does_not_touch_a_voter(db_conn, trip):
    """The voter's own lock is theirs alone; the freeze still guards what involves them."""
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    _apply(db_conn, b, items=[_item("e2", _expense({b: "10"}, {b: "5", carol: "5"}))])
    closing.cast_vote(db_conn, a, "trip")

    _apply(db_conn, carol, items=[_item("e2", ..., name="Renamed", ts=300)])
    _apply(db_conn, carol, items=[_item("e2", ..., ts=400, deleted=True)])


def test_withdrawing_the_vote_lets_the_member_change_things_again(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    closing.cast_vote(db_conn, a, "trip")
    closing.withdraw_vote(db_conn, a, "trip")

    _apply(db_conn, a, items=[_item("e2", _expense({a: "10"}, {a: "5", b: "5"}))])
    _apply(db_conn, a, items=[_item("e2", ..., name="Renamed", ts=300)])
    _apply(db_conn, a, lists=[{"id": "trip", "fields": {"name": _clock("Renamed", 300)}}])


def test_someone_elses_vote_does_not_stop_a_member_adding(db_conn, trip):
    """The rule is about the one who voted; the others add as before, around the freeze."""
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    closing.cast_vote(db_conn, b, "trip")

    _apply(db_conn, carol, items=[_item("e2", _expense({carol: "10"}, {a: "5", carol: "5"}))])


def test_an_expense_involving_a_voter_cannot_be_deleted(db_conn, trip):
    """A deletion takes their share to zero, which is a change like any other."""
    b = trip["bob"]
    closing.cast_vote(db_conn, b, "trip")

    _rejects(
        db_conn,
        trip["alice"],
        "participant_frozen",
        items=[_item("e1", ..., ts=300, deleted=True)],
    )


def test_an_expense_not_involving_a_voter_can_be_deleted(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    _apply(db_conn, a, items=[_item("e2", _expense({a: "10"}, {a: "10"}))])
    closing.cast_vote(db_conn, b, "trip")

    _apply(db_conn, a, items=[_item("e2", ..., ts=300, deleted=True)])


def test_a_departed_participant_is_frozen_too(db_conn, trip):
    """Their debts belong to someone who is no longer around to object to them being edited."""
    a, b = trip["alice"], trip["bob"]
    accounts.delete_account(db_conn, b, PW)

    _rejects(
        db_conn,
        a,
        "participant_frozen",
        items=[_item("e1", _expense({a: "40"}, {a: "30", b: "10"}), ts=200)],
    )


def test_withdrawing_a_vote_thaws_the_amounts_again(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    closing.cast_vote(db_conn, b, "trip")
    _rejects(
        db_conn,
        a,
        "participant_frozen",
        items=[_item("e1", _expense({a: "40"}, {a: "20", b: "20"}), ts=200)],
    )

    closing.withdraw_vote(db_conn, b, "trip")
    _apply(db_conn, a, items=[_item("e1", _expense({a: "40"}, {a: "20", b: "20"}), ts=300)])


def test_a_stale_write_that_would_move_a_voter_is_not_rejected(db_conn, trip):
    """It loses last-write-wins and changes nothing, so refusing it would quarantine an innocent
    offline edit for something that happened while the device was offline."""
    a, b = trip["alice"], trip["bob"]
    newer = _expense({a: "50"}, {a: "25", b: "25"})
    _apply(db_conn, a, items=[_item("e1", newer, ts=500)])
    closing.cast_vote(db_conn, b, "trip")

    stale = _expense({a: "99"}, {a: "50", b: "49"})
    _apply(db_conn, b, items=[_item("e1", stale, ts=200, by="devB")], device="devB")

    row = sync.delta(db_conn, a, 0)["changes"]["items"][0]
    assert row["fields"]["expense"]["value"] == {**newer, "type": "expense"}


def test_an_unrelated_field_of_a_frozen_expense_can_still_be_edited(db_conn, trip):
    """The freeze is about money. Fixing a typo in the title moves nobody's balance."""
    b = trip["bob"]
    closing.cast_vote(db_conn, b, "trip")

    _apply(db_conn, trip["alice"], items=[_item("e1", ..., name="Dinner at Luigi's", ts=300)])


# ---- the freeze and the entry type (T-242) -----------------------------------


ENTRY_TYPES = ["expense", "income", "transfer"]


def _wire_expense(conn, account_id, item_id):
    items = sync.delta(conn, account_id, 0)["changes"]["items"]
    return next(item for item in items if item["id"] == item_id)["fields"]["expense"]["value"]


def test_the_amounts_of_an_entry_carry_the_types_sign(db_conn):
    """Income is the mirror of an expense; a transfer moves money exactly as an expense does."""
    paid = {"a": "30.00"}
    owed = {"b": "30.00"}

    assert closing.expense_amounts(_expense(paid, owed)) == {"a": (3000, 0), "b": (0, 3000)}
    assert closing.expense_amounts(_expense(paid, owed, entry_type="expense")) == {
        "a": (3000, 0),
        "b": (0, 3000),
    }
    assert closing.expense_amounts(_expense(paid, owed, entry_type="transfer")) == {
        "a": (3000, 0),
        "b": (0, 3000),
    }
    assert closing.expense_amounts(_expense(paid, owed, entry_type="income")) == {
        "a": (-3000, 0),
        "b": (0, -3000),
    }


@pytest.mark.parametrize("entry_type", ENTRY_TYPES)
def test_a_new_entry_of_any_type_cannot_involve_a_voter(db_conn, trip, entry_type):
    a, b = trip["alice"], trip["bob"]
    closing.cast_vote(db_conn, b, "trip")

    entry = _expense({a: "10"}, {b: "10"}, entry_type=entry_type)
    error = _rejects(db_conn, a, "participant_frozen", items=[_item("e2", entry)])
    assert error.details == {"row_id": "e2", "field": "expense", "account_id": b}


@pytest.mark.parametrize("entry_type", ENTRY_TYPES)
def test_the_amounts_of_an_entry_of_any_type_are_frozen(db_conn, trip, entry_type):
    a, b = trip["alice"], trip["bob"]
    _apply(db_conn, a, items=[_item("e2", _expense({a: "10"}, {b: "10"}, entry_type=entry_type))])
    closing.cast_vote(db_conn, b, "trip")

    bigger = _expense({a: "20"}, {b: "20"}, entry_type=entry_type)
    _rejects(db_conn, a, "participant_frozen", items=[_item("e2", bigger, ts=300)])


@pytest.mark.parametrize("entry_type", ENTRY_TYPES)
def test_an_entry_of_any_type_involving_a_voter_cannot_be_deleted(db_conn, trip, entry_type):
    a, b = trip["alice"], trip["bob"]
    _apply(db_conn, a, items=[_item("e2", _expense({a: "10"}, {b: "10"}, entry_type=entry_type))])
    closing.cast_vote(db_conn, b, "trip")

    _rejects(db_conn, a, "participant_frozen", items=[_item("e2", ..., deleted=True, ts=300)])


def test_flipping_an_expense_to_an_income_is_refused_when_a_voter_is_involved(db_conn, trip):
    """Same amounts, opposite meaning: what bob owed becomes what he is owed."""
    a, b = trip["alice"], trip["bob"]
    closing.cast_vote(db_conn, b, "trip")

    flipped = _expense({a: "30"}, {a: "15", b: "15"}, entry_type="income")
    error = _rejects(db_conn, a, "participant_frozen", items=[_item("e1", flipped, ts=300)])
    assert error.details == {"row_id": "e1", "field": "expense", "account_id": b}


def test_changing_only_the_type_is_refused_when_a_voter_is_involved(db_conn, trip):
    """The amounts are untouched, so only the type rule catches this: an expense turned into a
    transfer is no longer money the group spent, which moves everyone in it."""
    a, b = trip["alice"], trip["bob"]
    _apply(db_conn, a, items=[_item("e2", _expense({a: "10"}, {b: "10"}))])
    closing.cast_vote(db_conn, b, "trip")

    transfer = _expense({a: "10"}, {b: "10"}, entry_type="transfer")
    error = _rejects(db_conn, a, "participant_frozen", items=[_item("e2", transfer, ts=300)])
    assert error.details == {"row_id": "e2", "field": "expense", "account_id": b}


def test_changing_only_the_type_is_refused_when_a_departed_member_is_involved(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    _apply(db_conn, a, items=[_item("e2", _expense({a: "10"}, {b: "10"}))])
    accounts.delete_account(db_conn, b, PW)

    transfer = _expense({a: "10"}, {b: "10"}, entry_type="transfer")
    error = _rejects(db_conn, a, "participant_frozen", items=[_item("e2", transfer, ts=300)])
    assert error.details["account_id"] == b


def test_changing_only_the_type_is_fine_when_nobody_is_frozen(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    _apply(db_conn, a, items=[_item("e2", _expense({a: "10"}, {b: "10"}))])

    transfer = _expense({a: "10"}, {b: "10"}, entry_type="transfer")
    _apply(db_conn, a, items=[_item("e2", transfer, ts=300)])

    assert _wire_expense(db_conn, a, "e2") == transfer


def test_a_non_voter_may_change_the_type_of_an_entry_involving_nobody_frozen(db_conn, trip):
    """Someone else's vote freezes their own numbers, not the whole ledger."""
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    _apply(db_conn, a, items=[_item("e2", _expense({a: "10"}, {b: "10"}))])
    closing.cast_vote(db_conn, carol, "trip")

    transfer = _expense({a: "10"}, {b: "10"}, entry_type="transfer")
    _apply(db_conn, a, items=[_item("e2", transfer, ts=300)])

    assert _wire_expense(db_conn, a, "e2") == transfer


def test_a_new_income_among_the_unfrozen_is_still_fine(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    closing.cast_vote(db_conn, b, "trip")

    income = _expense({a: "10"}, {carol: "10"}, entry_type="income")
    _apply(db_conn, a, items=[_item("e2", income)])

    assert _wire_expense(db_conn, a, "e2") == income


def test_a_stale_type_change_that_would_move_a_voter_is_not_rejected(db_conn, trip):
    """It loses last-write-wins and changes nothing, so it is discarded like any other stale
    write rather than quarantining the row on the device that made it offline."""
    a, b = trip["alice"], trip["bob"]
    newer = _expense({a: "10"}, {b: "10"})
    _apply(db_conn, a, items=[_item("e2", newer, ts=500)])
    closing.cast_vote(db_conn, b, "trip")

    stale = _expense({a: "10"}, {b: "10"}, entry_type="transfer")
    _apply(db_conn, b, items=[_item("e2", stale, ts=200, by="devB")], device="devB")

    assert _wire_expense(db_conn, a, "e2") == {**newer, "type": "expense"}


def test_a_ledger_of_all_three_types_still_closes_by_unanimous_vote(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    _apply(
        db_conn,
        a,
        items=[
            _item("e2", _expense({a: "30"}, {a: "15", b: "15"}, entry_type="income")),
            _item("e3", _expense({b: "15"}, {a: "15"}, entry_type="transfer")),
        ],
    )

    closing.cast_vote(db_conn, a, "trip")
    assert not closing.is_closed(db_conn, "trip")
    closing.cast_vote(db_conn, b, "trip")

    assert closing.is_closed(db_conn, "trip")
    _rejects(
        db_conn,
        a,
        "list_closed",
        items=[_item("e4", _expense({a: "5"}, {b: "5"}, entry_type="transfer"), ts=900)],
    )


# ---- over HTTP ---------------------------------------------------------------


def _login(client, email):
    client.post("/api/v1/register", json={"email": email, "password": PW})
    body = client.post(
        "/api/v1/login", json={"email": email, "password": PW, "device_label": "d"}
    ).get_json()
    return body["token"], body["account_id"]


def test_the_close_vote_endpoints_report_the_state(client):
    token, me = _login(client, "solo@example.com")
    headers = {"Authorization": f"Bearer {token}"}
    client.post(
        "/api/v1/sync",
        headers=headers,
        json={
            "cursor": 0,
            "device_id": "devA",
            "changes": {
                "lists": [
                    {
                        "id": "trip",
                        "fields": {
                            "name": _clock("Trip", 100),
                            "kind": _clock("expenses", 100),
                            "currency": _clock("EUR", 100),
                        },
                    }
                ]
            },
        },
    )

    resp = client.delete("/api/v1/lists/trip/close-votes", headers=headers)
    assert resp.status_code == 200
    assert resp.get_json() == {"close_votes": [], "closed_at": None}

    # A list of one closes on a single vote, in the same request.
    resp = client.post("/api/v1/lists/trip/close-votes", headers=headers)
    assert resp.status_code == 200
    assert resp.get_json()["close_votes"] == [me]
    assert resp.get_json()["closed_at"] is not None

    resp = client.post("/api/v1/lists/trip/close-votes", headers=headers)
    assert resp.status_code == 409
    assert resp.get_json()["error"] == "list_closed"


def test_a_non_member_cannot_vote_or_probe(client):
    token, _ = _login(client, "owner@example.com")
    client.post(
        "/api/v1/sync",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "cursor": 0,
            "device_id": "devA",
            "changes": {
                "lists": [
                    {
                        "id": "trip",
                        "fields": {
                            "name": _clock("Trip", 100),
                            "kind": _clock("expenses", 100),
                            "currency": _clock("EUR", 100),
                        },
                    }
                ]
            },
        },
    )
    stranger_token, _ = _login(client, "stranger@example.com")

    for path in ("/api/v1/lists/trip/close-votes", "/api/v1/lists/no-such-list/close-votes"):
        resp = client.post(path, headers={"Authorization": f"Bearer {stranger_token}"})
        # The same answer either way, so the endpoint cannot be used to probe which ids exist.
        assert resp.status_code == 403
        assert resp.get_json()["error"] == "not_a_member"


# ---- the migration -----------------------------------------------------------


def test_migration_7_adds_closed_at_and_the_close_votes_table(tmp_path):
    conn = sqlite3.connect(str(tmp_path / "v6.db"))
    conn.row_factory = sqlite3.Row
    # Just the parts of the v6 schema migration 7 touches or references.
    conn.execute("CREATE TABLE accounts (id TEXT PRIMARY KEY)")
    conn.execute("CREATE TABLE lists (id TEXT PRIMARY KEY, kind TEXT NOT NULL DEFAULT 'shopping')")
    conn.execute("INSERT INTO accounts (id) VALUES ('a1')")
    conn.execute("INSERT INTO lists (id, kind) VALUES ('l1', 'expenses')")
    conn.commit()

    for statement in dict(migrations_module.MIGRATIONS)[7]:
        conn.execute(statement)
    conn.commit()

    # An existing list arrives open, which is the only safe default: closing is a decision the
    # members make, never one a schema change makes for them.
    assert (
        conn.execute("SELECT closed_at FROM lists WHERE id = 'l1'").fetchone()["closed_at"] is None
    )

    conn.execute("INSERT INTO close_votes VALUES ('l1', 'a1', 1000)")
    # One vote per member per list, enforced by the table rather than by the code that writes it.
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute("INSERT INTO close_votes VALUES ('l1', 'a1', 2000)")
    conn.close()


def test_fresh_schema_and_migration_7_build_the_same_close_votes_table(db_conn, tmp_path):
    # Compared as structure rather than as SQL text: the two are written by hand in two places
    # and differ in whitespace, which is not a difference this test should ever fail on.
    def shape_of(conn, table):
        columns = [
            (r["name"], r["type"].upper(), r["notnull"], r["pk"])
            for r in conn.execute(f"PRAGMA table_info({table})")
        ]
        keys = sorted(
            (r["table"], r["from"], r["to"])
            for r in conn.execute(f"PRAGMA foreign_key_list({table})")
        )
        indexes = sorted(
            (
                r["name"],
                r["unique"],
                tuple(c["name"] for c in conn.execute(f"PRAGMA index_info({r['name']})")),
            )
            for r in conn.execute(f"PRAGMA index_list({table})")
        )
        return columns, keys, indexes

    migrated = sqlite3.connect(str(tmp_path / "migrated.db"))
    migrated.row_factory = sqlite3.Row
    migrated.execute("CREATE TABLE accounts (id TEXT PRIMARY KEY)")
    migrated.execute("CREATE TABLE lists (id TEXT PRIMARY KEY)")
    for statement in dict(migrations_module.MIGRATIONS)[7]:
        migrated.execute(statement)

    # schema.sql and the migration must land on the same shape — an upgraded database and a fresh
    # one are the same database as far as every query in the server is concerned.
    assert shape_of(migrated, "close_votes") == shape_of(db_conn, "close_votes")
    migrated.close()
