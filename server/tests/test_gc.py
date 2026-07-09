"""Tests for tombstone GC (S8)."""

from shoppinglist_server import auth, gc, invites, sync
from shoppinglist_server.errors import ApiError
import pytest

PW = "password123"
KEY = b"test-invite-hmac-key"
BASE_URL = "http://testserver"
NINETY_DAYS_MS = 90 * 24 * 60 * 60 * 1000
NOW = 10_000_000_000_000  # arbitrary but comfortably larger than any retention window


def _register(conn, email):
    return auth.register(conn, email, PW)


def _create_list(conn, account_id, device, list_id="list-1", name="Groceries"):
    sync.apply_changes(
        conn, account_id, device,
        {"lists": [{"id": list_id, "created_at": 1000,
                   "fields": {"name": {"value": name, "updated_at": 100, "updated_by": device}}}]},
    )


def _create_item(conn, account_id, device, item_id, list_id="list-1", name="Milk"):
    sync.apply_changes(
        conn, account_id, device,
        {"items": [{"id": item_id, "list_id": list_id, "created_at": 1000,
                   "fields": {"name": {"value": name, "updated_at": 100, "updated_by": device}}}]},
    )


def _delete_item(conn, account_id, device, item_id, list_id, ts):
    sync.apply_changes(
        conn, account_id, device,
        {"items": [{"id": item_id, "list_id": list_id,
                   "fields": {"deleted": {"value": True, "updated_at": ts, "updated_by": device}}}]},
    )


def _item_exists(conn, item_id):
    return conn.execute("SELECT 1 FROM items WHERE id = ?", (item_id,)).fetchone() is not None


def _list_exists(conn, list_id):
    return conn.execute("SELECT 1 FROM lists WHERE id = ?", (list_id,)).fetchone() is not None


def _membership_exists(conn, account_id, list_id):
    return conn.execute(
        "SELECT 1 FROM memberships WHERE account_id = ? AND list_id = ?", (account_id, list_id)
    ).fetchone() is not None


def _meta(conn):
    return conn.execute("SELECT * FROM meta WHERE id = 1").fetchone()


# ---- basic retention ---------------------------------------------------------


def test_fresh_tombstone_survives(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "dev")
    _create_item(db_conn, account_id, "dev", "item-1")
    _delete_item(db_conn, account_id, "dev", "item-1", "list-1", ts=NOW - 1000)  # 1s ago

    result = gc.run(db_conn, NOW)

    assert result == {"items_purged": 0, "lists_purged": 0}
    assert _item_exists(db_conn, "item-1")


def test_91_day_old_item_tombstone_is_purged(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "dev")
    _create_item(db_conn, account_id, "dev", "item-1")
    _delete_item(db_conn, account_id, "dev", "item-1", "list-1", ts=NOW - (91 * 24 * 60 * 60 * 1000))

    result = gc.run(db_conn, NOW)

    assert result["items_purged"] == 1
    assert not _item_exists(db_conn, "item-1")
    # Its list is untouched (still alive, not tombstoned).
    assert _list_exists(db_conn, "list-1")


def test_89_day_old_tombstone_survives(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "dev")
    _create_item(db_conn, account_id, "dev", "item-1")
    _delete_item(db_conn, account_id, "dev", "item-1", "list-1", ts=NOW - (89 * 24 * 60 * 60 * 1000))

    result = gc.run(db_conn, NOW)

    assert result == {"items_purged": 0, "lists_purged": 0}
    assert _item_exists(db_conn, "item-1")


# ---- gc_horizon + 410 (ties S4) ----------------------------------------------


def test_gc_horizon_advances_and_stale_cursor_then_gets_410(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "dev")
    _create_item(db_conn, account_id, "dev", "item-1")
    baseline_cursor = sync.delta(db_conn, account_id, cursor=0, full_lists=[])["cursor"]
    _delete_item(db_conn, account_id, "dev", "item-1", "list-1", ts=NOW - (91 * 24 * 60 * 60 * 1000))

    gc.run(db_conn, NOW)

    horizon = _meta(db_conn)["gc_horizon"]
    assert horizon >= baseline_cursor

    with pytest.raises(ApiError) as excinfo:
        sync.check_cursor(db_conn, baseline_cursor)
    assert excinfo.value.status == 410
    assert excinfo.value.code == "full_resync_required"


# ---- orphaned list cascade (ties T-6's note re: memberships FK) -------------


def test_orphaned_list_and_lingering_membership_purged_together(db_conn):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    _create_item(db_conn, account_id, "devA", "item-1")

    # Sole-member leave orphans the list; invites.leave deliberately KEEPS the
    # membership row so sibling devices see the tombstone (T-6). Back-date it
    # so it's eligible for purge.
    invites.leave(db_conn, account_id, "list-1")
    old_ts = NOW - (91 * 24 * 60 * 60 * 1000)
    db_conn.execute(
        "UPDATE lists SET deleted_ts = ? WHERE id = ?", (old_ts, "list-1")
    )
    db_conn.execute(
        "UPDATE items SET deleted_ts = ? WHERE list_id = ?", (old_ts, "list-1")
    )
    db_conn.commit()
    assert _membership_exists(db_conn, account_id, "list-1")  # precondition

    result = gc.run(db_conn, NOW)  # must not raise an FK IntegrityError

    assert result == {"items_purged": 1, "lists_purged": 1}
    assert not _list_exists(db_conn, "list-1")
    assert not _item_exists(db_conn, "item-1")
    assert not _membership_exists(db_conn, account_id, "list-1")


def _invite_exists(conn, invite_id):
    return conn.execute("SELECT 1 FROM invites WHERE id = ?", (invite_id,)).fetchone() is not None


def test_purging_a_list_also_removes_invites_referencing_it(db_conn):
    # invites.list_id is also a FK to lists(id): a used/expired/revoked
    # invite still references the list until GC removes it too, or the list
    # delete itself would violate the FK (caught by test_full_system.py first).
    owner = _register(db_conn, "owner@example.com")
    invitee = _register(db_conn, "invitee@example.com")
    _create_list(db_conn, owner, "devA")
    _create_item(db_conn, owner, "devA", "item-1")
    minted = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)
    invitee_account = auth.Account(id=invitee, email="invitee@example.com")
    invites.redeem(db_conn, KEY, invitee_account, minted["token"])
    assert _invite_exists(db_conn, minted["invite_id"])  # precondition

    invites.leave(db_conn, owner, "list-1")  # invitee remains -> list survives
    invites.leave(db_conn, invitee, "list-1")  # last member -> orphans
    old_ts = NOW - (91 * 24 * 60 * 60 * 1000)
    db_conn.execute("UPDATE lists SET deleted_ts = ? WHERE id = 'list-1'", (old_ts,))
    db_conn.execute("UPDATE items SET deleted_ts = ? WHERE list_id = 'list-1'", (old_ts,))
    db_conn.commit()

    result = gc.run(db_conn, NOW)  # must not raise an FK IntegrityError

    assert result == {"items_purged": 1, "lists_purged": 1}
    assert not _list_exists(db_conn, "list-1")
    assert not _invite_exists(db_conn, minted["invite_id"])


def test_purging_a_list_removes_its_items_even_if_not_independently_old(db_conn):
    # Defense in depth: an item under a purged list is removed unconditionally,
    # not only when its own tombstone independently clears the age cutoff.
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "devA")
    _create_item(db_conn, account_id, "devA", "item-1")
    invites.leave(db_conn, account_id, "list-1")  # orphans: list + item tombstoned together
    old_ts = NOW - (91 * 24 * 60 * 60 * 1000)
    db_conn.execute("UPDATE lists SET deleted_ts = ? WHERE id = ?", (old_ts, "list-1"))
    # Deliberately do NOT back-date the item's own deleted_ts (simulates any
    # skew between list and item tombstone timestamps).
    db_conn.commit()

    result = gc.run(db_conn, NOW)

    assert result == {"items_purged": 1, "lists_purged": 1}
    assert not _item_exists(db_conn, "item-1")


# ---- maybe_run ----------------------------------------------------------------


def test_maybe_run_noops_within_24h(db_conn, monkeypatch):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "dev")
    _create_item(db_conn, account_id, "dev", "item-1")
    _delete_item(db_conn, account_id, "dev", "item-1", "list-1", ts=NOW - (91 * 24 * 60 * 60 * 1000))
    db_conn.execute("UPDATE meta SET last_gc_at = ? WHERE id = 1", (NOW - 1000,))  # 1s ago
    db_conn.commit()

    monkeypatch.setattr(gc, "_current_now_ms", lambda: NOW)
    gc.maybe_run(db_conn)

    assert _item_exists(db_conn, "item-1")  # GC did not run


def test_maybe_run_runs_after_24h(db_conn, monkeypatch):
    account_id = _register(db_conn, "a@example.com")
    _create_list(db_conn, account_id, "dev")
    _create_item(db_conn, account_id, "dev", "item-1")
    _delete_item(db_conn, account_id, "dev", "item-1", "list-1", ts=NOW - (91 * 24 * 60 * 60 * 1000))
    day = 24 * 60 * 60 * 1000
    db_conn.execute("UPDATE meta SET last_gc_at = ? WHERE id = 1", (NOW - day - 1,))
    db_conn.commit()

    monkeypatch.setattr(gc, "_current_now_ms", lambda: NOW)
    gc.maybe_run(db_conn)

    assert not _item_exists(db_conn, "item-1")  # GC ran
    assert _meta(db_conn)["last_gc_at"] == NOW


# ---- opportunistic hook wired into POST /sync --------------------------------


def test_sync_endpoint_triggers_opportunistic_gc(client, app, monkeypatch):
    resp = client.post(
        "/api/v1/register", json={"email": "gchttp@example.com", "password": PW}
    )
    resp = client.post(
        "/api/v1/login",
        json={"email": "gchttp@example.com", "password": PW, "device_label": "dev"},
    )
    token = resp.get_json()["token"]

    resp = client.post(
        "/api/v1/sync",
        json={"cursor": 0, "device_id": "dev", "full_lists": [], "changes": {
            "lists": [{"id": "list-http", "fields": {
                "name": {"value": "Groceries", "updated_at": 100, "updated_by": "dev"}
            }}],
            "items": [{"id": "item-http", "list_id": "list-http", "fields": {
                "name": {"value": "Milk", "updated_at": 100, "updated_by": "dev"}
            }}],
        }},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200

    from shoppinglist_server import db as db_module
    from shoppinglist_server import get_config_by_name

    config = get_config_by_name(app)
    conn = db_module.connect(config["database_path"])
    old_ts = NOW - (91 * 24 * 60 * 60 * 1000)
    conn.execute(
        "UPDATE items SET deleted = 1, deleted_ts = ? WHERE id = 'item-http'", (old_ts,)
    )
    conn.execute("UPDATE meta SET last_gc_at = 0")  # force opportunistic GC eligible
    conn.commit()
    conn.close()

    from shoppinglist_server.routes import sync as sync_route

    monkeypatch.setattr(sync_route.gc, "_current_now_ms", lambda: NOW)

    resp = client.post(
        "/api/v1/sync",
        json={"cursor": 0, "device_id": "dev", "full_lists": [], "changes": {}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200

    conn = db_module.connect(config["database_path"])
    assert not _item_exists(conn, "item-http")  # opportunistic GC purged it
    conn.close()


# ---- CLI -----------------------------------------------------------------------


def test_gc_cli_runs(cli_runner, app, monkeypatch):
    from shoppinglist_server import db as db_module
    from shoppinglist_server import get_config_by_name

    config = get_config_by_name(app)

    conn = db_module.connect(config["database_path"])
    account_id = _register(conn, "clitest@example.com")
    _create_list(conn, account_id, "dev")
    _create_item(conn, account_id, "dev", "item-1")
    _delete_item(conn, account_id, "dev", "item-1", "list-1", ts=NOW - (91 * 24 * 60 * 60 * 1000))
    conn.commit()  # apply_changes() never commits; the /sync route normally would
    conn.close()

    from shoppinglist_server import cli as cli_module

    monkeypatch.setattr(cli_module, "now_ms", lambda: NOW)

    result = cli_runner.invoke(args=["shoppinglist", "gc"])
    assert result.exit_code == 0
    assert "1" in result.output  # purged count surfaced

    conn = db_module.connect(config["database_path"])
    assert not _item_exists(conn, "item-1")
    conn.close()
