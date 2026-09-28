"""An item's optional, passive due date (T-323): the `due` field, a nullable calendar date
'YYYY-MM-DD' synced by field-level last-write-wins like `note`."""

import pytest

from shoppinglist_server import auth, sync
from shoppinglist_server.errors import ApiError

PW = "password123"


def _clock(value, ts, by="devA"):
    return {"value": value, "updated_at": ts, "updated_by": by}


def _item(item_id, list_id="list-1", created_at=1000, **field_clocks):
    fields = {key: _clock(*clock) for key, clock in field_clocks.items()}
    return {"id": item_id, "list_id": list_id, "created_at": created_at, "fields": fields}


def _list(list_id="list-1", kind="checklist", ts=100):
    fields = {"name": _clock("Chores", ts), "kind": _clock(kind, ts)}
    if kind == "expenses":
        fields["currency"] = _clock("EUR", ts)
    return {"id": list_id, "created_at": 1000, "fields": fields}


def _apply(conn, account_id, device="devA", lists=(), items=()):
    sync.apply_changes(conn, account_id, device, {"lists": list(lists), "items": list(items)})


def _wire_item(conn, account_id, item_id):
    items = sync.delta(conn, account_id, 0)["changes"]["items"]
    return next(item for item in items if item["id"] == item_id)


def _row(conn, item_id):
    return conn.execute("SELECT * FROM items WHERE id = ?", (item_id,)).fetchone()


@pytest.fixture
def owner(db_conn):
    account_id = auth.register(db_conn, "a@example.com", PW)
    _apply(db_conn, account_id, lists=[_list()])
    return account_id


# ---- stored and served -----------------------------------------------------------------------


def test_a_due_date_is_stored_and_served_on_pull(db_conn, owner):
    _apply(db_conn, owner, items=[_item("i1", name=("Pay rent", 100), due=("2026-10-01", 100))])

    row = _row(db_conn, "i1")
    assert (row["due"], row["due_ts"], row["due_by"]) == ("2026-10-01", 100, "devA")
    assert _wire_item(db_conn, owner, "i1")["fields"]["due"] == _clock("2026-10-01", 100)


def test_an_item_that_never_had_a_due_date_is_served_with_a_null_one(db_conn, owner):
    _apply(db_conn, owner, items=[_item("i1", name=("Pay rent", 100))])

    assert _wire_item(db_conn, owner, "i1")["fields"]["due"] == {
        "value": None,
        "updated_at": 0,
        "updated_by": "",
    }


def test_null_clears_a_due_date(db_conn, owner):
    _apply(db_conn, owner, items=[_item("i1", name=("Pay rent", 100), due=("2026-10-01", 100))])
    _apply(db_conn, owner, items=[_item("i1", due=(None, 200))])

    assert _wire_item(db_conn, owner, "i1")["fields"]["due"] == _clock(None, 200)


def test_a_push_without_due_leaves_it_unchanged(db_conn, owner):
    # What an older client sends: only the fields it knows.
    _apply(db_conn, owner, items=[_item("i1", name=("Pay rent", 100), due=("2026-10-01", 100))])
    _apply(db_conn, owner, device="old", items=[_item("i1", name=("Pay the rent", 300, "old"))])

    fields = _wire_item(db_conn, owner, "i1")["fields"]
    assert fields["name"]["value"] == "Pay the rent"
    assert fields["due"] == _clock("2026-10-01", 100)


@pytest.mark.parametrize("kind", ["shopping", "checklist", "expenses"])
def test_due_is_accepted_on_every_list_kind(db_conn, kind):
    account_id = auth.register(db_conn, "k@example.com", PW)
    _apply(db_conn, account_id, lists=[_list("l", kind)])
    item = _item("i1", list_id="l", name=("Thing", 100), due=("2026-10-01", 100))
    if kind == "expenses":
        expense = {
            "paid_by": {account_id: "10"},
            "equal_by": False,
            "paid_for": {account_id: "10"},
            "equal_for": True,
            "date": "2026-09-28",
        }
        item["fields"]["expense"] = _clock(expense, 100)
    _apply(db_conn, account_id, items=[item])

    assert _row(db_conn, "i1")["due"] == "2026-10-01"


# ---- last-write-wins -------------------------------------------------------------------------


def test_a_newer_due_date_wins(db_conn, owner):
    _apply(db_conn, owner, items=[_item("i1", name=("Pay rent", 100), due=("2026-10-01", 100))])
    _apply(db_conn, owner, device="devB", items=[_item("i1", due=("2026-11-01", 200, "devB"))])

    assert _wire_item(db_conn, owner, "i1")["fields"]["due"] == _clock("2026-11-01", 200, "devB")


def test_a_stale_due_date_loses_silently(db_conn, owner):
    _apply(db_conn, owner, items=[_item("i1", name=("Pay rent", 100), due=("2026-10-01", 300))])
    _apply(db_conn, owner, device="devB", items=[_item("i1", due=(None, 200, "devB"))])

    assert _wire_item(db_conn, owner, "i1")["fields"]["due"] == _clock("2026-10-01", 300)


def test_a_name_merge_keeps_the_newest_due_date(db_conn, owner):
    _apply(db_conn, owner, items=[_item("a1", created_at=1000, name=("Rent", 100))])
    _apply(
        db_conn,
        owner,
        device="devB",
        items=[
            _item(
                "b1", created_at=2000, name=("rent", 110, "devB"), due=("2026-10-01", 110, "devB")
            )
        ],
    )

    survivor = _row(db_conn, "a1")
    assert survivor["deleted"] == 0
    assert (survivor["due"], survivor["due_ts"], survivor["due_by"]) == ("2026-10-01", 110, "devB")


# ---- validation ------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "value",
    [
        "2026-02-30",  # the right shape, not a day
        "2026-13-01",
        "20261001",  # ISO basic form, which date.fromisoformat accepts
        "2026-W40-4",  # ISO week date, ten characters, which date.fromisoformat accepts
        "2026-10-01T00:00",
        "2026-10-01Z",
        "2026-10-01\n",
        " 2026-10-01",
        "2026-1-01",
        "２０２６-１０-０１",  # full-width digits
        "",
        20261001,
        True,
        ["2026-10-01"],
        {"date": "2026-10-01"},
    ],
)
def test_anything_but_a_calendar_date_or_null_is_refused(db_conn, owner, value):
    with pytest.raises(ApiError) as excinfo:
        _apply(db_conn, owner, items=[_item("i1", name=("Pay rent", 100), due=(value, 100))])

    assert (excinfo.value.status, excinfo.value.code) == (422, "invalid_field")
    assert excinfo.value.details == {"row_id": "i1", "field": "due"}
    assert _row(db_conn, "i1") is None


def test_a_refused_due_date_names_the_row_on_the_wire(client):
    client.post("/api/v1/register", json={"email": "w@example.com", "password": PW})
    token = client.post(
        "/api/v1/login", json={"email": "w@example.com", "password": PW, "device_label": "devA"}
    ).get_json()["token"]
    resp = client.post(
        "/api/v1/sync",
        json={
            "cursor": 0,
            "device_id": "devA",
            "full_lists": [],
            "changes": {
                "lists": [_list()],
                "items": [_item("i1", name=("Pay rent", 100), due=("2026-02-30", 100))],
            },
        },
        headers={"Authorization": f"Bearer {token}"},
    )

    assert resp.status_code == 422
    body = resp.get_json()
    assert (body["error"], body["row_id"], body["field"]) == ("invalid_field", "i1", "due")
