"""Standing housekeeping: when the retention sweep runs, and a read-only audit
of the invariants the schema cannot state (T-218).

Two separate things live here, for one reason each.

**The trigger.** `gc.maybe_run` has always been correct about *what* to purge
and wrong about *when*: its only call site was `POST /sync`, so a server whose
clients happen not to be syncing — everyone on holiday, an instance kept for one
archived list, a deployment whose Android app was uninstalled — never swept at
all. Expired sessions, dead invites and retention-expired tombstones then
accumulate for as long as that lasts. `maybe_run` below is wired into the
blueprint's request plumbing instead (see `create_blueprint` in `__init__.py`),
so *any* authenticated request drives it, and it fires on the first request of a
server run as well as about weekly after that.

A server with no traffic at all still sweeps nothing. That is correct and not a
gap to be closed with a background thread: with nobody logged in, nothing is
being created either, so there is nothing accumulating to sweep. The sweep
happens the moment somebody comes back.

**The audit.** An investigation for T-218 established what is *not* wrong, and
it is worth writing down so the next reader does not redo it:

* Dangling references are structurally impossible. `db.connect()` has set
  `PRAGMA foreign_keys = ON` since the first server commit and every
  referencing column is declared `REFERENCES`, so SQLite refuses them.
* There is no deleted-but-present account. `accounts._delete_account_row`
  removes the account and everything hanging off it in one transaction.
* What *does* linger is rows on a **tombstoned** list: the departing member's
  membership row (kept deliberately by `invites.leave`, so their other devices
  converge on the tombstone via sync), their close vote, and the list's
  invites. `gc.run` clears all of them once retention passes. That is the
  documented design, not a defect — see the "close votes on a list the voter
  is not a member of" check below, which is the easiest false positive here.

So every check below is a **safety net**, not a live repair. It answers "has
something upstream started producing states that cannot happen?", and most of
them can only be provoked in a test with `PRAGMA foreign_keys = OFF`.

**Repair policy.** Delete only what is unambiguously dead and already GC's job
— which in practice means: delete nothing here that `gc.run` would not have
deleted anyway. The sweep therefore calls `gc.run` and leaves purging to it.

The single repair this module performs is the one state that is reachable in
principle and has an obviously right answer: a **live list with no memberships
at all** is tombstoned (via `invites.clear_and_tombstone`), never hard-deleted,
exactly as `invites.orphan_check` would have done at the moment its last member
left. Tombstoning means devices converge on the deletion the normal way and
retention takes the row later; hard-deleting it would strand every client that
still has the list, which is precisely what the tombstone mechanism exists to
avoid.

Everything else is **reported, not silently deleted**. A violation that cannot
currently be reached means a bug somewhere else, and the violating rows are the
only evidence of it; deleting them would destroy the one thing that could
explain how they got there.
"""

import sqlite3
from dataclasses import dataclass

from . import audit as audit_log
from . import boot, gc
from .auth import now_ms as _current_now_ms
from .sync import EXPENSES_KIND

# The standing sweep's cadence once a server run's first sweep has happened.
# Sits next to gc.MAYBE_RUN_INTERVAL_MS (24h) in spirit but is deliberately much
# longer: the retention GC is cheap and incremental, while the audit is a dozen
# whole-table scans whose answer is "nothing" on every healthy server.
SWEEP_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000  # about weekly

# How many offending row ids a finding carries. Enough to start an investigation
# with, few enough that a badly broken database cannot flood the log.
SAMPLE_LIMIT = 5


@dataclass(frozen=True)
class Finding:
    """One violated invariant: which check, how many rows, and a few of them.

    `samples` holds row **ids only** — never an email or any other stored
    value. Findings go to the audit log, and audit.py's `_REDACTED_KEYS`
    docstring has the reason: the log is retained longer and guarded less
    carefully than the database, so identifying a row by its opaque id is the
    whole point. For a table with a composite primary key the id is its
    components joined with "/", in primary-key order.
    """

    check: str
    count: int
    samples: tuple[str, ...]


# ---- the checks -----------------------------------------------------------------
#
# Each entry is (name, SQL selecting exactly one column: the offending row's id).
# The SQL is used twice — wrapped in a COUNT for the total, and with a LIMIT for
# the samples — so it must be a bare SELECT with no trailing clause of its own.
#
# `:cutoff` is bound to now - gc.RETENTION_MS, `:invite_cutoff` to
# now - gc.INVITE_GRACE_MS, `:expenses_kind` to sync.EXPENSES_KIND.

_MISSING_ACCOUNT_CHECKS: list[tuple[str, str]] = [
    (
        "invites_creator_account_missing",
        "SELECT invites.id FROM invites WHERE NOT EXISTS ("
        "  SELECT 1 FROM accounts WHERE accounts.id = invites.created_by)",
    ),
    (
        "close_votes_account_missing",
        "SELECT close_votes.list_id || '/' || close_votes.account_id FROM close_votes "
        "WHERE NOT EXISTS ("
        "  SELECT 1 FROM accounts WHERE accounts.id = close_votes.account_id)",
    ),
    (
        "account_settings_account_missing",
        "SELECT account_settings.account_id FROM account_settings WHERE NOT EXISTS ("
        "  SELECT 1 FROM accounts WHERE accounts.id = account_settings.account_id)",
    ),
    (
        "memberships_account_missing",
        "SELECT memberships.account_id || '/' || memberships.list_id FROM memberships "
        "WHERE NOT EXISTS ("
        "  SELECT 1 FROM accounts WHERE accounts.id = memberships.account_id)",
    ),
    (
        "auth_tokens_account_missing",
        "SELECT auth_tokens.id FROM auth_tokens WHERE NOT EXISTS ("
        "  SELECT 1 FROM accounts WHERE accounts.id = auth_tokens.account_id)",
    ),
]

_MISSING_LIST_CHECKS: list[tuple[str, str]] = [
    (
        "items_list_missing",
        "SELECT items.id FROM items WHERE NOT EXISTS ("
        "  SELECT 1 FROM lists WHERE lists.id = items.list_id)",
    ),
    (
        "memberships_list_missing",
        "SELECT memberships.account_id || '/' || memberships.list_id FROM memberships "
        "WHERE NOT EXISTS (SELECT 1 FROM lists WHERE lists.id = memberships.list_id)",
    ),
    (
        "close_votes_list_missing",
        "SELECT close_votes.list_id || '/' || close_votes.account_id FROM close_votes "
        "WHERE NOT EXISTS (SELECT 1 FROM lists WHERE lists.id = close_votes.list_id)",
    ),
    (
        "invites_list_missing",
        "SELECT invites.id FROM invites WHERE NOT EXISTS ("
        "  SELECT 1 FROM lists WHERE lists.id = invites.list_id)",
    ),
]

# The name the repair below reports itself under, and the check that finds its
# subjects. Named once so the finding, the repair record and the query cannot
# drift apart.
ORPHANED_LIVE_LISTS = "live_lists_without_members"
_ORPHANED_LIVE_LISTS_SQL = (
    "SELECT lists.id FROM lists WHERE lists.deleted = 0 AND NOT EXISTS ("
    "  SELECT 1 FROM memberships WHERE memberships.list_id = lists.id)"
)

_CHECKS: list[tuple[str, str]] = [
    # 1. Rows referencing an account that no longer exists.
    *_MISSING_ACCOUNT_CHECKS,
    # 2. Rows referencing a list that no longer exists.
    *_MISSING_LIST_CHECKS,
    # 3. Invites past their usable life that survive beyond their grace window.
    #    The condition mirrors gc._purge_dead_invites_for_live_lists exactly —
    #    including `:invite_cutoff`, which is gc.INVITE_GRACE_MS (a week) and
    #    NOT the tombstone retention window (T-219), and including the
    #    restriction to LIVE lists: a dead invite on a list that is itself
    #    tombstoned but not yet past retention is deliberately kept (it goes
    #    with the list, in the same statement, when the list is purged), so
    #    counting it here would report the GC's own design as a defect. What
    #    this does catch is a GC that is overdue or broken — and, because the
    #    rows hold a third party's email address, it is the check most worth
    #    being told about.
    (
        "invites_past_grace",
        "SELECT invites.id FROM invites JOIN lists ON lists.id = invites.list_id "
        "WHERE lists.deleted = 0 AND ("
        "  (invites.used_at IS NOT NULL AND invites.used_at < :invite_cutoff)"
        "  OR (invites.used_at IS NULL AND invites.expires_at < :invite_cutoff))",
    ),
    # 4. A live list nobody is a member of. Unreachable today — every path that
    #    removes a membership calls invites.orphan_check or invites.leave, and
    #    creating a list through sync inserts the creator's membership in the
    #    same transaction — and the one violation this module repairs rather
    #    than only reporting. See the module docstring.
    (ORPHANED_LIVE_LISTS, _ORPHANED_LIVE_LISTS_SQL),
    # 5. Everything else.
    #
    #    A close vote only means anything on an expenses list: closing is the
    #    expenses-list settlement ritual (T-157) and no other kind has a vote
    #    to cast. Note this does NOT exclude tombstoned lists — a vote on a
    #    tombstoned *shopping* list was already wrong before the list died.
    (
        "close_votes_on_non_expenses_list",
        "SELECT close_votes.list_id || '/' || close_votes.account_id FROM close_votes "
        "JOIN lists ON lists.id = close_votes.list_id WHERE lists.kind != :expenses_kind",
    ),
    #    A close vote by a non-member, on a list that is still LIVE. The
    #    "still live" clause is load-bearing, and leaving it out is the easiest
    #    false positive in this whole module. A tombstoned list is where votes
    #    and memberships are deliberately left lying around: invites.leave
    #    keeps the last member's membership row (and so their vote) so their
    #    other devices converge on the tombstone, and gc.run clears the lot at
    #    retention. Nothing on a dead list can be distorted by a stray vote —
    #    it is spent history, and treating it as a violation would report the
    #    design as a defect. On a LIVE list the same row is a real one: an
    #    unanimity count would be measured against a roster that no longer
    #    contains the voter, so the list could close on an absent member's say
    #    so, or never close at all.
    (
        "close_votes_by_non_member",
        "SELECT close_votes.list_id || '/' || close_votes.account_id FROM close_votes "
        "JOIN lists ON lists.id = close_votes.list_id "
        "WHERE lists.deleted = 0 AND NOT EXISTS ("
        "  SELECT 1 FROM memberships WHERE memberships.list_id = close_votes.list_id"
        "    AND memberships.account_id = close_votes.account_id)",
    ),
    #    A live item under a tombstoned list. Tombstoning a list tombstones
    #    every live item first (invites.clear_and_tombstone), so a survivor
    #    means a write landed on a list that was already dead — it would be
    #    invisible to clients and resurrect if the list ever came back.
    (
        "live_items_on_tombstoned_list",
        "SELECT items.id FROM items JOIN lists ON lists.id = items.list_id "
        "WHERE lists.deleted = 1 AND items.deleted = 0",
    ),
    #    Tombstones older than gc.RETENTION_MS that are still here. Split by
    #    table so a finding's ids are unambiguously from one of them. Since
    #    `sweep` runs gc.run immediately before the audit, either of these
    #    firing means the retention GC did not do its job, not merely that it
    #    is overdue.
    (
        "list_tombstones_past_retention",
        "SELECT lists.id FROM lists WHERE lists.deleted = 1 AND lists.deleted_ts < :cutoff",
    ),
    (
        "item_tombstones_past_retention",
        "SELECT items.id FROM items WHERE items.deleted = 1 AND items.deleted_ts < :cutoff",
    ),
]


def _run_check(conn: sqlite3.Connection, name: str, sql: str, params: dict) -> Finding | None:
    """Count first, sample only if the count is non-zero.

    Two statements rather than fetching every offending row and taking len():
    a check like `item_tombstones_past_retention` can legitimately match a very
    large number of rows on a server whose GC has been dead for months, and the
    finding only ever reports SAMPLE_LIMIT of them.
    """
    count = conn.execute(f"SELECT COUNT(*) AS n FROM ({sql})", params).fetchone()["n"]
    if count == 0:
        return None
    samples = [
        str(row[0]) for row in conn.execute(f"{sql} LIMIT {SAMPLE_LIMIT}", params).fetchall()
    ]
    return Finding(check=name, count=count, samples=tuple(samples))


def audit(conn: sqlite3.Connection, now_ms: int | None = None) -> list[Finding]:
    """Check every invariant and return one Finding per violated one, in the
    order the checks are declared above. Read-only: writes nothing, commits
    nothing, repairs nothing — `sweep` owns the one repair.

    `now_ms` defaults to the wall clock; it is a parameter so a test can script
    tombstone ages the way test_gc.py does.
    """
    now = _current_now_ms() if now_ms is None else now_ms
    params = {
        "cutoff": now - gc.RETENTION_MS,
        "invite_cutoff": now - gc.INVITE_GRACE_MS,
        "expenses_kind": EXPENSES_KIND,
    }
    findings = []
    for name, sql in _CHECKS:
        finding = _run_check(conn, name, sql, params)
        if finding is not None:
            findings.append(finding)
    return findings


def _tombstone_orphaned_live_lists(conn: sqlite3.Connection) -> list[str]:
    """Tombstone every live list with no memberships. Does not commit.

    Reuses invites.clear_and_tombstone, so an orphan found here ends up in
    exactly the state invites.orphan_check would have left it in at the moment
    its last member left: items tombstoned, list tombstoned, change_seq bumped
    for each, `deleted_by` = SERVER_ORPHAN. Retention then removes it once
    gc.RETENTION_MS has passed, like any other tombstone.
    """
    from . import invites  # deferred: invites -> auth -> this package's __init__

    list_ids = [row["id"] for row in conn.execute(_ORPHANED_LIVE_LISTS_SQL).fetchall()]
    for list_id in list_ids:
        invites.clear_and_tombstone(conn, list_id)
    return list_ids


def sweep(conn: sqlite3.Connection, now_ms: int) -> dict:
    """Run the retention GC, then the audit; log what was found; repair the one
    repairable violation; stamp the run. Commits.

    Order matters. GC runs first so the audit judges the database as it should
    be *after* a sweep — otherwise every tombstone that has just aged out would
    be reported as "retention GC is overdue" on the very run that fixes it. The
    audit then runs before the repair, so the orphaned lists the repair is
    about are in the returned findings and in the log rather than being quietly
    fixed with no trace.
    """
    result = dict(gc.run(conn, now_ms))

    findings = audit(conn, now_ms)
    for finding in findings:
        # outcome="error": a finding is a violated invariant, i.e. evidence of a
        # bug elsewhere. It is worth waking somebody up for, unlike the sweep
        # itself.
        audit_log.record(
            "housekeeping.violation",
            outcome="error",
            check=finding.check,
            count=finding.count,
            samples=",".join(finding.samples),
        )

    repaired = _tombstone_orphaned_live_lists(conn)
    if repaired:
        audit_log.record(
            "housekeeping.repair",
            check=ORPHANED_LIVE_LISTS,
            action="tombstoned",
            count=len(repaired),
            samples=",".join(repaired[:SAMPLE_LIMIT]),
        )

    conn.execute("UPDATE meta SET last_audit_at = ? WHERE id = 1", (now_ms,))
    conn.execute(
        "UPDATE server_runtime SET audit_boot_id = ? WHERE id = 1", (boot.current_boot_id(),)
    )
    conn.commit()

    result["findings"] = findings
    result["lists_tombstoned"] = len(repaired)
    audit_log.record(
        "housekeeping.sweep",
        violations=len(findings),
        lists_tombstoned=len(repaired),
        items_purged=result["items_purged"],
        lists_purged=result["lists_purged"],
        invites_purged=result["invites_purged"],
        sessions_purged=result["sessions_purged"],
    )
    return result


def _sweep_is_due(conn: sqlite3.Connection, now: int) -> bool:
    """One statement, two single-row primary-key lookups. This runs on every
    authenticated request, so "nothing is due" has to cost almost nothing.

    Due on either of two conditions:

    * `server_runtime.audit_boot_id` is not this server run's boot id — i.e.
      this is the first request of a server run. A blueprint has no reliable
      "server startup, once" hook (boot.py explains why: under a non-preload
      gunicorn there is no distinguished first worker), so the same trick
      server_settings.py uses for the registration override is used here —
      compare a recorded boot id lazily, at request time. Every worker of one
      run computes the same id, so the sweep happens once per run, not once
      per worker; whichever worker gets there first stamps it.
    * the recorded `meta.last_audit_at` is at least SWEEP_INTERVAL_MS old.
    """
    row = conn.execute(
        "SELECT meta.last_audit_at AS last_audit_at, "
        "       server_runtime.audit_boot_id AS audit_boot_id "
        "FROM meta LEFT JOIN server_runtime ON server_runtime.id = 1 "
        "WHERE meta.id = 1"
    ).fetchone()
    if row is None:  # pragma: no cover - meta row 1 is seeded by schema.sql
        return False
    if row["audit_boot_id"] != boot.current_boot_id():
        return True
    return now - row["last_audit_at"] >= SWEEP_INTERVAL_MS


def maybe_sweep(conn: sqlite3.Connection) -> bool:
    """Sweep iff this is the first request of a server run, or the last sweep
    was more than SWEEP_INTERVAL_MS ago. Returns whether it swept."""
    now = _current_now_ms()
    if not _sweep_is_due(conn, now):
        return False
    sweep(conn, now)
    return True


def maybe_run(conn: sqlite3.Connection) -> None:
    """The whole per-request trigger, called from the blueprint's after_request
    hook in `__init__.py` for every authenticated request.

    The standing sweep goes first: it runs gc.run itself and stamps
    `meta.last_gc_at`, so on the rare request where both would fire, the
    gc.maybe_run below then sees a fresh timestamp and no-ops instead of
    purging twice.

    gc.maybe_run keeps its own, much shorter 24h cadence. That is unchanged
    from when `POST /sync` was its only caller (T-218 broadened *where* it is
    called from, not how often it runs) — the retention GC is cheap, and
    keeping it daily means an expired session is collected within a day rather
    than within a week.
    """
    maybe_sweep(conn)
    gc.maybe_run(conn)
