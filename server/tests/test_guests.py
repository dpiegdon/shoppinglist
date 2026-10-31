"""A ledger's guests (T-364): the endpoints, the list object, creation-time names, the
participant rule and the freeze.

Design of record: T-364; wire side: "Guests" in docs/wire-contract.md.
"""

import sqlite3

import pytest

from shoppinglist_server import accounts, auth, closing
from shoppinglist_server import db as db_module
from shoppinglist_server import get_config_by_name, guests
from shoppinglist_server import migrations as migrations_module
from shoppinglist_server import sync
from shoppinglist_server.errors import ApiError

PW = "password123"
API = "/api/v1"


def _clock(value, ts=100, by="devA"):
    return {"value": value, "updated_at": ts, "updated_by": by}


def _expense(paid_by, paid_for, entry_type="expense"):
    return {
        "type": entry_type,
        "paid_by": paid_by,
        "equal_by": False,
        "paid_for": paid_for,
        "equal_for": False,
        "date": "2026-10-31",
    }


def _ledger_row(list_id="trip", guest_names=..., kind="expenses", ts=100):
    fields = {"name": _clock("Trip", ts), "kind": _clock(kind, ts)}
    if kind == "expenses":
        fields["currency"] = _clock("EUR", ts)
    row = {"id": list_id, "created_at": 1000, "fields": fields}
    if guest_names is not ...:
        row["guest_names"] = guest_names
    return row


def _entry(item_id, expense, ts=100, list_id="trip", deleted=None):
    fields = {"name": _clock("Dinner", ts), "expense": _clock(expense, ts)}
    if deleted is not None:
        fields["deleted"] = _clock(deleted, ts)
    return {"id": item_id, "list_id": list_id, "created_at": 1000, "fields": fields}


# ---- over HTTP ------------------------------------------------------------------------------


class Person:
    def __init__(self, client, email):
        self.client = client
        self.email = email
        client.post(f"{API}/register", json={"email": email, "password": PW})
        body = client.post(
            f"{API}/login", json={"email": email, "password": PW, "device_label": "d"}
        ).get_json()
        self.id = body["account_id"]
        self.headers = {"Authorization": f"Bearer {body['token']}"}

    def sync(self, lists=(), items=(), cursor=0):
        return self.client.post(
            f"{API}/sync",
            headers=self.headers,
            json={
                "cursor": cursor,
                "device_id": "devA",
                "changes": {"lists": list(lists), "items": list(items)},
            },
        )

    def call(self, method, path, json=None):
        return self.client.open(f"{API}{path}", method=method, headers=self.headers, json=json)

    def list_object(self, list_id="trip", cursor=0):
        rows = self.sync(cursor=cursor).get_json()["changes"]["lists"]
        matching = [row for row in rows if row["id"] == list_id]
        return matching[0] if matching else None

    def cursor(self):
        return self.sync().get_json()["cursor"]

    def add_guest(self, name, list_id="trip"):
        resp = self.call("POST", f"/lists/{list_id}/guests", {"name": name})
        assert resp.status_code == 201, resp.get_json()
        return resp.get_json()


def _join(owner, joiner, list_id="trip"):
    token = owner.call("POST", f"/lists/{list_id}/invites", {"invited_email": joiner.email})
    resp = joiner.call("POST", "/invites/redeem", {"token": token.get_json()["token"]})
    assert resp.status_code == 200, resp.get_json()


@pytest.fixture
def alice(client):
    person = Person(client, "alice@example.com")
    assert person.sync(lists=[_ledger_row()]).status_code == 200
    return person


@pytest.fixture
def bob(client, alice):
    person = Person(client, "bob@example.com")
    _join(alice, person)
    return person


def _five_calls(gid):
    return [
        ("POST", "/lists/{list_id}/guests", {"name": "Eve"}),
        ("PATCH", f"/lists/{{list_id}}/guests/{gid}", {"name": "Eve"}),
        ("DELETE", f"/lists/{{list_id}}/guests/{gid}", None),
        ("POST", f"/lists/{{list_id}}/guests/{gid}/claim", None),
        ("DELETE", f"/lists/{{list_id}}/guests/{gid}/claim", None),
    ]


def _assert_error(resp, status, code, **details):
    body = resp.get_json()
    assert (resp.status_code, body["error"]) == (status, code), body
    for key, value in details.items():
        assert body.get(key) == value, (key, body)
    return body


# ---- the list object ------------------------------------------------------------------------


def test_guests_are_served_on_the_list_object_oldest_first(client):
    alice = Person(client, "alice@example.com")
    resp = alice.sync(lists=[_ledger_row(guest_names=["Dave", "eve"])])
    assert resp.status_code == 200, resp.get_json()
    later = alice.add_guest("Ann")

    served = alice.list_object()["guests"]

    assert [g["name"] for g in served] == ["Dave", "eve", "Ann"]
    assert served[-1] == later
    for guest in served:
        assert set(guest) == {"id", "name", "initials", "locked", "claimed_by", "created_at"}
        assert guest["id"].startswith("g:")
        assert guest["locked"] is False
        assert guest["claimed_by"] is None
    assert [g["initials"] for g in served] == ["DA", "EV", "AN"]
    assert served[0]["created_at"] < served[1]["created_at"] < served[2]["created_at"]


def test_guests_with_the_same_created_at_are_served_by_id(db_conn):
    owner = auth.register(db_conn, "o@example.com", PW)
    sync.apply_changes(db_conn, owner, "d", {"lists": [_ledger_row()]})
    for guest_id in ("g:b", "g:a", "g:c"):
        db_conn.execute(
            "INSERT INTO guests (id, list_id, name, created_at, created_by) "
            "VALUES (?, 'trip', 'X', 5, ?)",
            (guest_id, owner),
        )

    assert [g["id"] for g in guests.for_lists(db_conn, ["trip"])["trip"]] == [
        "g:a",
        "g:b",
        "g:c",
    ]


def test_other_kinds_serve_no_guests_and_ignore_guest_names(client):
    alice = Person(client, "alice@example.com")
    resp = alice.sync(lists=[_ledger_row(list_id="shop", kind="shopping", guest_names=["Dave"])])
    assert resp.status_code == 200, resp.get_json()

    assert alice.list_object("shop")["guests"] == []
    # Ignored means not even validated: a shopping list never had guests to name.
    resp = alice.sync(lists=[_ledger_row(list_id="shop2", kind="checklist", guest_names=5)])
    assert resp.status_code == 200, resp.get_json()


def test_guest_names_are_ignored_on_a_later_push_of_the_list(client):
    alice = Person(client, "alice@example.com")
    alice.sync(lists=[_ledger_row(guest_names=["Dave"])])

    resp = alice.sync(lists=[_ledger_row(guest_names=["Eve", ""], ts=200)])
    assert resp.status_code == 200, resp.get_json()

    assert [g["name"] for g in alice.list_object()["guests"]] == ["Dave"]


def test_a_pushed_guests_key_is_ignored(client):
    alice = Person(client, "alice@example.com")
    fake = [{"id": "g:fake", "name": "Mallory", "locked": False, "claimed_by": None}]
    row = _ledger_row()
    row["guests"] = fake
    assert alice.sync(lists=[row]).status_code == 200
    row = _ledger_row(ts=200)
    row["guests"] = fake
    assert alice.sync(lists=[row]).status_code == 200

    assert alice.list_object()["guests"] == []


def test_guest_names_are_trimmed_like_the_endpoint_trims_them(client):
    alice = Person(client, "alice@example.com")
    alice.sync(lists=[_ledger_row(guest_names=["  Dave ", "Eve, Frank"])])

    # No comma splitting: a name may contain a comma.
    assert [g["name"] for g in alice.list_object()["guests"]] == ["Dave", "Eve, Frank"]


def test_fifty_guest_names_are_accepted(client):
    alice = Person(client, "alice@example.com")
    names = [f"Guest {n}" for n in range(50)]
    assert alice.sync(lists=[_ledger_row(guest_names=names)]).status_code == 200

    assert [g["name"] for g in alice.list_object()["guests"]] == names


@pytest.mark.parametrize(
    "guest_names",
    [
        [f"Guest {n}" for n in range(51)],
        "Dave, Eve",
        None,
        {"0": "Dave"},
        ["Dave", 5],
        ["Dave", ""],
        ["   "],
        ["x" * 65],
        ["Da\nve"],
        ["‮evaD"],
        ["​"],
    ],
    ids=[
        "51 names",
        "a string",
        "null",
        "an object",
        "a non-string",
        "an empty name",
        "a blank name",
        "65 characters",
        "a line feed",
        "a bidi override",
        "only a format character",
    ],
)
def test_bad_guest_names_refuse_the_creation_row_and_create_nothing(client, guest_names):
    alice = Person(client, "alice@example.com")

    resp = alice.sync(lists=[_ledger_row(guest_names=guest_names)])

    _assert_error(resp, 422, "invalid_guest_name", row_id="trip", field="guest_names")
    assert alice.list_object() is None
    conn = db_module.connect(get_config_by_name(client.application)["database_path"])
    assert conn.execute("SELECT COUNT(*) FROM lists").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM guests").fetchone()[0] == 0
    conn.close()


# ---- adding, and names ----------------------------------------------------------------------


def test_adding_a_guest_answers_the_guest(alice):
    resp = alice.call("POST", "/lists/trip/guests", {"name": " Dave "})

    assert resp.status_code == 201
    body = resp.get_json()
    assert body["name"] == "Dave"
    assert body["initials"] == "DA"
    assert body["id"].startswith("g:")
    assert alice.list_object()["guests"] == [body]


def test_a_name_of_64_characters_is_accepted(alice):
    assert alice.add_guest("x" * 64)["name"] == "x" * 64


def test_two_guests_may_share_a_name(alice):
    first = alice.add_guest("Dave")
    second = alice.add_guest("Dave")
    assert first["id"] != second["id"]


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"name": None},
        {"name": 5},
        {"name": ""},
        {"name": " \t　"},
        {"name": "x" * 65},
        {"name": "Da\rve"},
        {"name": "Da ve"},
        {"name": "‪Dave"},
        {"name": "‍"},
    ],
)
def test_a_bad_name_is_refused(alice, body):
    _assert_error(
        alice.call("POST", "/lists/trip/guests", body), 422, "invalid_guest_name", field="name"
    )
    assert alice.list_object()["guests"] == []


def test_renaming_refuses_a_bad_name_too(alice):
    gid = alice.add_guest("Dave")["id"]
    _assert_error(
        alice.call("PATCH", f"/lists/trip/guests/{gid}", {"name": "  "}),
        422,
        "invalid_guest_name",
        field="name",
    )


# ---- every change moves the list -------------------------------------------------------------


def _moves_the_list(person, action):
    cursor = person.cursor()
    resp = action()
    assert resp.status_code in (200, 201, 204), resp.get_json()
    after = person.list_object(cursor=cursor)
    assert after is not None, "the change did not bump the list's change_seq"
    return after


def test_every_guest_change_reaches_other_devices_through_sync(alice, bob):
    after = _moves_the_list(alice, lambda: alice.call("POST", "/lists/trip/guests", {"name": "D"}))
    gid = after["guests"][0]["id"]

    after = _moves_the_list(
        bob, lambda: alice.call("PATCH", f"/lists/trip/guests/{gid}", {"name": "Dave"})
    )
    assert after["guests"][0]["name"] == "Dave"
    after = _moves_the_list(
        bob, lambda: alice.call("PATCH", f"/lists/trip/guests/{gid}", {"locked": True})
    )
    assert after["guests"][0]["locked"] is True
    after = _moves_the_list(
        bob, lambda: alice.call("PATCH", f"/lists/trip/guests/{gid}", {"locked": False})
    )
    assert after["guests"][0]["locked"] is False
    after = _moves_the_list(bob, lambda: alice.call("POST", f"/lists/trip/guests/{gid}/claim"))
    assert after["guests"][0]["claimed_by"] == alice.id
    after = _moves_the_list(bob, lambda: alice.call("DELETE", f"/lists/trip/guests/{gid}/claim"))
    assert after["guests"][0]["claimed_by"] is None
    after = _moves_the_list(bob, lambda: alice.call("DELETE", f"/lists/trip/guests/{gid}"))
    assert after["guests"] == []


# ---- the error table, row by row ------------------------------------------------------------


def test_a_non_member_gets_the_same_403_whether_or_not_the_list_exists(client, alice):
    gid = alice.add_guest("Dave")["id"]
    stranger = Person(client, "stranger@example.com")
    for list_id in ("trip", "no-such-list"):
        for method, path, body in _five_calls(gid):
            resp = stranger.call(method, path.format(list_id=list_id), body)
            _assert_error(resp, 403, "not_a_member")


def test_another_kind_of_list_has_no_guests(alice):
    alice.sync(lists=[_ledger_row(list_id="shop", kind="shopping")])
    for method, path, body in _five_calls("g:any"):
        _assert_error(
            alice.call(method, path.format(list_id="shop"), body), 409, "not_an_expenses_list"
        )


def test_a_closed_list_is_read_only_for_guests(alice):
    gid = alice.add_guest("Dave")["id"]
    alice.call("POST", "/lists/trip/close-votes")  # a list of one closes on one vote
    # The unknown guest id is not looked at: list_closed comes first.
    for guest_id in (gid, "g:unknown"):
        for method, path, body in _five_calls(guest_id):
            _assert_error(alice.call(method, path.format(list_id="trip"), body), 409, "list_closed")


def test_a_voter_changes_no_guest(alice, bob):
    gid = alice.add_guest("Dave")["id"]
    alice.call("POST", "/lists/trip/close-votes")
    for guest_id in (gid, "g:unknown"):
        for method, path, body in _five_calls(guest_id):
            resp = alice.call(method, path.format(list_id="trip"), body)
            _assert_error(resp, 422, "voted_to_close", row_id="trip")
    # A bad name from a voter is still the vote's answer.
    resp = alice.call("POST", "/lists/trip/guests", {"name": ""})
    _assert_error(resp, 422, "voted_to_close", row_id="trip")
    # Someone else's vote stops nobody else.
    assert bob.call("PATCH", f"/lists/trip/guests/{gid}", {"locked": True}).status_code == 200


def test_an_unknown_guest_is_404(alice):
    for method, path, body in _five_calls("g:unknown")[1:]:
        _assert_error(alice.call(method, path.format(list_id="trip"), body), 404, "guest_not_found")


def test_a_guest_of_another_list_is_404_here(alice):
    alice.sync(lists=[_ledger_row(list_id="other", guest_names=["Zed"])])
    other_gid = alice.list_object("other")["guests"][0]["id"]
    for method, path, body in _five_calls(other_gid)[1:]:
        _assert_error(alice.call(method, path.format(list_id="trip"), body), 404, "guest_not_found")
    assert alice.list_object("other")["guests"][0]["name"] == "Zed"


def test_404_comes_before_a_bad_body(alice):
    resp = alice.call("PATCH", "/lists/trip/guests/g:unknown", {"name": ""})
    _assert_error(resp, 404, "guest_not_found")
    resp = alice.call("PATCH", "/lists/trip/guests/g:unknown", {})
    _assert_error(resp, 404, "guest_not_found")


@pytest.mark.parametrize("body", [{}, {"locked": "yes"}, {"locked": 1}, {"locked": None}])
def test_a_patch_with_nothing_to_do_or_a_non_boolean_lock_is_invalid_request(alice, body):
    gid = alice.add_guest("Dave")["id"]
    _assert_error(alice.call("PATCH", f"/lists/trip/guests/{gid}", body), 422, "invalid_request")


def test_patch_renames_and_locks_together(alice):
    gid = alice.add_guest("Dave")["id"]
    resp = alice.call("PATCH", f"/lists/trip/guests/{gid}", {"name": "David", "locked": True})
    assert resp.status_code == 200
    assert resp.get_json()["name"] == "David"
    assert resp.get_json()["locked"] is True
    assert resp.get_json()["initials"] == "DA"


def test_a_claimed_guest_can_be_renamed_but_not_locked_or_unlocked(alice, bob):
    gid = alice.add_guest("Dave")["id"]
    assert bob.call("POST", f"/lists/trip/guests/{gid}/claim").status_code == 200

    resp = alice.call("PATCH", f"/lists/trip/guests/{gid}", {"name": "Dave B."})
    assert resp.status_code == 200
    assert resp.get_json()["claimed_by"] == bob.id
    for locked in (True, False):
        resp = alice.call("PATCH", f"/lists/trip/guests/{gid}", {"locked": locked})
        _assert_error(resp, 409, "guest_claimed")
    # A rename riding along with a refused lock is not applied either.
    resp = alice.call("PATCH", f"/lists/trip/guests/{gid}", {"name": "X", "locked": True})
    _assert_error(resp, 409, "guest_claimed")
    assert alice.list_object()["guests"][0]["name"] == "Dave B."


def test_a_bad_name_is_reported_before_guest_claimed(alice):
    gid = alice.add_guest("Dave")["id"]
    alice.call("POST", f"/lists/trip/guests/{gid}/claim")
    resp = alice.call("PATCH", f"/lists/trip/guests/{gid}", {"name": "", "locked": True})
    _assert_error(resp, 422, "invalid_guest_name", field="name")


# ---- claiming -------------------------------------------------------------------------------


def test_claiming_and_unclaiming(alice):
    gid = alice.add_guest("Dave")["id"]

    resp = alice.call("POST", f"/lists/trip/guests/{gid}/claim")
    assert resp.status_code == 200
    assert resp.get_json()["claimed_by"] == alice.id

    resp = alice.call("DELETE", f"/lists/trip/guests/{gid}/claim")
    assert resp.status_code == 200
    assert resp.get_json()["claimed_by"] is None


def test_a_claimed_guest_cannot_be_claimed_again_by_anyone(alice, bob):
    gid = alice.add_guest("Dave")["id"]
    alice.call("POST", f"/lists/trip/guests/{gid}/claim")
    for person in (bob, alice):
        _assert_error(person.call("POST", f"/lists/trip/guests/{gid}/claim"), 409, "guest_claimed")
    assert alice.list_object()["guests"][0]["claimed_by"] == alice.id


def test_one_account_may_claim_several_guests(alice):
    first = alice.add_guest("Dave")["id"]
    second = alice.add_guest("Dave")["id"]
    assert alice.call("POST", f"/lists/trip/guests/{first}/claim").status_code == 200
    assert alice.call("POST", f"/lists/trip/guests/{second}/claim").status_code == 200


def test_only_the_claimant_can_unclaim(alice, bob):
    gid = alice.add_guest("Dave")["id"]
    # Nobody is the claimant of an unclaimed guest.
    _assert_error(alice.call("DELETE", f"/lists/trip/guests/{gid}/claim"), 409, "guest_claimed")
    alice.call("POST", f"/lists/trip/guests/{gid}/claim")
    _assert_error(bob.call("DELETE", f"/lists/trip/guests/{gid}/claim"), 409, "guest_claimed")
    assert alice.list_object()["guests"][0]["claimed_by"] == alice.id


def test_a_locked_guest_cannot_be_claimed(alice):
    gid = alice.add_guest("Dave")["id"]
    alice.call("PATCH", f"/lists/trip/guests/{gid}", {"locked": True})

    resp = alice.call("POST", f"/lists/trip/guests/{gid}/claim")

    _assert_error(resp, 422, "participant_frozen", account_id=gid)
    assert alice.list_object()["guests"][0]["claimed_by"] is None


def test_guest_claimed_comes_before_participant_frozen(client, alice):
    gid = alice.add_guest("Dave")["id"]
    alice.call("POST", f"/lists/trip/guests/{gid}/claim")
    # Not reachable through the API (a claimed guest cannot be locked), so set it by hand.
    conn = db_module.connect(get_config_by_name(client.application)["database_path"])
    conn.execute("UPDATE guests SET locked = 1 WHERE id = ?", (gid,))
    conn.commit()
    conn.close()

    _assert_error(alice.call("POST", f"/lists/trip/guests/{gid}/claim"), 409, "guest_claimed")


def test_a_claim_survives_the_claimant_deleting_their_account(client, alice, bob):
    gid = alice.add_guest("Dave")["id"]
    bob.call("POST", f"/lists/trip/guests/{gid}/claim")

    assert bob.call("DELETE", "/account", {"password": PW}).status_code == 204

    assert alice.list_object()["guests"][0]["claimed_by"] == bob.id


# ---- removing -------------------------------------------------------------------------------


@pytest.mark.parametrize("side", ["paid_by", "paid_for"])
def test_a_guest_named_in_a_live_entry_cannot_be_removed_until_it_is_tombstoned(alice, side):
    gid = alice.add_guest("Dave")["id"]
    if side == "paid_by":
        expense = _expense({gid: "10"}, {alice.id: "10"})
    else:
        expense = _expense({alice.id: "10"}, {gid: "10"})
    assert alice.sync(items=[_entry("e1", expense)]).status_code == 200

    _assert_error(alice.call("DELETE", f"/lists/trip/guests/{gid}"), 409, "guest_in_use")
    assert len(alice.list_object()["guests"]) == 1

    assert alice.sync(items=[_entry("e1", expense, ts=200, deleted=True)]).status_code == 200
    assert alice.call("DELETE", f"/lists/trip/guests/{gid}").status_code == 204
    assert alice.list_object()["guests"] == []


def test_an_unused_claimed_guest_can_be_removed(alice):
    gid = alice.add_guest("Dave")["id"]
    alice.call("POST", f"/lists/trip/guests/{gid}/claim")
    assert alice.call("DELETE", f"/lists/trip/guests/{gid}").status_code == 204


# ---- the participant rule and the freeze, on the sync engine ---------------------------------


@pytest.fixture
def trip(db_conn):
    """A ledger with alice and bob, and two guests: Dave and Eve."""
    alice = auth.register(db_conn, "alice@example.com", PW)
    bob = auth.register(db_conn, "bob@example.com", PW)
    sync.apply_changes(db_conn, alice, "devA", {"lists": [_ledger_row()]})
    db_conn.execute(
        "INSERT INTO memberships (account_id, list_id, joined_at) VALUES (?, 'trip', 0)", (bob,)
    )
    dave = guests.add(db_conn, alice, "trip", "Dave")["id"]
    eve = guests.add(db_conn, alice, "trip", "Eve")["id"]
    return {"alice": alice, "bob": bob, "dave": dave, "eve": eve}


def _apply(conn, account_id, *entries):
    sync.apply_changes(conn, account_id, "devA", {"items": list(entries)})


def _refused(conn, account_id, code, *entries):
    with pytest.raises(ApiError) as excinfo:
        _apply(conn, account_id, *entries)
    assert excinfo.value.code == code, (excinfo.value.code, excinfo.value.message)
    return excinfo.value.details


def test_an_entry_may_name_a_guest_on_either_side(db_conn, trip):
    a, dave, eve = trip["alice"], trip["dave"], trip["eve"]
    _apply(db_conn, a, _entry("e1", _expense({dave: "30"}, {a: "10", dave: "10", eve: "10"})))
    _apply(db_conn, a, _entry("e2", _expense({eve: "5"}, {dave: "5"}, "transfer")))
    _apply(db_conn, a, _entry("e3", _expense({a: "5"}, {eve: "5"}, "income")))


def test_an_entry_may_name_a_claimed_guest(db_conn, trip):
    """Written offline before the claim landed, it must not be quarantined for it."""
    guests.claim(db_conn, trip["bob"], "trip", trip["dave"])
    _apply(db_conn, trip["alice"], _entry("e1", _expense({trip["dave"]: "8"}, {trip["bob"]: "8"})))


def test_an_unknown_guest_id_is_not_a_participant(db_conn, trip):
    details = _refused(
        db_conn,
        trip["alice"],
        "invalid_expense",
        _entry("e1", _expense({"g:nobody": "8"}, {trip["alice"]: "8"})),
    )
    assert details["account_id"] == "g:nobody"
    assert details["row_id"] == "e1"


def test_a_guest_of_another_list_is_not_a_participant_here(db_conn, trip):
    sync.apply_changes(db_conn, trip["alice"], "devA", {"lists": [_ledger_row("other")]})
    zed = guests.add(db_conn, trip["alice"], "other", "Zed")["id"]
    details = _refused(
        db_conn,
        trip["alice"],
        "invalid_expense",
        _entry("e1", _expense({zed: "8"}, {trip["alice"]: "8"})),
    )
    assert details["account_id"] == zed


def test_a_removed_guest_stays_editable_where_it_was_already_named(db_conn, trip):
    a, dave = trip["alice"], trip["dave"]
    _apply(db_conn, a, _entry("e1", _expense({dave: "8"}, {a: "8"})))
    _apply(db_conn, a, _entry("e1", _expense({dave: "8"}, {a: "8"}), ts=200, deleted=True))
    guests.remove(db_conn, a, "trip", dave)

    # A new entry cannot name it ...
    _refused(db_conn, a, "invalid_expense", _entry("e2", _expense({dave: "8"}, {a: "8"})))
    # ... and the old one, restored, is admitted by the participant rule — and then refused by the
    # freeze, since a removed guest's amounts are frozen like a departed member's.
    restore = _entry("e1", _expense({dave: "8"}, {a: "8"}), ts=300, deleted=False)
    details = _refused(db_conn, a, "participant_frozen", restore)
    assert details["account_id"] == dave
    # An edit that does not move the removed guest's amount is still accepted.
    _apply(db_conn, a, _entry("e1", _expense({dave: "8"}, {a: "8"}), ts=400))


def test_a_locked_guests_amounts_are_frozen(db_conn, trip):
    a, b, dave = trip["alice"], trip["bob"], trip["dave"]
    _apply(db_conn, a, _entry("e1", _expense({dave: "10"}, {a: "5", b: "5"})))
    guests.update(db_conn, a, "trip", dave, {"locked": True})

    details = _refused(
        db_conn,
        b,
        "participant_frozen",
        _entry("e1", _expense({dave: "12"}, {a: "6", b: "6"}), 200),
    )
    assert details == {"row_id": "e1", "field": "expense", "account_id": dave}
    _refused(db_conn, b, "participant_frozen", _entry("e2", _expense({dave: "1"}, {a: "1"})))
    _refused(
        db_conn,
        b,
        "participant_frozen",
        _entry("e1", _expense({dave: "10"}, {a: "5", b: "5"}), 200, deleted=True),
    )
    # Moving only the others is fine.
    _apply(db_conn, b, _entry("e1", _expense({dave: "10"}, {a: "4", b: "6"}), 300))
    # And a stale write that would move the guest is discarded, not refused.
    _apply(db_conn, b, _entry("e1", _expense({dave: "99"}, {a: "99"}), 50))

    guests.update(db_conn, a, "trip", dave, {"locked": False})
    _apply(db_conn, b, _entry("e1", _expense({dave: "12"}, {a: "6", b: "6"}), 400))


def test_an_unlocked_unclaimed_guest_is_not_frozen_even_while_members_vote(db_conn, trip):
    a, b, dave, eve = trip["alice"], trip["bob"], trip["dave"], trip["eve"]
    closing.cast_vote(db_conn, a, "trip")
    _apply(db_conn, b, _entry("e1", _expense({dave: "10"}, {eve: "5", b: "5"})))


def test_a_claimed_guest_is_frozen_while_its_claimant_has_voted(db_conn, trip):
    a, b, dave = trip["alice"], trip["bob"], trip["dave"]
    _apply(db_conn, a, _entry("e1", _expense({dave: "10"}, {a: "10"})))
    guests.claim(db_conn, b, "trip", dave)
    closing.cast_vote(db_conn, b, "trip")

    details = _refused(
        db_conn, a, "participant_frozen", _entry("e1", _expense({dave: "11"}, {a: "11"}), 200)
    )
    assert details["account_id"] == dave
    # Per stored key, not per folded id: moving Dave's amount onto Bob's own key is refused too,
    # although Bob's folded balance would not move.
    _refused(db_conn, a, "participant_frozen", _entry("e1", _expense({b: "10"}, {a: "10"}), 200))

    closing.withdraw_vote(db_conn, b, "trip")
    _apply(db_conn, a, _entry("e1", _expense({dave: "11"}, {a: "11"}), 300))


def test_a_claimed_guest_follows_its_claimant_not_its_own_lock(db_conn, trip):
    a, b, dave = trip["alice"], trip["bob"], trip["dave"]
    _apply(db_conn, a, _entry("e1", _expense({dave: "10"}, {a: "10"})))
    guests.claim(db_conn, b, "trip", dave)
    # A stale lock flag on a claimed guest does not freeze it: the claimant's status governs.
    db_conn.execute("UPDATE guests SET locked = 1 WHERE id = ?", (dave,))
    _apply(db_conn, a, _entry("e1", _expense({dave: "11"}, {a: "11"}), 200))


def test_a_claimed_guest_is_frozen_once_its_claimant_has_left(db_conn, trip):
    a, b, dave = trip["alice"], trip["bob"], trip["dave"]
    _apply(db_conn, a, _entry("e1", _expense({dave: "10"}, {a: "10"})))
    guests.claim(db_conn, b, "trip", dave)
    accounts.delete_account(db_conn, b, PW)

    details = _refused(
        db_conn, a, "participant_frozen", _entry("e1", _expense({dave: "11"}, {a: "11"}), 200)
    )
    assert details["account_id"] == dave


def test_is_frozen_for_each_kind_of_guest(db_conn, trip):
    a, b, dave, eve = trip["alice"], trip["bob"], trip["dave"], trip["eve"]

    assert not closing.is_frozen(db_conn, "trip", dave)
    guests.update(db_conn, a, "trip", dave, {"locked": True})
    assert closing.is_frozen(db_conn, "trip", dave)
    assert closing.is_frozen(db_conn, "trip", "g:removed")

    guests.claim(db_conn, b, "trip", eve)
    assert not closing.is_frozen(db_conn, "trip", eve)
    closing.cast_vote(db_conn, b, "trip")
    assert closing.is_frozen(db_conn, "trip", eve)


def test_guests_never_vote_and_never_block_closing(db_conn, trip):
    closing.cast_vote(db_conn, trip["alice"], "trip")
    closing.cast_vote(db_conn, trip["bob"], "trip")
    assert closing.is_closed(db_conn, "trip")


# ---- the schema -----------------------------------------------------------------------------


def _shape_of(conn, table):
    columns = [
        (r["name"], r["type"].upper(), r["notnull"], r["dflt_value"], r["pk"])
        for r in conn.execute(f"PRAGMA table_info({table})")
    ]
    keys = sorted(
        (r["table"], r["from"], r["to"]) for r in conn.execute(f"PRAGMA foreign_key_list({table})")
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


def test_fresh_schema_and_migration_13_build_the_same_guests_table(db_conn, tmp_path):
    migrated = sqlite3.connect(str(tmp_path / "migrated.db"))
    migrated.row_factory = sqlite3.Row
    migrated.execute("CREATE TABLE lists (id TEXT PRIMARY KEY)")
    for step in dict(migrations_module.MIGRATIONS)[13]:
        migrations_module.run_step(migrated, step)

    assert _shape_of(migrated, "guests") == _shape_of(db_conn, "guests")
    # Only the list is a reference: claimed_by and created_by are history and outlive accounts.
    assert _shape_of(db_conn, "guests")[1] == [("lists", "list_id", "id")]
    migrated.close()


def test_a_guest_cannot_point_at_a_missing_list(db_conn):
    with pytest.raises(sqlite3.IntegrityError):
        db_conn.execute(
            "INSERT INTO guests (id, list_id, name, created_at, created_by) "
            "VALUES ('g:x', 'nope', 'X', 1, 'a')"
        )
