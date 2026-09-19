"""Housekeeping: the invariant audit, its one repair, and when the sweep runs (T-218).

Two things are being pinned down here.

The **audit** must fire on every invariant it claims to check and must stay
silent on the legitimate look-alike of each. The look-alikes are the point: the
rows that linger on a *tombstoned* list — the departing member's membership, a
close vote of theirs, the list's dead invites — are `invites.leave`'s documented
design, kept so sibling devices converge on the tombstone, and reporting them
would make the audit cry wolf on every healthy server. Every test below starts
from `_baseline`, which contains exactly those look-alikes, and asserts the
audit is quiet on it before adding a single violating row.

The **trigger** must fire on the first authenticated request of a server run and
about weekly after that, from any authenticated request rather than only from
`POST /sync`, and must never be able to fail the request it rides on.

Several checks guard states that FOREIGN KEY constraints make unreachable — see
`_without_foreign_keys` below, which is how those rows get built at all. That is
not a flaw in the test: it is the reason those checks exist. They are a safety
net for the day some future change makes the state reachable (a PRAGMA that
stops being set, a table created without its REFERENCES clause, a restore from a
dump), which is exactly when nobody will be looking.
"""

import contextlib
import logging
import sqlite3

import pytest

from shoppinglist_server import audit as audit_log
from shoppinglist_server import auth, closing
from shoppinglist_server import db as db_module
from shoppinglist_server import gc, get_config_by_name, housekeeping, invites, sync

PW = "password123"
KEY = b"test-invite-hmac-key"
BASE_URL = "http://testserver"
DAY_MS = 24 * 60 * 60 * 1000
NOW = 10_000_000_000_000  # as in test_gc.py: comfortably past any retention window
GHOST = "no-such-account"
GHOST_LIST = "no-such-list"


# ---- building a database by hand ---------------------------------------------


@contextlib.contextmanager
def _without_foreign_keys(conn):
    """Insert rows SQLite would otherwise refuse.

    `db.connect()` sets `PRAGMA foreign_keys = ON` and every referencing column
    is declared REFERENCES, so a dangling reference cannot be created through
    any code path in this server — which is precisely why the audit's
    dangling-reference checks are a safety net rather than a live repair, and
    why a test has to reach under the constraint to exercise them at all.
    """
    conn.commit()  # the pragma is a no-op inside a transaction
    conn.execute("PRAGMA foreign_keys = OFF")
    try:
        yield conn
    finally:
        conn.commit()
        conn.execute("PRAGMA foreign_keys = ON")


def _account(conn, account_id):
    conn.execute(
        "INSERT INTO accounts (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
        (account_id, f"{account_id}@example.com", "x", NOW),
    )


def _list(conn, list_id, kind="shopping", deleted=0, deleted_ts=0):
    conn.execute(
        "INSERT INTO lists (id, created_at, change_seq, name, name_ts, name_by, kind, "
        "deleted, deleted_ts, deleted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (list_id, NOW, 1, list_id, NOW, "dev", kind, deleted, deleted_ts, "dev"),
    )


def _item(conn, item_id, list_id, deleted=0, deleted_ts=0):
    conn.execute(
        "INSERT INTO items (id, list_id, created_at, change_seq, name, name_ts, name_by, "
        "status_ts, status_by, deleted, deleted_ts, deleted_by) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (item_id, list_id, NOW, 1, item_id, NOW, "dev", NOW, "dev", deleted, deleted_ts, "dev"),
    )


def _membership(conn, account_id, list_id):
    conn.execute(
        "INSERT INTO memberships (account_id, list_id, joined_at) VALUES (?, ?, ?)",
        (account_id, list_id, NOW),
    )


def _vote(conn, list_id, account_id):
    conn.execute(
        "INSERT INTO close_votes (list_id, account_id, voted_at) VALUES (?, ?, ?)",
        (list_id, account_id, NOW),
    )


def _invite(conn, invite_id, list_id, created_by, expires_at=NOW + DAY_MS, used_at=None):
    conn.execute(
        "INSERT INTO invites (id, list_id, invited_email, created_by, created_at, expires_at, "
        "revoked, used_at) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
        (invite_id, list_id, "invitee@example.com", created_by, NOW, expires_at, used_at),
    )


def _token(conn, token_id, account_id):
    conn.execute(
        "INSERT INTO auth_tokens (id, token_hash, account_id, device_label, created_at, "
        "last_seen_at) VALUES (?, ?, ?, ?, ?, ?)",
        (token_id, f"hash-{token_id}", account_id, "dev", NOW, NOW),
    )


def _settings(conn, account_id):
    conn.execute(
        "INSERT INTO account_settings (account_id, default_currency, updated_at) "
        "VALUES (?, ?, ?)",
        (account_id, "EUR", NOW),
    )


def _baseline(conn):
    """A healthy database that contains a legitimate look-alike of every check.

    Most importantly it contains the lingering-rows-on-a-tombstoned-list state:
    `t1` is tombstoned but still inside its retention window, and it keeps a
    departed member's membership row, a close vote, and a long-dead invite —
    all of which `invites.leave` and `gc.run` leave there on purpose, and none
    of which is a violation. `t1`'s vote is by an account that is not a member
    of it, which goes one step further than any current code path does, because
    that is the shape the `close_votes_by_non_member` check has to stay quiet
    about on a dead list.
    """
    _account(conn, "a1")
    _account(conn, "a2")
    _settings(conn, "a1")
    _token(conn, "tok-1", "a1")

    _list(conn, "l1")  # live shopping list, two members, one live item
    _membership(conn, "a1", "l1")
    _membership(conn, "a2", "l1")
    _item(conn, "i1", "l1")
    _invite(conn, "inv-live", "l1", "a1")

    _list(conn, "e1", kind="expenses")  # live expenses list with a member's close vote
    _membership(conn, "a1", "e1")
    _vote(conn, "e1", "a1")

    # Tombstoned, inside retention. Everything hanging off it is the documented
    # lingering state, not a defect.
    _list(conn, "t1", kind="expenses", deleted=1, deleted_ts=NOW - 1000)
    _membership(conn, "a1", "t1")
    _item(conn, "i2", "t1", deleted=1, deleted_ts=NOW - 1000)
    _vote(conn, "t1", "a2")
    # Long past its grace window: what keeps it out of the findings is that its
    # list is tombstoned, not its age.
    _invite(conn, "inv-dead", "t1", "a1", expires_at=NOW - gc.INVITE_GRACE_MS - 1000)
    conn.commit()


def _checks(findings):
    return [finding.check for finding in findings]


# ---- the baseline is quiet ----------------------------------------------------


def test_a_healthy_database_has_no_findings(db_conn):
    _baseline(db_conn)

    assert housekeeping.audit(db_conn, NOW) == []


def test_lingering_rows_on_a_tombstoned_list_are_not_reported(db_conn):
    """The same thing again, but reached through the real API rather than by
    hand — so it keeps holding if `invites.leave` ever changes what it leaves
    behind."""
    owner = auth.register(db_conn, "owner@example.com", PW)
    joiner = auth.register(db_conn, "joiner@example.com", PW)
    sync.apply_changes(
        db_conn,
        owner,
        "dev",
        {
            "lists": [
                {
                    "id": "shared",
                    "fields": {
                        "name": {"value": "Trip", "updated_at": 100, "updated_by": "dev"},
                        "kind": {"value": "expenses", "updated_at": 100, "updated_by": "dev"},
                        "currency": {"value": "EUR", "updated_at": 100, "updated_by": "dev"},
                    },
                }
            ]
        },
    )
    minted = invites.mint(db_conn, KEY, BASE_URL, "shared", "joiner@example.com", owner)
    invites.redeem(
        db_conn, KEY, auth.Account(id=joiner, email="joiner@example.com"), minted["token"]
    )
    closing.cast_vote(db_conn, owner, "shared")
    closing.cast_vote(db_conn, joiner, "shared")  # unanimous -> closed
    invites.leave(db_conn, owner, "shared")
    invites.leave(db_conn, joiner, "shared")  # last member -> tombstoned
    db_conn.commit()

    # Precondition: this is the state the audit must not flinch at — a
    # tombstoned list still holding the last member's membership row, their
    # close vote, and a used invite.
    assert db_conn.execute("SELECT deleted FROM lists WHERE id = 'shared'").fetchone()[0] == 1
    assert db_conn.execute("SELECT COUNT(*) FROM memberships").fetchone()[0] == 1
    assert db_conn.execute("SELECT COUNT(*) FROM close_votes").fetchone()[0] == 1
    assert db_conn.execute("SELECT COUNT(*) FROM invites").fetchone()[0] == 1

    assert housekeeping.audit(db_conn) == []


# ---- dangling references (only reachable with the FK pragma off) --------------


def _case_invites_creator(conn):
    _invite(conn, "inv-bad", "l1", GHOST)
    return "inv-bad"


def _case_close_votes_account(conn):
    _vote(conn, "e1", GHOST)
    return f"e1/{GHOST}"


def _case_account_settings_account(conn):
    _settings(conn, GHOST)
    return GHOST


def _case_memberships_account(conn):
    _membership(conn, GHOST, "l1")
    return f"{GHOST}/l1"


def _case_auth_tokens_account(conn):
    _token(conn, "tok-bad", GHOST)
    return "tok-bad"


def _case_items_list(conn):
    _item(conn, "i-bad", GHOST_LIST)
    return "i-bad"


def _case_memberships_list(conn):
    _membership(conn, "a1", GHOST_LIST)
    return f"a1/{GHOST_LIST}"


def _case_close_votes_list(conn):
    _vote(conn, GHOST_LIST, "a1")
    return f"{GHOST_LIST}/a1"


def _case_invites_list(conn):
    _invite(conn, "inv-bad", GHOST_LIST, "a1")
    return "inv-bad"


@pytest.mark.parametrize(
    "build, expected_checks",
    [
        (_case_invites_creator, ["invites_creator_account_missing"]),
        # A vote by an account that does not exist is also, unavoidably, a vote
        # by a non-member of a live list: both invariants really are broken.
        (
            _case_close_votes_account,
            ["close_votes_account_missing", "close_votes_by_non_member"],
        ),
        (_case_account_settings_account, ["account_settings_account_missing"]),
        (_case_memberships_account, ["memberships_account_missing"]),
        (_case_auth_tokens_account, ["auth_tokens_account_missing"]),
        (_case_items_list, ["items_list_missing"]),
        (_case_memberships_list, ["memberships_list_missing"]),
        (_case_close_votes_list, ["close_votes_list_missing"]),
        (_case_invites_list, ["invites_list_missing"]),
    ],
    ids=lambda value: value[0] if isinstance(value, list) else "",
)
def test_a_dangling_reference_is_reported(db_conn, build, expected_checks):
    _baseline(db_conn)
    assert housekeeping.audit(db_conn, NOW) == []  # the look-alikes stay quiet

    with _without_foreign_keys(db_conn) as conn:
        expected_sample = build(conn)

    findings = housekeeping.audit(db_conn, NOW)

    assert _checks(findings) == expected_checks
    assert findings[0].count == 1
    assert findings[0].samples == (expected_sample,)


def test_a_dangling_reference_the_foreign_key_still_guards_cannot_be_created(db_conn):
    """The other half of the safety-net story: with the pragma on — i.e. in
    production — the row the test above builds is simply refused."""
    _baseline(db_conn)

    with pytest.raises(sqlite3.IntegrityError):
        _case_items_list(db_conn)


# ---- the remaining checks ------------------------------------------------------


def test_a_dead_invite_on_a_live_list_past_its_grace_window_is_reported(db_conn):
    _baseline(db_conn)
    _invite(db_conn, "inv-stale", "l1", "a1", expires_at=NOW - gc.INVITE_GRACE_MS - 1000)
    db_conn.commit()

    findings = housekeeping.audit(db_conn, NOW)

    assert _checks(findings) == ["invites_past_grace"]
    assert findings[0].samples == ("inv-stale",)


def test_a_live_list_with_no_members_is_reported(db_conn):
    _baseline(db_conn)
    _list(db_conn, "orphan")
    db_conn.commit()

    findings = housekeeping.audit(db_conn, NOW)

    assert _checks(findings) == [housekeeping.ORPHANED_LIVE_LISTS]
    assert findings[0].samples == ("orphan",)


def test_a_close_vote_on_a_non_expenses_list_is_reported(db_conn):
    _baseline(db_conn)
    _vote(db_conn, "l1", "a1")  # a1 IS a member; the list is simply not an expenses list
    db_conn.commit()

    findings = housekeeping.audit(db_conn, NOW)

    assert _checks(findings) == ["close_votes_on_non_expenses_list"]
    assert findings[0].samples == ("l1/a1",)


def test_a_close_vote_by_a_non_member_of_a_live_list_is_reported(db_conn):
    _baseline(db_conn)
    _vote(db_conn, "e1", "a2")  # a2 is a member of l1, not of e1
    db_conn.commit()

    findings = housekeeping.audit(db_conn, NOW)

    assert _checks(findings) == ["close_votes_by_non_member"]
    assert findings[0].samples == ("e1/a2",)


def test_a_live_item_on_a_tombstoned_list_is_reported(db_conn):
    _baseline(db_conn)
    _item(db_conn, "i-live-on-dead", "t1")
    db_conn.commit()

    findings = housekeeping.audit(db_conn, NOW)

    assert _checks(findings) == ["live_items_on_tombstoned_list"]
    assert findings[0].samples == ("i-live-on-dead",)


def test_a_list_tombstone_past_retention_is_reported(db_conn):
    _baseline(db_conn)
    _list(db_conn, "ancient", deleted=1, deleted_ts=NOW - gc.RETENTION_MS - 1000)
    db_conn.commit()

    findings = housekeeping.audit(db_conn, NOW)

    assert _checks(findings) == ["list_tombstones_past_retention"]
    assert findings[0].samples == ("ancient",)


def test_an_item_tombstone_past_retention_is_reported(db_conn):
    _baseline(db_conn)
    _item(db_conn, "i-ancient", "l1", deleted=1, deleted_ts=NOW - gc.RETENTION_MS - 1000)
    db_conn.commit()

    findings = housekeeping.audit(db_conn, NOW)

    assert _checks(findings) == ["item_tombstones_past_retention"]
    assert findings[0].samples == ("i-ancient",)


def test_a_finding_counts_every_row_but_samples_only_a_few(db_conn):
    _baseline(db_conn)
    for index in range(housekeeping.SAMPLE_LIMIT + 3):
        _list(db_conn, f"orphan-{index}")
    db_conn.commit()

    findings = housekeeping.audit(db_conn, NOW)

    assert findings[0].count == housekeeping.SAMPLE_LIMIT + 3
    assert len(findings[0].samples) == housekeeping.SAMPLE_LIMIT


def test_audit_writes_nothing(db_conn):
    _baseline(db_conn)
    _list(db_conn, "orphan")
    db_conn.commit()

    housekeeping.audit(db_conn, NOW)

    # The orphan is reported, not repaired: `audit` is read-only, and only
    # `sweep` tombstones.
    assert db_conn.execute("SELECT deleted FROM lists WHERE id = 'orphan'").fetchone()[0] == 0


# ---- the one repair -------------------------------------------------------------


def test_sweep_tombstones_an_orphaned_live_list_instead_of_deleting_it(db_conn):
    _baseline(db_conn)
    _list(db_conn, "orphan")
    _item(db_conn, "i-orphan", "orphan")
    db_conn.commit()

    result = housekeeping.sweep(db_conn, NOW)

    assert result["lists_tombstoned"] == 1
    # Tombstoned, NOT hard-deleted: the row must survive so every device that
    # still holds the list converges on the deletion through sync, and
    # retention takes it later like any other tombstone.
    row = db_conn.execute("SELECT deleted, deleted_by FROM lists WHERE id = 'orphan'").fetchone()
    assert row["deleted"] == 1
    assert row["deleted_by"] == invites.SERVER_ORPHAN
    assert db_conn.execute("SELECT deleted FROM items WHERE id = 'i-orphan'").fetchone()[0] == 1
    # And the repair is idempotent: the list is no longer live, so a second
    # sweep finds nothing to do.
    assert housekeeping.sweep(db_conn, NOW)["lists_tombstoned"] == 0


def test_sweep_reports_the_orphan_it_repairs(db_conn):
    _baseline(db_conn)
    _list(db_conn, "orphan")
    db_conn.commit()

    result = housekeeping.sweep(db_conn, NOW)

    assert _checks(result["findings"]) == [housekeeping.ORPHANED_LIVE_LISTS]


def test_sweep_does_not_delete_what_it_cannot_explain(db_conn):
    """A violation that is unreachable today means a bug elsewhere. The rows are
    the only evidence of it, so they are reported and left exactly where they
    are."""
    _baseline(db_conn)
    with _without_foreign_keys(db_conn) as conn:
        _case_items_list(conn)

    result = housekeeping.sweep(db_conn, NOW)

    assert _checks(result["findings"]) == ["items_list_missing"]
    assert db_conn.execute("SELECT 1 FROM items WHERE id = 'i-bad'").fetchone() is not None


def test_sweep_runs_the_retention_gc(db_conn):
    _baseline(db_conn)
    db_conn.execute(
        "UPDATE lists SET deleted_ts = ? WHERE id = 't1'", (NOW - gc.RETENTION_MS - 1000,)
    )
    db_conn.execute(
        "UPDATE items SET deleted_ts = ? WHERE id = 'i2'", (NOW - gc.RETENTION_MS - 1000,)
    )
    db_conn.commit()

    result = housekeeping.sweep(db_conn, NOW)

    assert result["lists_purged"] == 1
    assert db_conn.execute("SELECT 1 FROM lists WHERE id = 't1'").fetchone() is None
    # GC ran BEFORE the audit, so the tombstone it has just collected is not
    # also reported as "retention GC is overdue".
    assert result["findings"] == []


def test_sweep_logs_each_finding_and_each_repair(db_conn, caplog):
    caplog.set_level(logging.INFO, logger=audit_log.LOGGER_NAME)
    _baseline(db_conn)
    _list(db_conn, "orphan")
    db_conn.commit()

    housekeeping.sweep(db_conn, NOW)

    messages = [r.getMessage() for r in caplog.records if r.name == audit_log.LOGGER_NAME]
    assert any(
        "event=housekeeping.violation" in m and f"check={housekeeping.ORPHANED_LIVE_LISTS}" in m
        for m in messages
    )
    assert any("event=housekeeping.repair" in m and "action=tombstoned" in m for m in messages)
    assert any("event=housekeeping.sweep" in m for m in messages)
    # Audit records carry row ids, never anything that could be an email.
    assert not any("@" in m for m in messages)


# ---- when the sweep runs --------------------------------------------------------


@pytest.fixture
def pinned_clock(monkeypatch):
    monkeypatch.setattr(housekeeping, "_current_now_ms", lambda: NOW)
    monkeypatch.setattr(gc, "_current_now_ms", lambda: NOW)


def _stamp(conn, boot_id, last_audit_at):
    conn.execute("UPDATE server_runtime SET audit_boot_id = ? WHERE id = 1", (boot_id,))
    conn.execute("UPDATE meta SET last_audit_at = ? WHERE id = 1", (last_audit_at,))
    conn.commit()


def _last_audit_at(conn):
    return conn.execute("SELECT last_audit_at FROM meta WHERE id = 1").fetchone()[0]


def test_the_first_request_of_a_server_run_sweeps_and_the_next_one_does_not(
    db_conn, monkeypatch, pinned_clock
):
    _baseline(db_conn)
    _stamp(db_conn, "run-1", NOW)  # swept a moment ago, by the PREVIOUS run
    monkeypatch.setattr(housekeeping.boot, "current_boot_id", lambda: "run-2")

    assert housekeeping.maybe_sweep(db_conn) is True  # restart -> due, however recent
    assert housekeeping.maybe_sweep(db_conn) is False  # once per run, not once per request
    assert (
        db_conn.execute("SELECT audit_boot_id FROM server_runtime WHERE id = 1").fetchone()[0]
        == "run-2"
    )


def test_the_same_server_run_does_not_re_sweep_within_the_interval(
    db_conn, monkeypatch, pinned_clock
):
    _baseline(db_conn)
    monkeypatch.setattr(housekeeping.boot, "current_boot_id", lambda: "run-1")
    _stamp(db_conn, "run-1", NOW - housekeeping.SWEEP_INTERVAL_MS + 1)

    assert housekeeping.maybe_sweep(db_conn) is False
    assert _last_audit_at(db_conn) == NOW - housekeeping.SWEEP_INTERVAL_MS + 1


def test_the_same_server_run_sweeps_again_after_the_interval(db_conn, monkeypatch, pinned_clock):
    _baseline(db_conn)
    monkeypatch.setattr(housekeeping.boot, "current_boot_id", lambda: "run-1")
    _stamp(db_conn, "run-1", NOW - housekeeping.SWEEP_INTERVAL_MS)

    assert housekeeping.maybe_sweep(db_conn) is True
    assert _last_audit_at(db_conn) == NOW


def test_maybe_run_leaves_the_daily_gc_cadence_alone(db_conn, monkeypatch, pinned_clock):
    """The sweep is weekly; the retention GC stays daily. A request that is too
    soon for a sweep but late enough for GC must still collect."""
    _baseline(db_conn)
    monkeypatch.setattr(housekeeping.boot, "current_boot_id", lambda: "run-1")
    _stamp(db_conn, "run-1", NOW - DAY_MS)  # swept yesterday: no sweep due
    _item(db_conn, "i-old", "l1", deleted=1, deleted_ts=NOW - gc.RETENTION_MS - 1000)
    db_conn.execute("UPDATE meta SET last_gc_at = ? WHERE id = 1", (NOW - DAY_MS - 1,))
    db_conn.commit()

    housekeeping.maybe_run(db_conn)

    assert _last_audit_at(db_conn) == NOW - DAY_MS  # no sweep
    assert db_conn.execute("SELECT 1 FROM items WHERE id = 'i-old'").fetchone() is None  # but GC


# ---- the request hook -----------------------------------------------------------


def _register_and_login(client, email="hk@example.com"):
    client.post("/api/v1/register", json={"email": email, "password": PW})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": PW, "device_label": "dev"}
    )
    return resp.get_json()["token"]


def _open_db(app):
    return db_module.connect(get_config_by_name(app)["database_path"])


def test_an_authenticated_request_that_is_not_sync_triggers_housekeeping(client, app):
    """The whole point of T-218: before it, `POST /sync` was the only trigger,
    so a server whose clients were not syncing never swept."""
    token = _register_and_login(client)

    resp = client.get("/api/v1/lists", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200

    conn = _open_db(app)
    row = conn.execute(
        "SELECT meta.last_audit_at AS at, server_runtime.audit_boot_id AS boot_id "
        "FROM meta, server_runtime WHERE meta.id = 1 AND server_runtime.id = 1"
    ).fetchone()
    conn.close()
    assert row["at"] > 0
    assert row["boot_id"] is not None


def test_an_anonymous_request_does_not_trigger_housekeeping(client, app):
    # Registering and logging in are the requests a stranger can aim at the
    # server; neither may set a sweep going.
    _register_and_login(client)

    conn = _open_db(app)
    assert conn.execute("SELECT last_audit_at FROM meta WHERE id = 1").fetchone()[0] == 0
    assert (
        conn.execute("SELECT audit_boot_id FROM server_runtime WHERE id = 1").fetchone()[0] is None
    )
    conn.close()


def test_an_authenticated_request_drives_the_retention_gc(client, app):
    token = _register_and_login(client)
    headers = {"Authorization": f"Bearer {token}"}
    client.get("/api/v1/lists", headers=headers)  # first request: sweeps, stamps last_gc_at

    conn = _open_db(app)
    conn.execute(
        "INSERT INTO lists (id, created_at, change_seq, name, name_ts, name_by, deleted, "
        "deleted_ts, deleted_by) VALUES ('gone', 0, 1, 'Gone', 0, 'dev', 1, 0, 'dev')"
    )
    conn.execute("UPDATE meta SET last_gc_at = 0 WHERE id = 1")  # a day has 'passed'
    conn.commit()
    conn.close()

    assert client.get("/api/v1/lists", headers=headers).status_code == 200

    conn = _open_db(app)
    assert conn.execute("SELECT 1 FROM lists WHERE id = 'gone'").fetchone() is None
    conn.close()


def test_a_housekeeping_failure_cannot_fail_the_request(client, app, monkeypatch, caplog):
    caplog.set_level(logging.INFO, logger=audit_log.LOGGER_NAME)
    token = _register_and_login(client)

    def _explode(conn):
        raise RuntimeError("housekeeping is broken")

    monkeypatch.setattr(housekeeping, "maybe_run", _explode)

    resp = client.get("/api/v1/lists", headers={"Authorization": f"Bearer {token}"})

    assert resp.status_code == 200  # observation must not break the observed request
    messages = [r.getMessage() for r in caplog.records if r.name == audit_log.LOGGER_NAME]
    assert any("event=housekeeping.failed" in m and "error=RuntimeError" in m for m in messages)


def test_an_uncommitted_transaction_is_never_committed_by_housekeeping(client, app):
    """Housekeeping commits, so it must not run on a connection the request left
    mid-transaction — it would commit that request's half-finished work as a
    side effect.

    A sync batch that creates a list and then trips over a bad item is exactly
    that shape: `apply_changes` applies lists before items, so the list row is
    written and then the request raises before any commit.
    """
    token = _register_and_login(client)

    resp = client.post(
        "/api/v1/sync",
        json={
            "cursor": 0,
            "device_id": "dev",
            "full_lists": [],
            "changes": {
                "lists": [
                    {
                        "id": "half-written",
                        "fields": {
                            "name": {"value": "Half", "updated_at": 100, "updated_by": "dev"}
                        },
                    }
                ],
                "items": [
                    {
                        "id": "bad-item",
                        "list_id": "half-written",
                        "fields": {
                            "status": {"value": "nope", "updated_at": 100, "updated_by": "dev"}
                        },
                    }
                ],
            },
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 422

    conn = _open_db(app)
    # The list write was rolled back by the connection close, not committed by
    # the sweep...
    assert conn.execute("SELECT 1 FROM lists WHERE id = 'half-written'").fetchone() is None
    # ...because the sweep declined to run at all on that request.
    assert conn.execute("SELECT last_audit_at FROM meta WHERE id = 1").fetchone()[0] == 0
    conn.close()

    # The next, clean request sweeps as usual.
    assert (
        client.get("/api/v1/lists", headers={"Authorization": f"Bearer {token}"}).status_code == 200
    )
    conn = _open_db(app)
    assert conn.execute("SELECT last_audit_at FROM meta WHERE id = 1").fetchone()[0] > 0
    conn.close()


# ---- the CLI ---------------------------------------------------------------------


def test_audit_cli_reports_a_clean_database(cli_runner, app):
    result = cli_runner.invoke(args=["shoppinglist", "audit"])

    assert result.exit_code == 0
    assert "no violations" in result.output


def test_audit_cli_exits_non_zero_when_it_finds_a_violation(cli_runner, app):
    conn = _open_db(app)
    _list(conn, "orphan")  # a live list nobody is a member of
    conn.commit()
    conn.close()

    result = cli_runner.invoke(args=["shoppinglist", "audit"])

    assert result.exit_code == 1  # usable straight from cron / a monitoring check
    assert housekeeping.ORPHANED_LIVE_LISTS in result.output
    assert "orphan" in result.output


def test_audit_cli_does_not_repair(cli_runner, app):
    conn = _open_db(app)
    _list(conn, "orphan")
    conn.commit()
    conn.close()

    cli_runner.invoke(args=["shoppinglist", "audit"])

    conn = _open_db(app)
    assert conn.execute("SELECT deleted FROM lists WHERE id = 'orphan'").fetchone()[0] == 0
    conn.close()
