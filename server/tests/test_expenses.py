"""Expense lists, phase 1 (T-151): the `expenses` kind, the list `currency`, and the item `expense`.

Design and reasons: docs/archive/specs/2026-09-17-expense-lists-design.md.
"""

import json
import sqlite3

import pytest

from shoppinglist_server import accounts, auth
from shoppinglist_server import migrations as migrations_module
from shoppinglist_server import sync
from shoppinglist_server.errors import ApiError

PW = "password123"


# ---- helpers ---------------------------------------------------------------


def _clock(value, ts, by="devA"):
    return {"value": value, "updated_at": ts, "updated_by": by}


def _register(conn, email):
    return auth.register(conn, email, PW)


def _expense_list(list_id="trip", currency="EUR", ts=100, kind="expenses"):
    fields = {"name": _clock("Trip", ts), "kind": _clock(kind, ts)}
    if currency is not None:
        fields["currency"] = _clock(currency, ts)
    return {"id": list_id, "created_at": 1000, "fields": fields}


def _expense(paid_by, paid_for, date="2026-09-17", equal_by=False, equal_for=True):
    return {
        "paid_by": paid_by,
        "equal_by": equal_by,
        "paid_for": paid_for,
        "equal_for": equal_for,
        "date": date,
    }


def _expense_item(item_id, expense, name="Dinner", list_id="trip", ts=100, by="devA"):
    fields = {"name": _clock(name, ts, by)}
    if expense is not ...:
        fields["expense"] = _clock(expense, ts, by)
    return {"id": item_id, "list_id": list_id, "created_at": 1000, "fields": fields}


def _apply(conn, account_id, lists=(), items=(), device="devA"):
    sync.apply_changes(conn, account_id, device, {"lists": list(lists), "items": list(items)})


def _add_member(conn, account_id, list_id="trip"):
    conn.execute(
        "INSERT INTO memberships (account_id, list_id, joined_at) VALUES (?, ?, 0)",
        (account_id, list_id),
    )


def _rejects(conn, account_id, code, lists=(), items=()):
    with pytest.raises(ApiError) as excinfo:
        _apply(conn, account_id, lists=lists, items=items)
    assert excinfo.value.status == 422
    assert excinfo.value.code == code
    return excinfo.value


def _wire_item(conn, account_id, item_id):
    items = sync.delta(conn, account_id, 0)["changes"]["items"]
    return next(item for item in items if item["id"] == item_id)


@pytest.fixture
def alice(db_conn):
    return _register(db_conn, "alice@example.com")


@pytest.fixture
def trip(db_conn, alice):
    """An expenses list owned by alice, with bob as a second member."""
    _apply(db_conn, alice, lists=[_expense_list()])
    bob = _register(db_conn, "bob@example.com")
    _add_member(db_conn, bob)
    return {"alice": alice, "bob": bob}


# ---- the list: kind and currency --------------------------------------------


def test_an_expenses_list_is_created_with_its_currency_on_the_wire(db_conn, alice):
    _apply(db_conn, alice, lists=[_expense_list(currency="pizza slices")])

    wire = sync.delta(db_conn, alice, 0)["changes"]["lists"][0]
    assert wire["fields"]["kind"]["value"] == "expenses"
    # Free text, not the ISO check the price field uses.
    assert wire["fields"]["currency"]["value"] == "pizza slices"


def test_other_kinds_carry_a_null_currency_on_the_wire(db_conn, alice):
    _apply(db_conn, alice, lists=[{"id": "groceries", "fields": {"name": _clock("Food", 100)}}])

    wire = sync.delta(db_conn, alice, 0)["changes"]["lists"][0]
    assert wire["fields"]["currency"]["value"] is None


@pytest.mark.parametrize("currency", [None, "", "   "])
def test_an_expenses_list_cannot_be_created_without_a_currency(db_conn, alice, currency):
    error = _rejects(db_conn, alice, "invalid_currency", lists=[_expense_list(currency=currency)])
    assert error.details == {"row_id": "trip", "field": "currency"}


def test_currency_is_capped_at_32_characters(db_conn, alice):
    _rejects(db_conn, alice, "invalid_currency", lists=[_expense_list(currency="x" * 33)])


def test_an_expenses_list_currency_cannot_be_blanked_later(db_conn, trip):
    update = {"id": "trip", "fields": {"currency": _clock("", 200)}}
    _rejects(db_conn, trip["alice"], "invalid_currency", lists=[update])


@pytest.mark.parametrize(
    ("created_as", "changed_to"),
    [("shopping", "expenses"), ("checklist", "expenses"), ("expenses", "shopping")],
)
def test_the_expenses_kind_is_fixed_for_life_in_both_directions(
    db_conn, alice, created_as, changed_to
):
    _apply(db_conn, alice, lists=[_expense_list(kind=created_as)])

    # Even a newer clock cannot do it: this is a rule about the list, not a lost race.
    update = {"id": "trip", "fields": {"kind": _clock(changed_to, 999)}}
    error = _rejects(db_conn, alice, "invalid_field", lists=[update])
    assert error.details == {"row_id": "trip", "field": "kind"}


def test_rewriting_an_expenses_list_with_its_own_kind_is_fine(db_conn, trip):
    # Clients may re-send unchanged fields; that is not a conversion.
    _apply(db_conn, trip["alice"], lists=[_expense_list(ts=200)])


def test_shopping_and_checklist_still_convert_freely(db_conn, alice):
    _apply(db_conn, alice, lists=[_expense_list(kind="shopping")])
    _apply(db_conn, alice, lists=[{"id": "trip", "fields": {"kind": _clock("checklist", 200)}}])

    assert db_conn.execute("SELECT kind FROM lists").fetchone()["kind"] == "checklist"


# ---- the item: shape --------------------------------------------------------


def test_an_expense_round_trips_as_one_field(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    expense = _expense({a: "40.00", b: "24.00"}, {a: "32.00", b: "32.00"})
    _apply(db_conn, a, items=[_expense_item("e1", expense)])

    wire = _wire_item(db_conn, a, "e1")
    assert wire["fields"]["expense"]["value"] == expense
    assert wire["fields"]["expense"]["updated_at"] == 100


def test_unknown_keys_inside_an_expense_are_dropped_not_stored(db_conn, trip):
    a = trip["alice"]
    expense = {**_expense({a: "5"}, {a: "5"}), "tip": "junk" * 100}
    _apply(db_conn, a, items=[_expense_item("e1", expense)])

    stored = json.loads(db_conn.execute("SELECT expense FROM items").fetchone()["expense"])
    assert set(stored) == {"paid_by", "equal_by", "paid_for", "equal_for", "date"}


def test_amounts_are_compared_in_cents_not_as_strings(db_conn, trip):
    a = trip["alice"]
    _apply(db_conn, a, items=[_expense_item("e1", _expense({a: "12.5"}, {a: "12.50"}))])


def test_two_payers_with_an_unequal_split_are_accepted(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    expense = _expense({a: "0.01", b: "63.99"}, {a: "21.34", b: "42.66"}, equal_for=False)
    _apply(db_conn, a, items=[_expense_item("e1", expense)])


def _invalid_expenses():
    a = "acct-a"
    ok = _expense({a: "10"}, {a: "10"})
    return [
        pytest.param("not an object", id="not-an-object"),
        pytest.param({**ok, "paid_by": {}}, id="empty-paid-by"),
        pytest.param({k: v for k, v in ok.items() if k != "paid_for"}, id="missing-paid-for"),
        pytest.param({**ok, "paid_by": [a]}, id="paid-by-not-a-map"),
        pytest.param(_expense({a: "0"}, {a: "0"}), id="zero"),
        pytest.param(_expense({a: "0.00"}, {a: "0.00"}), id="zero-with-cents"),
        pytest.param(_expense({a: "-10"}, {a: "-10"}), id="negative"),
        pytest.param(_expense({a: 10}, {a: 10}), id="number-not-string"),
        pytest.param(_expense({a: "10.999"}, {a: "10.999"}), id="three-decimals"),
        pytest.param(_expense({a: "10,50"}, {a: "10,50"}), id="comma-decimal"),
        # Unicode digits: Python's \d would take these (T-125), [0-9] does not.
        pytest.param(_expense({a: "٥"}, {a: "٥"}), id="non-ascii-digits"),
        pytest.param(_expense({a: "10"}, {a: "9.99"}), id="sums-differ-by-a-cent"),
        pytest.param(_expense({"": "10"}, {"": "10"}), id="blank-participant"),
        pytest.param(_expense({"x" * 129: "10"}, {"x" * 129: "10"}), id="participant-too-long"),
        pytest.param({**ok, "equal_by": "yes"}, id="equal-by-not-bool"),
        pytest.param({k: v for k, v in ok.items() if k != "equal_for"}, id="equal-for-missing"),
        pytest.param({**ok, "date": "17.09.2026"}, id="date-format"),
        pytest.param({**ok, "date": "2026-02-30"}, id="date-not-in-calendar"),
        pytest.param({**ok, "date": "2026-09-17T10:00"}, id="date-with-time"),
        pytest.param({k: v for k, v in ok.items() if k != "date"}, id="date-missing"),
        pytest.param(
            _expense({f"p{i}": "1" for i in range(201)}, {f"p{i}": "1" for i in range(201)}),
            id="too-many-participants",
        ),
    ]


@pytest.mark.parametrize("expense", _invalid_expenses())
def test_a_malformed_expense_is_rejected_naming_the_row(db_conn, trip, expense):
    error = _rejects(
        db_conn, trip["alice"], "invalid_expense", items=[_expense_item("e1", expense)]
    )
    # row_id + field is what lets the clients quarantine just this row (T-32).
    assert error.details == {"row_id": "e1", "field": "expense"}


# ---- the item: which list it may sit on --------------------------------------


def test_an_expense_is_refused_on_a_shopping_list(db_conn, alice):
    _apply(db_conn, alice, lists=[_expense_list(kind="shopping")])
    expense = _expense({alice: "5"}, {alice: "5"})

    _rejects(db_conn, alice, "invalid_expense", items=[_expense_item("e1", expense)])


def test_a_null_expense_on_a_shopping_list_is_harmless(db_conn, alice):
    _apply(db_conn, alice, lists=[_expense_list(kind="shopping")])

    _apply(db_conn, alice, items=[_expense_item("i1", None, name="Milk")])
    assert _wire_item(db_conn, alice, "i1")["fields"]["expense"]["value"] is None


def test_every_item_on_an_expenses_list_must_be_an_expense(db_conn, trip):
    error = _rejects(db_conn, trip["alice"], "invalid_expense", items=[_expense_item("e1", ...)])
    assert error.details["row_id"] == "e1"


def test_an_expense_cannot_be_nulled_later(db_conn, trip):
    a = trip["alice"]
    _apply(db_conn, a, items=[_expense_item("e1", _expense({a: "5"}, {a: "5"}))])

    _rejects(db_conn, a, "invalid_expense", items=[_expense_item("e1", None, ts=200)])


def test_a_stale_null_expense_is_discarded_not_refused(db_conn, trip):
    """The null-expense rule, like the participant rule, binds only a write that would win
    last-write-wins: a stale write is discarded silently, never refused (T-206)."""
    a = trip["alice"]
    original = _expense({a: "5"}, {a: "5"})
    _apply(db_conn, a, items=[_expense_item("e1", original, ts=200)])

    _apply(db_conn, a, items=[_expense_item("e1", None, ts=100)])  # stale: ts=100 < 200

    assert _wire_item(db_conn, a, "e1")["fields"]["expense"]["value"] == original


def test_other_fields_of_an_expense_can_be_edited_without_resending_it(db_conn, trip):
    a = trip["alice"]
    _apply(db_conn, a, items=[_expense_item("e1", _expense({a: "5"}, {a: "5"}))])

    rename = {"id": "e1", "fields": {"name": _clock("Lunch", 200), "note": _clock("tip!", 200)}}
    _apply(db_conn, a, items=[rename])
    assert _wire_item(db_conn, a, "e1")["fields"]["name"]["value"] == "Lunch"


# ---- the item: participants --------------------------------------------------


def test_every_participant_must_be_a_member(db_conn, trip):
    a = trip["alice"]
    stranger = _register(db_conn, "stranger@example.com")
    expense = _expense({a: "10"}, {a: "5", stranger: "5"})

    error = _rejects(db_conn, a, "invalid_expense", items=[_expense_item("e1", expense)])
    assert error.details["account_id"] == stranger


def test_an_expense_involving_someone_who_left_stays_editable(db_conn, trip):
    """Leaving an open expenses list is refused from T-157 on, but deleting an account never is —
    so a departed participant is still reachable, and the expense still has to be editable."""
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    original = _expense({b: "30"}, {a: "10", b: "10", carol: "10"})
    _apply(db_conn, a, items=[_expense_item("e1", original)])
    accounts.delete_account(db_conn, b, PW)

    # Correcting how the living members split it, leaving the departed member's own share alone.
    edit = _expense({b: "30"}, {a: "15", b: "10", carol: "5"})
    _apply(db_conn, a, items=[_expense_item("e1", edit, ts=200)])
    assert _wire_item(db_conn, a, "e1")["fields"]["expense"]["value"] == edit


def test_someone_who_left_cannot_be_added_to_a_different_expense(db_conn, trip):
    a, b = trip["alice"], trip["bob"]
    accounts.delete_account(db_conn, b, PW)

    new = _expense({a: "10"}, {a: "5", b: "5"})
    _rejects(db_conn, a, "invalid_expense", items=[_expense_item("e2", new)])


def test_a_stale_write_naming_a_departed_member_is_not_rejected(db_conn, trip):
    """It loses last-write-wins and is discarded anyway. Rejecting it would quarantine an innocent
    offline edit on the client for something that happened while it was offline."""
    a, b = trip["alice"], trip["bob"]
    carol = _register(db_conn, "carol@example.com")
    _add_member(db_conn, carol)
    _apply(db_conn, a, items=[_expense_item("e1", _expense({a: "10"}, {a: "10"}), ts=100)])
    newer = _expense({a: "20"}, {a: "20"})
    _apply(db_conn, a, items=[_expense_item("e1", newer, ts=300)])
    accounts.delete_account(db_conn, carol, PW)

    # Written offline at ts=200, naming carol, pushed after she left and after the ts=300 edit.
    stale = _expense({a: "10"}, {a: "5", carol: "5"})
    _apply(db_conn, b, items=[_expense_item("e1", stale, ts=200, by="devB")], device="devB")

    assert _wire_item(db_conn, a, "e1")["fields"]["expense"]["value"] == newer


# ---- names are not unique on an expenses list --------------------------------


def test_several_expenses_may_share_a_name(db_conn, trip):
    a = trip["alice"]
    dinner = _expense({a: "30"}, {a: "30"})
    _apply(
        db_conn,
        a,
        items=[
            _expense_item("e1", dinner, "Dinner at Luigi's"),
            _expense_item("e2", dinner, "dinner at luigi's"),
        ],
    )
    sync.name_merge(db_conn, "trip")

    live = db_conn.execute("SELECT id FROM items WHERE deleted = 0 ORDER BY id").fetchall()
    assert [row["id"] for row in live] == ["e1", "e2"]


def test_renaming_an_expense_onto_another_expense_name_does_not_merge(db_conn, trip):
    a = trip["alice"]
    _apply(
        db_conn,
        a,
        items=[
            _expense_item("e1", _expense({a: "30"}, {a: "30"}), "Dinner"),
            _expense_item("e2", _expense({a: "12"}, {a: "12"}), "Taxi"),
        ],
    )
    _apply(db_conn, a, items=[{"id": "e2", "fields": {"name": _clock("Dinner", 200)}}])
    sync.name_merge(db_conn, "trip")

    live = db_conn.execute("SELECT id, name FROM items WHERE deleted = 0 ORDER BY id").fetchall()
    assert [(row["id"], row["name"]) for row in live] == [("e1", "Dinner"), ("e2", "Dinner")]


def test_shopping_lists_still_merge_same_named_items(db_conn, alice):
    _apply(db_conn, alice, lists=[_expense_list(kind="shopping")])
    _apply(
        db_conn,
        alice,
        items=[_expense_item("i1", ..., "Milk"), _expense_item("i2", ..., "milk", ts=101)],
    )
    sync.name_merge(db_conn, "trip")

    assert db_conn.execute("SELECT COUNT(*) AS n FROM items WHERE deleted = 0").fetchone()["n"] == 1


# ---- a solo list, end to end -------------------------------------------------


def test_a_solo_expenses_list_lifecycle(db_conn, alice):
    _apply(db_conn, alice, lists=[_expense_list(list_id="me", currency="CHF")])
    groceries = _expense({alice: "84.20"}, {alice: "84.20"}, equal_by=True)
    _apply(db_conn, alice, items=[_expense_item("e1", groceries, "Groceries", list_id="me")])

    corrected = _expense({alice: "48.20"}, {alice: "48.20"}, equal_by=True)
    _apply(db_conn, alice, items=[_expense_item("e1", corrected, "Groceries", "me", ts=200)])
    assert _wire_item(db_conn, alice, "e1")["fields"]["expense"]["value"] == corrected

    _apply(db_conn, alice, items=[{"id": "e1", "fields": {"deleted": _clock(True, 300)}}])
    assert _wire_item(db_conn, alice, "e1")["fields"]["deleted"]["value"] is True


# ---- over HTTP ---------------------------------------------------------------


def _login(client, email):
    client.post("/api/v1/register", json={"email": email, "password": PW})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": PW, "device_label": "test"}
    )
    body = resp.get_json()
    return body["token"], body["account_id"]


def test_sync_endpoint_quarantinable_422_and_no_merge_for_expenses(client):
    token, me = _login(client, "http@example.com")
    auth_header = {"Authorization": f"Bearer {token}"}
    dinner = _expense({me: "30"}, {me: "30"})

    resp = client.post(
        "/api/v1/sync",
        headers=auth_header,
        json={
            "cursor": 0,
            "device_id": "devA",
            "changes": {
                "lists": [_expense_list()],
                "items": [_expense_item("e1", dinner), _expense_item("e2", dinner)],
            },
        },
    )
    assert resp.status_code == 200
    live = [i for i in resp.get_json()["changes"]["items"] if not i["fields"]["deleted"]["value"]]
    assert sorted(i["id"] for i in live) == ["e1", "e2"]

    resp = client.post(
        "/api/v1/sync",
        headers=auth_header,
        json={
            "cursor": 0,
            "device_id": "devA",
            "changes": {"items": [_expense_item("e3", _expense({me: "1"}, {me: "2"}))]},
        },
    )
    assert resp.status_code == 422
    body = resp.get_json()
    assert body["error"] == "invalid_expense"
    assert body["row_id"] == "e3"
    assert body["field"] == "expense"


# ---- the migration -----------------------------------------------------------


def test_migration_6_adds_the_columns_and_rebuilds_the_name_index(tmp_path):
    conn = sqlite3.connect(str(tmp_path / "v5.db"))
    conn.row_factory = sqlite3.Row
    # Just the parts of the v5 schema that migration 6 touches.
    conn.execute("CREATE TABLE lists (id TEXT PRIMARY KEY, kind TEXT NOT NULL DEFAULT 'shopping')")
    conn.execute(
        "CREATE TABLE items (id TEXT PRIMARY KEY, list_id TEXT NOT NULL, name TEXT NOT NULL, "
        "deleted INTEGER NOT NULL DEFAULT 0)"
    )
    conn.execute(
        "CREATE UNIQUE INDEX idx_items_list_name_live ON items (list_id, lower(name)) "
        "WHERE deleted = 0"
    )
    conn.execute("INSERT INTO lists (id) VALUES ('l1')")
    conn.execute("INSERT INTO items VALUES ('i1', 'l1', 'Milk', 0)")
    conn.commit()

    for statement in dict(migrations_module.MIGRATIONS)[6]:
        conn.execute(statement)
    conn.commit()

    assert conn.execute("SELECT currency, currency_ts, currency_by FROM lists").fetchone()[1] == 0
    assert conn.execute("SELECT expense FROM items").fetchone()["expense"] is None
    # Existing shopping items are still name-unique after the rebuild...
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute("INSERT INTO items (id, list_id, name) VALUES ('i2', 'l1', 'milk')")
    # ...while expense rows are exempt.
    conn.execute("UPDATE items SET expense = '{}' WHERE id = 'i1'")
    conn.execute("INSERT INTO items (id, list_id, name, expense) VALUES ('i3', 'l1', 'Milk', '{}')")
    conn.close()


def test_fresh_schema_and_migration_6_build_the_same_name_index(db_conn, tmp_path):
    def index_sql(conn):
        sql = conn.execute(
            "SELECT sql FROM sqlite_master WHERE name = 'idx_items_list_name_live'"
        ).fetchone()["sql"]
        return " ".join(sql.replace("IF NOT EXISTS ", "").split())

    migrated = sqlite3.connect(str(tmp_path / "migrated.db"))
    migrated.row_factory = sqlite3.Row
    migrated.execute(
        "CREATE TABLE items (id TEXT, list_id TEXT, name TEXT, deleted INTEGER, expense TEXT)"
    )
    migrated.execute("CREATE TABLE lists (id TEXT)")
    statements = dict(migrations_module.MIGRATIONS)[6]
    for statement in statements:
        if "ADD COLUMN expense TEXT" not in statement:
            migrated.execute(statement)

    assert index_sql(migrated) == index_sql(db_conn)
    migrated.close()
