"""Tests for the pure sync engine (no HTTP)."""

import pytest

from shoppinglist_server import auth, sync
from shoppinglist_server.errors import ApiError

PW = "password123"


# ---- helpers ---------------------------------------------------------------


def _register(conn, email):
    return auth.register(conn, email, PW)


def _clock(value, ts, by):
    return {"value": value, "updated_at": ts, "updated_by": by}


def _mk_list(list_id, name, ts, by, created_at=1000, category_order=None, notes=None):
    fields = {"name": _clock(name, ts, by)}
    if category_order is not None:
        fields["category_order"] = _clock(category_order, ts, by)
    if notes is not None:
        fields["notes"] = _clock(notes, ts, by)
    return {"id": list_id, "created_at": created_at, "fields": fields}


def _mk_item(item_id, list_id, created_at=1000, **field_clocks):
    fields = {k: _clock(*v) for k, v in field_clocks.items()}
    return {"id": item_id, "list_id": list_id, "created_at": created_at, "fields": fields}


def _create_list(conn, account_id, device, list_id="list-1", name="Groceries", ts=100):
    sync.apply_changes(
        conn, account_id, device, {"lists": [_mk_list(list_id, name, ts, device)]}
    )


def _item_row(conn, item_id):
    return conn.execute("SELECT * FROM items WHERE id = ?", (item_id,)).fetchone()


def _live_items(conn, list_id):
    return conn.execute(
        "SELECT * FROM items WHERE list_id = ? AND deleted = 0 ORDER BY id", (list_id,)
    ).fetchall()


# ---- creating lists and items ---------------------------------------------


def test_create_list_via_sync_makes_creator_a_member(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA", "list-1", "Groceries")

    row = db_conn.execute("SELECT * FROM lists WHERE id = ?", ("list-1",)).fetchone()
    assert row["name"] == "Groceries"
    member = db_conn.execute(
        "SELECT 1 FROM memberships WHERE account_id = ? AND list_id = ?",
        (account_id, "list-1"),
    ).fetchone()
    assert member is not None


def test_create_item_stores_all_fields(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    sync.apply_changes(
        db_conn,
        account_id,
        "devA",
        {
            "items": [
                _mk_item(
                    "item-1",
                    "list-1",
                    created_at=1000,
                    name=("Milk", 100, "devA"),
                    category=("groceries", 100, "devA"),
                    stores=(["Rewe", "Aldi"], 100, "devA"),
                    quantity=("2l", 100, "devA"),
                    price=({"amount": "1.99", "currency": "EUR"}, 100, "devA"),
                    note=("the ripe ones", 100, "devA"),
                    status=("todo", 100, "devA"),
                )
            ]
        },
    )
    row = _item_row(db_conn, "item-1")
    assert row["name"] == "Milk"
    assert row["category"] == "groceries"
    assert row["stores"] == '["Rewe", "Aldi"]'
    assert row["price_amount"] == "1.99"
    assert row["price_currency"] == "EUR"
    assert row["status"] == "todo"


def test_create_item_unknown_list_raises_422(db_conn):
    account_id = _register(db_conn, "a@example.com")
    with pytest.raises(ApiError) as excinfo:
        sync.apply_changes(
            db_conn,
            account_id,
            "devA",
            {"items": [_mk_item("item-1", "nonexistent", name=("Milk", 100, "devA"))]},
        )
    assert excinfo.value.status == 422


def test_edit_item_in_non_member_list_raises_403(db_conn):
    owner = _register(db_conn, "owner@example.com")
    intruder = _register(db_conn, "intruder@example.com")
    _create_list(db_conn, owner, "devOwner")
    sync.apply_changes(
        db_conn, owner, "devOwner",
        {"items": [_mk_item("item-1", "list-1", name=("Milk", 100, "devOwner"))]},
    )
    with pytest.raises(ApiError) as excinfo:
        sync.apply_changes(
            db_conn, intruder, "devIntruder",
            {"items": [_mk_item("item-1", "list-1", category=("x", 200, "devIntruder"))]},
        )
    assert excinfo.value.status == 403


def test_existing_item_authorized_against_its_stored_list_not_payload(db_conn):
    # Attacker is a member of their own list but not the victim's; they must not
    # be able to edit a victim item by mislabelling the payload's list_id.
    victim = _register(db_conn, "victim@example.com")
    attacker = _register(db_conn, "attacker@example.com")
    _create_list(db_conn, victim, "devV", "list-victim", "Victim")
    sync.apply_changes(db_conn, victim, "devV",
                       {"items": [_mk_item("secret", "list-victim", name=("Milk", 100, "devV"))]})
    _create_list(db_conn, attacker, "devA", "list-attacker", "Attacker")

    with pytest.raises(ApiError) as excinfo:
        sync.apply_changes(
            db_conn, attacker, "devA",
            {"items": [{"id": "secret", "list_id": "list-attacker",
                        "fields": {"name": _clock("Hacked", 999, "devA")}}]},
        )
    assert excinfo.value.status == 403
    assert _item_row(db_conn, "secret")["name"] == "Milk"


def test_empty_item_name_raises_422(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    with pytest.raises(ApiError) as excinfo:
        sync.apply_changes(
            db_conn, account_id, "devA",
            {"items": [_mk_item("item-1", "list-1", name=("   ", 100, "devA"))]},
        )
    assert excinfo.value.status == 422


def test_invalid_status_raises_422(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    with pytest.raises(ApiError) as excinfo:
        sync.apply_changes(
            db_conn, account_id, "devA",
            {"items": [_mk_item("item-1", "list-1",
                                name=("Milk", 100, "devA"),
                                status=("bought", 100, "devA"))]},
        )
    assert excinfo.value.status == 422


def test_invalid_price_amount_raises_422(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    with pytest.raises(ApiError) as excinfo:
        sync.apply_changes(
            db_conn, account_id, "devA",
            {"items": [_mk_item("item-1", "list-1",
                                name=("Milk", 100, "devA"),
                                price=({"amount": "1.999", "currency": "EUR"}, 100, "devA"))]},
        )
    assert excinfo.value.status == 422


# ---- field-level LWW merge -------------------------------------------------


def _setup_item(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    sync.apply_changes(
        db_conn, account_id, "devA",
        {"items": [_mk_item("item-1", "list-1", name=("Milk", 100, "devA"))]},
    )
    return account_id


def test_different_field_edits_both_survive(db_conn):
    account_id = _setup_item(db_conn)
    sync.apply_changes(db_conn, account_id, "X",
                       {"items": [_mk_item("item-1", "list-1", category=("food", 200, "X"))]})
    sync.apply_changes(db_conn, account_id, "Y",
                       {"items": [_mk_item("item-1", "list-1", quantity=("2l", 201, "Y"))]})
    row = _item_row(db_conn, "item-1")
    assert row["category"] == "food"
    assert row["quantity"] == "2l"
    assert row["name"] == "Milk"


@pytest.mark.parametrize("order", [("old_first"), ("new_first")])
def test_same_field_latest_wins(db_conn, order):
    account_id = _setup_item(db_conn)
    older = _mk_item("item-1", "list-1", name=("MilkOld", 200, "X"))
    newer = _mk_item("item-1", "list-1", name=("MilkNew", 300, "Y"))
    seq = [older, newer] if order == "old_first" else [newer, older]
    for obj in seq:
        sync.apply_changes(db_conn, account_id, "dev", {"items": [obj]})
    assert _item_row(db_conn, "item-1")["name"] == "MilkNew"


@pytest.mark.parametrize("order", ["ab", "ba"])
def test_equal_timestamp_tiebreak_by_updated_by(db_conn, order):
    account_id = _setup_item(db_conn)
    a = _mk_item("item-1", "list-1", name=("valueA", 500, "dev-a"))
    b = _mk_item("item-1", "list-1", name=("valueB", 500, "dev-b"))
    seq = [a, b] if order == "ab" else [b, a]
    for obj in seq:
        sync.apply_changes(db_conn, account_id, "dev", {"items": [obj]})
    # Larger updated_by ("dev-b") wins deterministically.
    assert _item_row(db_conn, "item-1")["name"] == "valueB"


def test_stores_array_replace_wins(db_conn):
    account_id = _setup_item(db_conn)
    sync.apply_changes(db_conn, account_id, "X",
                       {"items": [_mk_item("item-1", "list-1", stores=(["Rewe"], 100, "X"))]})
    sync.apply_changes(db_conn, account_id, "Y",
                       {"items": [_mk_item("item-1", "list-1", stores=(["Aldi", "Lidl"], 200, "Y"))]})
    assert _item_row(db_conn, "item-1")["stores"] == '["Aldi", "Lidl"]'


def test_price_amount_and_currency_move_as_one_field(db_conn):
    account_id = _setup_item(db_conn)
    sync.apply_changes(db_conn, account_id, "X",
                       {"items": [_mk_item("item-1", "list-1",
                                           price=({"amount": "1.99", "currency": "EUR"}, 100, "X"))]})
    sync.apply_changes(db_conn, account_id, "Y",
                       {"items": [_mk_item("item-1", "list-1",
                                           price=({"amount": "2.50", "currency": "USD"}, 200, "Y"))]})
    row = _item_row(db_conn, "item-1")
    assert row["price_amount"] == "2.50"
    assert row["price_currency"] == "USD"


def test_status_transition_conflict_resolves_latest(db_conn):
    account_id = _setup_item(db_conn)
    sync.apply_changes(db_conn, account_id, "X",
                       {"items": [_mk_item("item-1", "list-1", status=("todo", 200, "X"))]})
    sync.apply_changes(db_conn, account_id, "Y",
                       {"items": [_mk_item("item-1", "list-1", status=("checked", 300, "Y"))]})
    assert _item_row(db_conn, "item-1")["status"] == "checked"


def test_tombstone_beats_older_edit_and_loses_to_newer_resurrect(db_conn):
    account_id = _setup_item(db_conn)
    # Delete at ts=200.
    sync.apply_changes(db_conn, account_id, "X",
                       {"items": [_mk_item("item-1", "list-1", deleted=(True, 200, "X"))]})
    assert _item_row(db_conn, "item-1")["deleted"] == 1

    # Older resurrect (ts=150 < 200) does NOT bring it back.
    sync.apply_changes(db_conn, account_id, "Y",
                       {"items": [_mk_item("item-1", "list-1", deleted=(False, 150, "Y"))]})
    assert _item_row(db_conn, "item-1")["deleted"] == 1

    # Newer resurrect (ts=300 > 200) brings it back.
    sync.apply_changes(db_conn, account_id, "Z",
                       {"items": [_mk_item("item-1", "list-1", deleted=(False, 300, "Z"))]})
    assert _item_row(db_conn, "item-1")["deleted"] == 0


# ---- same-name merge -------------------------------------------------------


def test_same_name_merge_picks_deterministic_survivor_and_merges_fields(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    # Device A creates "Milk" (created earlier), with a category.
    sync.apply_changes(db_conn, account_id, "X",
                       {"items": [_mk_item("a1", "list-1", created_at=1000,
                                           name=("Milk", 100, "X"),
                                           category=("food", 100, "X"))]})
    # Device B independently creates "milk" (created later), with a quantity.
    sync.apply_changes(db_conn, account_id, "Y",
                       {"items": [_mk_item("b1", "list-1", created_at=2000,
                                           name=("milk", 110, "Y"),
                                           quantity=("2l", 110, "Y"))]})

    live = _live_items(db_conn, "list-1")
    assert len(live) == 1
    survivor = live[0]
    assert survivor["id"] == "a1"  # earliest created_at wins
    assert survivor["name"] == "milk"  # newest name clock (ts=110) wins
    assert survivor["category"] == "food"
    assert survivor["quantity"] == "2l"

    loser = _item_row(db_conn, "b1")
    assert loser["deleted"] == 1
    assert loser["deleted_by"] == "server-merge"


def test_name_merge_is_idempotent_noop_when_no_duplicates(db_conn):
    account_id = _setup_item(db_conn)
    sync.name_merge(db_conn, "list-1")  # must not raise or change anything
    assert len(_live_items(db_conn, "list-1")) == 1


# ---- delta -----------------------------------------------------------------


def test_delta_excludes_non_member_rows(db_conn):
    account_a = _register(db_conn, "a@example.com")
    account_b = _register(db_conn, "b@example.com")
    _create_list(db_conn, account_a, "devA", "list-a", "A's list")
    sync.apply_changes(db_conn, account_a, "devA",
                       {"items": [_mk_item("ia", "list-a", name=("Milk", 100, "devA"))]})
    _create_list(db_conn, account_b, "devB", "list-b", "B's list")
    sync.apply_changes(db_conn, account_b, "devB",
                       {"items": [_mk_item("ib", "list-b", name=("Bread", 100, "devB"))]})

    result = sync.delta(db_conn, account_b, cursor=0, full_lists=[])
    list_ids = {l["id"] for l in result["changes"]["lists"]}
    item_ids = {i["id"] for i in result["changes"]["items"]}
    assert list_ids == {"list-b"}
    assert item_ids == {"ib"}


def test_cursor_zero_returns_everything_visible(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    sync.apply_changes(db_conn, account_id, "devA",
                       {"items": [_mk_item("item-1", "list-1", name=("Milk", 100, "devA"))]})
    result = sync.delta(db_conn, account_id, cursor=0, full_lists=[])
    assert {l["id"] for l in result["changes"]["lists"]} == {"list-1"}
    assert {i["id"] for i in result["changes"]["items"]} == {"item-1"}
    # Full round-trip: response field clocks match what went in.
    item = result["changes"]["items"][0]
    assert item["fields"]["name"] == {"value": "Milk", "updated_at": 100, "updated_by": "devA"}


def test_full_lists_returns_rows_older_than_cursor(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    sync.apply_changes(db_conn, account_id, "devA",
                       {"items": [_mk_item("item-1", "list-1", name=("Milk", 100, "devA"))]})
    # A cursor far above any row's change_seq: a plain delta returns nothing...
    plain = sync.delta(db_conn, account_id, cursor=9999, full_lists=[])
    assert plain["changes"]["items"] == []
    # ...but full_lists forces a snapshot regardless of cursor.
    snapshot = sync.delta(db_conn, account_id, cursor=9999, full_lists=["list-1"])
    assert {i["id"] for i in snapshot["changes"]["items"]} == {"item-1"}
    assert {l["id"] for l in snapshot["changes"]["lists"]} == {"list-1"}


def test_full_lists_non_member_raises_403(db_conn):
    account_a = _register(db_conn, "a@example.com")
    account_b = _register(db_conn, "b@example.com")
    _create_list(db_conn, account_a, "devA", "list-a", "A's list")
    with pytest.raises(ApiError) as excinfo:
        sync.delta(db_conn, account_b, cursor=0, full_lists=["list-a"])
    assert excinfo.value.status == 403


def test_delta_returns_tombstones_incrementally(db_conn):
    account_id = _setup_item(db_conn)
    baseline = sync.delta(db_conn, account_id, cursor=0, full_lists=[])["cursor"]
    sync.apply_changes(db_conn, account_id, "X",
                       {"items": [_mk_item("item-1", "list-1", deleted=(True, 500, "X"))]})
    result = sync.delta(db_conn, account_id, cursor=baseline, full_lists=[])
    tomb = [i for i in result["changes"]["items"] if i["id"] == "item-1"]
    assert len(tomb) == 1
    assert tomb[0]["fields"]["deleted"]["value"] is True


# ---- check_cursor ----------------------------------------------------------


def test_check_cursor_below_gc_horizon_raises_410(db_conn):
    db_conn.execute("UPDATE meta SET gc_horizon = 50 WHERE id = 1")
    with pytest.raises(ApiError) as excinfo:
        sync.check_cursor(db_conn, 10)
    assert excinfo.value.status == 410
    assert excinfo.value.code == "full_resync_required"


def test_check_cursor_zero_and_recent_are_allowed(db_conn):
    db_conn.execute("UPDATE meta SET gc_horizon = 50 WHERE id = 1")
    sync.check_cursor(db_conn, 0)   # initial full sync always allowed
    sync.check_cursor(db_conn, 60)  # at/above horizon allowed


# ---- list-level LWW --------------------------------------------------------


def test_list_name_and_category_order_lww(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA", "list-1", "Groceries", ts=100)
    sync.apply_changes(db_conn, account_id, "X",
                       {"lists": [_mk_list("list-1", "Food", 200, "X",
                                           category_order=["freezer", "produce"])]})
    row = db_conn.execute("SELECT * FROM lists WHERE id = ?", ("list-1",)).fetchone()
    assert row["name"] == "Food"
    assert row["category_order"] == '["freezer", "produce"]'


def test_list_notes_set_via_sync_and_returned_on_wire(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA", "list-1", "Groceries", ts=100)

    sync.apply_changes(db_conn, account_id, "X",
                       {"lists": [_mk_list("list-1", "Groceries", 200, "X",
                                           notes="Gate code: 4471")]})

    row = db_conn.execute("SELECT * FROM lists WHERE id = ?", ("list-1",)).fetchone()
    assert row["notes"] == "Gate code: 4471"

    wire_lists = {lst["id"]: lst for lst in sync.delta(db_conn, account_id, 0)["changes"]["lists"]}
    assert wire_lists["list-1"]["fields"]["notes"]["value"] == "Gate code: 4471"


def test_list_notes_lww_older_write_loses(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA", "list-1", "Groceries", ts=100)
    sync.apply_changes(db_conn, account_id, "X",
                       {"lists": [_mk_list("list-1", "Groceries", 300, "X", notes="new note")]})

    # A stale write (earlier timestamp) must not overwrite the newer note.
    sync.apply_changes(db_conn, account_id, "Y",
                       {"lists": [_mk_list("list-1", "Groceries", 200, "Y", notes="stale note")]})

    row = db_conn.execute("SELECT * FROM lists WHERE id = ?", ("list-1",)).fetchone()
    assert row["notes"] == "new note"


def test_list_notes_can_be_cleared_to_null(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA", "list-1", "Groceries", ts=100)
    sync.apply_changes(db_conn, account_id, "X",
                       {"lists": [_mk_list("list-1", "Groceries", 200, "X", notes="temporary")]})

    sync.apply_changes(db_conn, account_id, "X",
                       {"lists": [_mk_list("list-1", "Groceries", 300, "X", notes=None)]})

    # notes wasn't included in this push's fields (notes=None short-circuits _mk_list's
    # helper), so re-send explicitly via the raw payload to actually clear it.
    sync.apply_changes(db_conn, account_id, "X", {
        "lists": [{
            "id": "list-1", "created_at": 1000,
            "fields": {"notes": {"value": None, "updated_at": 400, "updated_by": "X"}},
        }],
    })
    row = db_conn.execute("SELECT * FROM lists WHERE id = ?", ("list-1",)).fetchone()
    assert row["notes"] is None


def test_list_notes_over_max_length_rejected(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA", "list-1", "Groceries", ts=100)

    too_long = "x" * (sync.NOTES_MAX_LENGTH + 1)
    with pytest.raises(ApiError) as exc_info:
        sync.apply_changes(db_conn, account_id, "X",
                           {"lists": [_mk_list("list-1", "Groceries", 200, "X", notes=too_long)]})
    assert exc_info.value.status == 422
    assert exc_info.value.code == "invalid_notes"


def test_list_notes_at_max_length_accepted(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA", "list-1", "Groceries", ts=100)

    exactly_max = "x" * sync.NOTES_MAX_LENGTH
    sync.apply_changes(db_conn, account_id, "X",
                       {"lists": [_mk_list("list-1", "Groceries", 200, "X", notes=exactly_max)]})
    row = db_conn.execute("SELECT * FROM lists WHERE id = ?", ("list-1",)).fetchone()
    assert row["notes"] == exactly_max
