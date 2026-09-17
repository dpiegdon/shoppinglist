"""The member roster and vote state on the synced list object (T-152).

Membership, email and initials are visible in `members`, so every change to them has to move the
list's change_seq — otherwise a device keeps showing a stale roster until some unrelated edit
happens to travel. `close_votes` / `closed_at` are placeholders until T-157.
"""

import pytest

from shoppinglist_server import accounts, auth, invites, sync

PW = "password123"
KEY = b"test-hmac-key"
BASE_URL = "http://testserver"


def _register(conn, email):
    return auth.register(conn, email, PW)


def _clock(value, ts, by="devA"):
    return {"value": value, "updated_at": ts, "updated_by": by}


def _create_list(conn, account_id, list_id="list-1", name="Groceries"):
    sync.apply_changes(
        conn,
        account_id,
        "devA",
        {"lists": [{"id": list_id, "fields": {"name": _clock(name, 100)}}]},
    )


def _invite_and_redeem(conn, owner, invitee_id, invitee_email, list_id="list-1"):
    minted = invites.mint(conn, KEY, BASE_URL, list_id, invitee_email, owner)
    account = auth.Account(id=invitee_id, email=invitee_email)
    return invites.redeem(conn, KEY, account, minted["token"])


def _lists_since(conn, account_id, cursor):
    return {row["id"]: row for row in sync.delta(conn, account_id, cursor)["changes"]["lists"]}


def _emails(list_wire):
    return [member["email"] for member in list_wire["members"]]


@pytest.fixture
def shared(db_conn):
    """A list owned by alice, shared with bob, and alice's cursor after that happened."""
    alice = _register(db_conn, "alice@example.com")
    bob = _register(db_conn, "bob@example.com")
    _create_list(db_conn, alice)
    _invite_and_redeem(db_conn, alice, bob, "bob@example.com")
    cursor = sync.delta(db_conn, alice, 0)["cursor"]
    return {"alice": alice, "bob": bob, "cursor": cursor}


# ---- what the list object carries -------------------------------------------


def test_a_fresh_list_carries_its_creator_as_the_only_member(db_conn):
    alice = _register(db_conn, "alice@example.com")
    _create_list(db_conn, alice)

    wire = _lists_since(db_conn, alice, 0)["list-1"]
    assert wire["members"] == [
        {"account_id": alice, "email": "alice@example.com", "initials": "AL"}
    ]
    # Nothing can be voted on or closed until T-157.
    assert wire["close_votes"] == []
    assert wire["closed_at"] is None


def test_the_roster_is_oldest_membership_first_then_alphabetical(db_conn, shared):
    wire = _lists_since(db_conn, shared["alice"], 0)["list-1"]

    assert _emails(wire) == ["alice@example.com", "bob@example.com"]


def test_a_full_list_snapshot_carries_the_roster_too(db_conn, shared):
    """The join path asks for full_lists rather than a delta, and needs the roster just as much."""
    snapshot = sync.delta(db_conn, shared["bob"], 0, full_lists=["list-1"])

    assert _emails(snapshot["changes"]["lists"][0]) == ["alice@example.com", "bob@example.com"]


def test_a_member_never_sees_a_list_they_are_not_on(db_conn, shared):
    carol = _register(db_conn, "carol@example.com")

    assert _lists_since(db_conn, carol, 0) == {}


# ---- every roster change moves the list --------------------------------------


def test_joining_moves_the_list_for_the_members_already_on_it(db_conn):
    alice = _register(db_conn, "alice@example.com")
    bob = _register(db_conn, "bob@example.com")
    _create_list(db_conn, alice)
    cursor = sync.delta(db_conn, alice, 0)["cursor"]

    _invite_and_redeem(db_conn, alice, bob, "bob@example.com")

    # Alice changed nothing, yet her next incremental sync has to bring bob.
    wire = _lists_since(db_conn, alice, cursor)["list-1"]
    assert _emails(wire) == ["alice@example.com", "bob@example.com"]


def test_leaving_moves_the_list_for_everyone_who_stays(db_conn, shared):
    invites.leave(db_conn, shared["bob"], "list-1")

    wire = _lists_since(db_conn, shared["alice"], shared["cursor"])["list-1"]
    assert _emails(wire) == ["alice@example.com"]


def test_deleting_an_account_moves_every_list_it_was_on(db_conn, shared):
    accounts.delete_account(db_conn, shared["bob"], PW)

    wire = _lists_since(db_conn, shared["alice"], shared["cursor"])["list-1"]
    assert _emails(wire) == ["alice@example.com"]


def test_changing_an_email_moves_every_list_the_account_is_on(db_conn, shared):
    accounts.change_email(db_conn, shared["bob"], PW, "robert@example.com")

    wire = _lists_since(db_conn, shared["alice"], shared["cursor"])["list-1"]
    assert _emails(wire) == ["alice@example.com", "robert@example.com"]


def test_changing_initials_moves_every_list_the_account_is_on(db_conn, shared):
    accounts.update_settings(db_conn, shared["bob"], initials="BOB")

    wire = _lists_since(db_conn, shared["alice"], shared["cursor"])["list-1"]
    assert [member["initials"] for member in wire["members"]] == ["AL", "BOB"]


def test_clearing_initials_falls_back_to_the_derived_default(db_conn, shared):
    accounts.update_settings(db_conn, shared["bob"], initials="BOB")
    cursor = sync.delta(db_conn, shared["alice"], 0)["cursor"]
    accounts.update_settings(db_conn, shared["bob"], initials=None)

    wire = _lists_since(db_conn, shared["alice"], cursor)["list-1"]
    assert [member["initials"] for member in wire["members"]] == ["AL", "BO"]


def test_a_private_setting_change_moves_nothing(db_conn, shared):
    """default_currency is the account's own; it is not in anyone's roster."""
    accounts.update_settings(db_conn, shared["bob"], default_currency="CHF")

    assert _lists_since(db_conn, shared["alice"], shared["cursor"]) == {}


def test_an_orphaning_leave_does_not_double_bump(db_conn):
    """The tombstone write already moves the list; the roster bump must not fire as well."""
    alice = _register(db_conn, "alice@example.com")
    _create_list(db_conn, alice)
    invites.leave(db_conn, alice, "list-1")

    wire = _lists_since(db_conn, alice, 0)["list-1"]
    assert wire["fields"]["deleted"]["value"] is True


# ---- clients cannot write any of it ------------------------------------------


def test_a_client_cannot_write_the_roster_or_the_vote_state(db_conn, shared):
    sync.apply_changes(
        db_conn,
        shared["alice"],
        "devA",
        {
            "lists": [
                {
                    "id": "list-1",
                    "members": [{"account_id": "smuggled", "email": "evil@example.com"}],
                    "close_votes": [shared["alice"]],
                    "closed_at": 1,
                    "fields": {
                        "name": _clock("Groceries", 200),
                        "members": _clock(["smuggled"], 200),
                        "closed_at": _clock(1, 200),
                    },
                }
            ]
        },
    )

    wire = _lists_since(db_conn, shared["alice"], 0)["list-1"]
    assert _emails(wire) == ["alice@example.com", "bob@example.com"]
    assert wire["close_votes"] == []
    assert wire["closed_at"] is None


# ---- over HTTP ---------------------------------------------------------------


def test_the_roster_reaches_a_client_through_the_sync_endpoint(client):
    client.post("/api/v1/register", json={"email": "http@example.com", "password": PW})
    login = client.post(
        "/api/v1/login", json={"email": "http@example.com", "password": PW, "device_label": "d"}
    ).get_json()
    headers = {"Authorization": f"Bearer {login['token']}"}

    resp = client.post(
        "/api/v1/sync",
        headers=headers,
        json={
            "cursor": 0,
            "device_id": "devA",
            "changes": {"lists": [{"id": "l1", "fields": {"name": _clock("Trip", 100)}}]},
        },
    )

    assert resp.status_code == 200
    wire = resp.get_json()["changes"]["lists"][0]
    assert wire["members"] == [
        {"account_id": login["account_id"], "email": "http@example.com", "initials": "HT"}
    ]
