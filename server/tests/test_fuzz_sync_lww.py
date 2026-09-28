"""A stateful test of /sync's field-level last-write-wins (T-326).

Two accounts share a shopping list and an expenses ledger, each on its own device. Hypothesis
drives them through pushes of generated field clocks — values, timestamps (ties, zero and
negative ones included) and authors, explicit or left for the server to fill in from the device
id — pulls, and close votes on the ledger, in any order. A model keeps, per field, the write the
contract says must win: the greatest `(updated_at, updated_by)`, strictly, so an equal clock never
replaces what is stored. After every step:

- a full pull (cursor 0, a third device) equals the model, field by field and clock by clock:
  a stale write never won, and no winning one was lost;
- each device's cursor never moved backwards;
- a device that has just synced holds, from its incremental pulls alone, exactly what the full
  pull holds — no row was skipped by a cursor.

On the ledger the model also knows the rules that refuse a row — an entry without an expense, a
participant who is not a member, a voter changing anything, a voter's amounts moved by someone
else, anything at all once the list is closed — and that every one but the last applies only to
a write that would win. A push the model expects to be refused must be a 422 naming that row,
with nothing of the batch applied; any other push must succeed. So a stale write is never
refused, and a refused one never lands.

Item names are unique per item, so the same-name merge (which rewrites clocks on purpose) never
runs; no list is ever deleted, so no item is tombstoned by the server; and timestamps stay in the
past, so clamping never applies. Those three have tests of their own. A row created by a push
takes the clocks it was pushed with, whatever they are; its other fields start at (0, "").
"""

import copy
import sqlite3

import pytest
from flask import Flask
from hypothesis import strategies as st
from hypothesis.stateful import RuleBasedStateMachine, invariant, rule

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module

PASSWORD = "correct horse battery"
LIST_ID = "shared-list"
LEDGER_ID = "shared-ledger"
ITEM_IDS = ["i0", "i1", "i2", "i3"]
ENTRY_IDS = ["e0", "e1", "e2"]
DEVICES = ["dev-a", "dev-b"]
STRANGER = "stranger-account"

# Defaults a new row gets for every field it was not created with: clock (0, "").
ITEM_DEFAULTS = {
    "name": "",
    "category": None,
    "stores": [],
    "quantity": None,
    "price": None,
    "note": None,
    "due": None,
    "status": "todo",
    "expense": None,
    "deleted": False,
}
LIST_DEFAULTS = {
    "name": "",
    "category_order": [],
    "notes": None,
    "kind": "shopping",
    "currency": None,
    "deleted": False,
}

_SHORT = st.text(st.sampled_from("abcXYZ é😀"), max_size=4)
_ITEM_VALUES = {
    "category": st.none() | _SHORT,
    "stores": st.lists(_SHORT, max_size=3),
    "quantity": st.none() | _SHORT,
    "price": st.none()
    | st.fixed_dictionaries(
        {"amount": st.sampled_from(["0", "1.5", "2.99"]), "currency": st.none() | st.just("EUR")}
    ),
    "note": st.none() | _SHORT,
    "due": st.none() | st.sampled_from(["2026-10-01", "2027-02-28"]),
    "status": st.sampled_from(["backlog", "todo", "checked"]),
    "deleted": st.booleans(),
}
_LIST_VALUES = {
    "name": _SHORT.map(lambda s: f"List {s}"),
    "category_order": st.lists(_SHORT, max_size=3),
    "notes": st.none() | _SHORT,
    "kind": st.sampled_from(["shopping", "checklist"]),
    "currency": st.none() | _SHORT,
}
_LEDGER_VALUES = {
    "name": _SHORT.map(lambda s: f"Trip {s}"),
    "notes": st.none() | _SHORT,
}
# Small, so ties are common; zero and negative ones meet the defaults' (0, "") clock.
_TIMESTAMPS = st.integers(min_value=-2, max_value=12)
# None leaves the author to the server, which uses the pushing device's id.
_AUTHORS = st.sampled_from([None, "", "dev-a", "dev-b", "dev-c"])


def _item_name(item_id, draw):
    """A name only this item can have, in either case, so no two items ever merge."""
    return draw(st.sampled_from([item_id, item_id.upper()])) + f"-{draw(st.integers(0, 2))}"


def _entry(entry_type, paid_by, paid_for):
    return {
        "type": entry_type,
        "paid_by": paid_by,
        "equal_by": False,
        "paid_for": paid_for,
        "equal_for": len(set(paid_for.values())) == 1,
        "date": "2026-09-17",
    }


def _expenses(a, b):
    """Ledger entries between accounts `a` and `b`: valid ones, one naming a stranger, and null."""
    return [
        _entry("expense", {a: "10.00"}, {a: "5.00", b: "5.00"}),
        _entry("expense", {b: "2.50"}, {b: "2.50"}),
        _entry("income", {a: "8.00"}, {a: "4.00", b: "4.00"}),
        _entry("transfer", {a: "5.00"}, {b: "5.00"}),
        _entry("expense", {a: "1.00"}, {STRANGER: "1.00"}),
        None,
    ]


def _signed_cents(expense):
    """{account: (paid, owed)} in cents with the type's sign — the freeze's view of an entry,
    computed here independently of the server's own."""
    if expense is None:
        return {}
    sign = -1 if expense["type"] == "income" else 1
    amounts = {}
    for index, key in enumerate(("paid_by", "paid_for")):
        for account, amount in expense[key].items():
            pair = amounts.setdefault(account, [0, 0])
            pair[index] += sign * round(float(amount) * 100)
    return {account: tuple(pair) for account, pair in amounts.items()}


class _World:
    """Two accounts on a shared list and a shared ledger, snapshotted once and restored for
    every run."""

    def __init__(self, tmp_path):
        self.db_path = str(tmp_path / "lww.db")
        conn = db_module.connect(self.db_path)
        db_module.init_db(conn)
        conn.close()
        app = Flask(__name__)
        app.config["TESTING"] = True
        app.register_blueprint(
            create_blueprint(
                database_path=self.db_path,
                invite_hmac_key=b"lww-hmac-key",
                base_url="http://testserver",
                serve_web_client=False,
                serve_invite_landing_page=False,
                serve_android_apk=False,
            )
        )
        self.client = app.test_client()
        self.tokens = {}
        self.accounts = {}
        for device, email in zip(DEVICES, ["a@example.com", "b@example.com"], strict=True):
            response = self.client.post(
                "/api/v1/register", json={"email": email, "password": PASSWORD}
            )
            assert response.status_code == 201
            self.accounts[device] = response.get_json()["account_id"]
            response = self.client.post(
                "/api/v1/login", json={"email": email, "password": PASSWORD}
            )
            self.tokens[device] = response.get_json()["token"]
        self.tokens["observer"] = self.tokens["dev-a"]
        self.initial = {
            LIST_ID: {**LIST_DEFAULTS, "name": "Shared"},
            LEDGER_ID: {**LIST_DEFAULTS, "name": "Trip", "kind": "expenses", "currency": "EUR"},
        }
        setup_clock = {"updated_at": 0, "updated_by": "setup"}
        response = self.sync(
            "dev-a",
            0,
            {
                "lists": [
                    {
                        "id": LIST_ID,
                        "fields": {"name": {"value": "Shared", **setup_clock}},
                    },
                    {
                        "id": LEDGER_ID,
                        "fields": {
                            "name": {"value": "Trip", **setup_clock},
                            "kind": {"value": "expenses", **setup_clock},
                            "currency": {"value": "EUR", **setup_clock},
                        },
                    },
                ]
            },
        )
        assert response.status_code == 200, response.get_json()
        for list_id in (LIST_ID, LEDGER_ID):
            response = self.client.post(
                f"/api/v1/lists/{list_id}/invites",
                json={"invited_email": "b@example.com"},
                headers=self.auth("dev-a"),
            )
            token = response.get_json()["token"]
            response = self.client.post(
                "/api/v1/invites/redeem", json={"token": token}, headers=self.auth("dev-b")
            )
            assert response.status_code == 200, response.get_json()
        self.snapshot = sqlite3.connect(":memory:")
        source = sqlite3.connect(self.db_path)
        source.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        source.backup(self.snapshot)
        source.close()

    def auth(self, device):
        return {"Authorization": f"Bearer {self.tokens[device]}"}

    def restore(self):
        target = sqlite3.connect(self.db_path)
        self.snapshot.backup(target)
        target.close()

    def sync(self, device, cursor, changes=None):
        body = {"cursor": cursor, "device_id": device}
        if changes is not None:
            body["changes"] = changes
        return self.client.post("/api/v1/sync", json=body, headers=self.auth(device))


_WORLD: list[_World] = []


@pytest.fixture(scope="module", autouse=True)
def _lww_world(tmp_path_factory, fast_password_hashing):
    _WORLD.append(_World(tmp_path_factory.mktemp("fuzz_lww")))
    yield
    _WORLD.clear()


def _state(rows):
    """{(kind, id): {field: (value, updated_at, updated_by)}} from a response's changes."""
    state = {}
    for kind in ("lists", "items"):
        for row in rows[kind]:
            state[(kind, row["id"])] = {
                key: (clock["value"], clock["updated_at"], clock["updated_by"])
                for key, clock in row["fields"].items()
            }
    return state


def _wins(incoming, stored, name):
    """Whether a pushed field's clock beats the stored one: strictly, and always on a new row."""
    return stored is None or (name in incoming and incoming[name][1:] > stored[name][1:])


class Refused(Exception):
    """The model's prediction that the server refuses this row with this code."""

    def __init__(self, code, row_id):
        super().__init__(code)
        self.code = code
        self.row_id = row_id


class SyncLww(RuleBasedStateMachine):
    def __init__(self):
        super().__init__()
        self.world = _WORLD[0]
        self.world.restore()
        self.account = self.world.accounts
        # What must be stored, per field, by the contract's rule.
        self.model = {
            ("lists", list_id): {
                key: (value, 0, "setup" if value != LIST_DEFAULTS[key] else "")
                for key, value in initial.items()
            }
            for list_id, initial in self.world.initial.items()
        }
        self.cursors = dict.fromkeys(DEVICES, 0)
        self.replicas = {device: {} for device in DEVICES}
        self.voters = set()  # account ids with a close vote on the ledger
        self.closed = False

    # ---- helpers -----------------------------------------------------------------------------

    def _full_pull(self):
        response = self.world.sync("observer", 0)
        assert response.status_code == 200, response.get_json()
        return _state(response.get_json()["changes"])

    def _synced(self, device, response):
        assert response.status_code == 200, response.get_json()
        body = response.get_json()
        assert body["cursor"] >= self.cursors[device], "the cursor moved backwards"
        self.cursors[device] = body["cursor"]
        self.replicas[device].update(_state(body["changes"]))
        assert (
            self.replicas[device] == self._full_pull()
        ), f"{device}'s incremental pulls hold something other than a full pull"

    @staticmethod
    def _apply(model, key, defaults, fields):
        """The contract's rule for one pushed row. A new row takes every pushed field as it is —
        there is nothing stored to compare with — and the defaults, at clock (0, ""), for the
        rest; an existing one takes a pushed field only if its clock is strictly greater."""
        stored = model.get(key)
        if stored is None:
            model[key] = {name: (value, 0, "") for name, value in defaults.items()} | fields
            return
        for name, incoming in fields.items():
            if incoming[1:] > stored[name][1:]:
                stored[name] = incoming

    def _check_ledger_list(self, model, account, fields):
        """The rules on a push of the ledger's own row."""
        if self.closed:
            raise Refused("list_closed", LEDGER_ID)
        stored = model[("lists", LEDGER_ID)]
        if account in self.voters and any(_wins(fields, stored, name) for name in fields):
            raise Refused("voted_to_close", LEDGER_ID)

    def _check_entry(self, model, account, entry_id, fields):
        """The rules on a ledger entry, in the order the contract gives them."""
        if self.closed:
            raise Refused("list_closed", entry_id)
        stored = model.get(("items", entry_id))
        members = set(self.account.values())
        expense_wins = "expense" in fields and _wins(fields, stored, "expense")
        if stored is None and "expense" not in fields:
            raise Refused("invalid_expense", entry_id)
        if expense_wins:
            value = fields["expense"][0]
            if value is None:
                raise Refused("invalid_expense", entry_id)
            present = set()
            if stored is not None and stored["expense"][0] is not None:
                present = set(stored["expense"][0]["paid_by"]) | set(
                    stored["expense"][0]["paid_for"]
                )
            if (set(value["paid_by"]) | set(value["paid_for"])) - members - present:
                raise Refused("invalid_expense", entry_id)
        if account in self.voters and any(_wins(fields, stored, name) for name in fields):
            raise Refused("voted_to_close", entry_id)
        # The freeze: a deleted entry counts as no entry.
        was_deleted = stored is not None and stored["deleted"][0]
        before = None if stored is None or was_deleted else stored["expense"][0]
        after = fields["expense"][0] if expense_wins else (stored and stored["expense"][0])
        deleted_after = (
            fields["deleted"][0]
            if "deleted" in fields and _wins(fields, stored, "deleted")
            else was_deleted
        )
        after = None if deleted_after else after
        before_cents, after_cents = _signed_cents(before), _signed_cents(after)
        type_changed = bool(before and after) and before["type"] != after["type"]
        frozen = self.voters | ((set(before_cents) | set(after_cents)) - members)
        for participant in set(before_cents) | set(after_cents):
            moved = before_cents.get(participant, (0, 0)) != after_cents.get(participant, (0, 0))
            if (moved or type_changed) and participant in frozen:
                raise Refused("participant_frozen", entry_id)

    # ---- rules -------------------------------------------------------------------------------

    @rule(device=st.sampled_from(DEVICES), data=st.data())
    def push(self, device, data):
        draw = data.draw
        account = self.account[device]
        a, b = self.account["dev-a"], self.account["dev-b"]
        rows = {"lists": [], "items": []}
        # (row id, list, defaults, fields as the model stores them) per kind: the server applies
        # every list row first, then every item, and so does the model.
        planned = {"lists": [], "items": []}
        known = set(self.model)
        for _ in range(draw(st.integers(1, 3), label="rows")):
            target = draw(st.sampled_from(["list", "ledger", "item", "entry"]), label="target")
            if target in ("list", "ledger"):
                list_id = LIST_ID if target == "list" else LEDGER_ID
                values = _LIST_VALUES if target == "list" else _LEDGER_VALUES
                names = draw(st.sets(st.sampled_from(sorted(values)), min_size=1))
                fields = {name: draw(values[name]) for name in names}
                kind, row_id, defaults = "lists", list_id, LIST_DEFAULTS
                row: dict = {"id": list_id}
            else:
                on_ledger = target == "entry"
                row_id = draw(st.sampled_from(ENTRY_IDS if on_ledger else ITEM_IDS), label="id")
                list_id = LEDGER_ID if on_ledger else LIST_ID
                kind, defaults = "items", ITEM_DEFAULTS
                if on_ledger:
                    choices = ["name", "note", "expense", "deleted"]
                    values = {
                        "note": _ITEM_VALUES["note"],
                        "deleted": _ITEM_VALUES["deleted"],
                        "expense": st.sampled_from(_expenses(a, b)),
                    }
                else:
                    choices = sorted(_ITEM_VALUES) + ["name"]
                    values = _ITEM_VALUES
                names = draw(st.sets(st.sampled_from(choices)))
                if ("items", row_id) not in known:
                    names.add("name")  # creating an item requires one
                    if on_ledger and draw(st.integers(0, 4), label="without expense"):
                        names.add("expense")  # and an entry one, which is left out now and then
                fields = {
                    name: (_item_name(row_id, draw) if name == "name" else draw(values[name]))
                    for name in names
                }
                row = {"id": row_id, "list_id": list_id}
            known.add((kind, row_id))
            clocks = {}
            expected = {}
            for name, value in fields.items():
                ts = draw(_TIMESTAMPS, label="updated_at")
                by = draw(_AUTHORS, label="updated_by")
                clocks[name] = {"value": value, "updated_at": ts}
                if by is not None:
                    clocks[name]["updated_by"] = by
                expected[name] = (value, ts, by or device)
            row["fields"] = clocks
            rows[kind].append(row)
            planned[kind].append((row_id, list_id, defaults, expected))

        pending = copy.deepcopy(self.model)
        refused = None
        try:
            for kind in ("lists", "items"):
                for row_id, list_id, defaults, expected in planned[kind]:
                    if kind == "lists" and list_id == LEDGER_ID:
                        self._check_ledger_list(pending, account, expected)
                    elif kind == "items" and list_id == LEDGER_ID:
                        self._check_entry(pending, account, row_id, expected)
                    self._apply(pending, (kind, row_id), defaults, expected)
        except Refused as exc:
            refused = exc

        response = self.world.sync(device, self.cursors[device], rows)
        if refused is None:
            self.model = pending
            self._synced(device, response)
        else:
            body = response.get_json()
            assert response.status_code == 422, (refused.code, body)
            assert (body["error"], body.get("row_id")) == (refused.code, refused.row_id), body

    @rule(device=st.sampled_from(DEVICES))
    def pull(self, device):
        self._synced(device, self.world.sync(device, self.cursors[device]))

    @rule(device=st.sampled_from(DEVICES))
    def pull_from_zero(self, device):
        """A device starting over (a reinstall) sees the same rows as one that never did."""
        self.replicas[device] = {}
        self.cursors[device] = 0
        self._synced(device, self.world.sync(device, 0))

    @rule(device=st.sampled_from(DEVICES), agree=st.booleans(), close=st.integers(0, 3))
    def vote(self, device, agree, close):
        """Agree to close the ledger, or withdraw; it closes when both have agreed. The closing
        vote is cast only one time in four: a closed ledger refuses everything, and the rules
        worth exploring are those of an open one with a voter on it."""
        if (
            agree
            and not self.closed
            and self.voters | {self.account[device]} == set(self.account.values())
        ):
            if close:
                return
        response = self.world.client.open(
            f"/api/v1/lists/{LEDGER_ID}/close-votes",
            method="POST" if agree else "DELETE",
            headers=self.world.auth(device),
        )
        if self.closed:
            assert response.status_code == 409, response.get_json()
            return
        assert response.status_code == 200, response.get_json()
        if agree:
            self.voters.add(self.account[device])
        else:
            self.voters.discard(self.account[device])
        self.closed = self.voters == set(self.account.values())
        assert (response.get_json()["closed_at"] is not None) == self.closed

    # ---- invariants --------------------------------------------------------------------------

    @invariant()
    def a_full_pull_equals_the_merged_state(self):
        """Every field holds the write with the greatest clock: a stale one never won, a winning
        one was never lost, and a refused push left nothing behind."""
        assert self._full_pull() == self.model


TestSyncLww = SyncLww.TestCase
