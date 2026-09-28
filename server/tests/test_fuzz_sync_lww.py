"""A stateful test of /sync's field-level last-write-wins (T-326).

Two accounts share one list, each on its own device. Hypothesis drives them through pushes of
generated field clocks — values, timestamps (ties, zero and negative ones included) and authors,
explicit or left for the server to fill in from the device id — and pulls, in any order. A model
keeps, per field, the write the contract says must win: the greatest `(updated_at, updated_by)`,
strictly, so an equal clock never replaces what is stored. After every step:

- a full pull (cursor 0, a third device) equals the model, field by field and clock by clock:
  a stale write never won, and no winning one was lost;
- each device's cursor never moved backwards;
- a device that has just synced holds, from its incremental pulls alone, exactly what the full
  pull holds — no row was skipped by a cursor.

Item names are unique per item, so the same-name merge (which rewrites clocks on purpose) never
runs; the list is never deleted, so its items are never tombstoned by the server either; and
timestamps stay in the past, so clamping never applies. A row created by a push takes the clocks
it was pushed with, whatever they are; its other fields start at the default clock (0, ""). Those three have tests of their own.
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
ITEM_IDS = ["i0", "i1", "i2", "i3"]
DEVICES = ["dev-a", "dev-b"]

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
# Small, so ties are common; zero and negative ones meet the defaults' (0, "") clock.
_TIMESTAMPS = st.integers(min_value=-2, max_value=12)
# None leaves the author to the server, which uses the pushing device's id.
_AUTHORS = st.sampled_from([None, "", "dev-a", "dev-b", "dev-c"])


def _item_name(item_id, draw):
    """A name only this item can have, in either case, so no two items ever merge."""
    return draw(st.sampled_from([item_id, item_id.upper()])) + f"-{draw(st.integers(0, 2))}"


class _World:
    """Two accounts on one shared list, snapshotted once and restored for every run."""

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
        for device, email in zip(DEVICES, ["a@example.com", "b@example.com"], strict=True):
            response = self.client.post(
                "/api/v1/register", json={"email": email, "password": PASSWORD}
            )
            assert response.status_code == 201
            response = self.client.post(
                "/api/v1/login", json={"email": email, "password": PASSWORD}
            )
            self.tokens[device] = response.get_json()["token"]
        self.tokens["observer"] = self.tokens["dev-a"]
        self.initial_list = {**LIST_DEFAULTS, "name": "Shared"}
        response = self.sync(
            "dev-a",
            0,
            {
                "lists": [
                    {
                        "id": LIST_ID,
                        "fields": {
                            "name": {"value": "Shared", "updated_at": 0, "updated_by": "setup"}
                        },
                    }
                ]
            },
        )
        assert response.status_code == 200, response.get_json()
        response = self.client.post(
            f"/api/v1/lists/{LIST_ID}/invites",
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


class SyncLww(RuleBasedStateMachine):
    def __init__(self):
        super().__init__()
        self.world = _WORLD[0]
        self.world.restore()
        # What must be stored, per field, by the contract's rule.
        self.model = {
            ("lists", LIST_ID): {
                key: (value, 0, "setup" if key == "name" else "")
                for key, value in self.world.initial_list.items()
            }
        }
        self.cursors = dict.fromkeys(DEVICES, 0)
        self.replicas = {device: {} for device in DEVICES}

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
    def _apply_to_model(model, key, defaults, fields):
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

    # ---- rules -------------------------------------------------------------------------------

    @rule(device=st.sampled_from(DEVICES), data=st.data())
    def push(self, device, data):
        draw = data.draw
        rows = {"lists": [], "items": []}
        pending = copy.deepcopy(self.model)
        for _ in range(draw(st.integers(1, 3), label="rows")):
            if draw(st.booleans(), label="list row"):
                names = draw(st.sets(st.sampled_from(sorted(_LIST_VALUES)), min_size=1))
                fields = {name: draw(_LIST_VALUES[name]) for name in names}
                key, defaults, kind = ("lists", LIST_ID), LIST_DEFAULTS, "lists"
                row: dict = {"id": LIST_ID}
            else:
                item_id = draw(st.sampled_from(ITEM_IDS), label="item")
                names = draw(st.sets(st.sampled_from(sorted(_ITEM_VALUES) + ["name"])))
                key, defaults, kind = ("items", item_id), ITEM_DEFAULTS, "items"
                if key not in pending:
                    names.add("name")  # creating an item requires one
                fields = {
                    name: (
                        _item_name(item_id, draw) if name == "name" else draw(_ITEM_VALUES[name])
                    )
                    for name in names
                }
                row = {"id": item_id, "list_id": LIST_ID}
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
            self._apply_to_model(pending, key, defaults, expected)
        response = self.world.sync(device, self.cursors[device], rows)
        self.model = pending
        self._synced(device, response)

    @rule(device=st.sampled_from(DEVICES))
    def pull(self, device):
        self._synced(device, self.world.sync(device, self.cursors[device]))

    @rule(device=st.sampled_from(DEVICES))
    def pull_from_zero(self, device):
        """A device starting over (a reinstall) sees the same rows as one that never did."""
        self.replicas[device] = {}
        self.cursors[device] = 0
        self._synced(device, self.world.sync(device, 0))

    # ---- invariants --------------------------------------------------------------------------

    @invariant()
    def a_full_pull_equals_the_merged_state(self):
        """Every field holds the write with the greatest clock: a stale one never won, and a
        winning one was never lost."""
        assert self._full_pull() == self.model


TestSyncLww = SyncLww.TestCase
