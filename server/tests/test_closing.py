"""Closing an expenses list, and the freeze that protects a voter (T-157).

Design: docs/archive/specs/2026-09-17-expense-lists-design.md.
"""

import pytest

from shoppinglist_server import accounts, auth, closing, invites, sync
from shoppinglist_server.errors import ApiError

PW = "password123"
KEY = b"test-hmac-key"


def _clock(value, ts, by="devA"):
    return {"value": value, "updated_at": ts, "updated_by": by}


def _register(conn, email):
    return auth.register(conn, email, PW)


def _expense(paid_by, paid_for, date="2026-09-17"):
    return {
        "paid_by": paid_by,
        "equal_by": False,
        "paid_for": paid_for,
        "equal_for": False,
        "date": date,
    }


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
    assert row["fields"]["expense"]["value"] == newer


def test_an_unrelated_field_of_a_frozen_expense_can_still_be_edited(db_conn, trip):
    """The freeze is about money. Fixing a typo in the title moves nobody's balance."""
    b = trip["bob"]
    closing.cast_vote(db_conn, b, "trip")

    _apply(db_conn, trip["alice"], items=[_item("e1", ..., name="Dinner at Luigi's", ts=300)])


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
