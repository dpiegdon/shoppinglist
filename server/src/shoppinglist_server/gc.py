"""Tombstone garbage collection (Spec §6 deletes/tombstones, §2 operator CLI)."""

from .auth import now_ms as _current_now_ms

# Fixed tombstone retention (Spec §6). Six weeks, down from the original 90 days
# (T-219).
#
# The window exists so a device that has been offline can still catch up
# INCREMENTALLY: past it, its cursor has fallen below `meta.gc_horizon` and
# `sync.check_cursor` answers `410 full_resync_required`, which both clients
# handle by pushing their dirty rows first and then wiping and re-pulling from
# cursor 0. So shortening the window costs a device idle longer than it one full
# resync — never data.
#
# What it buys down is resurrection: a device that comes back after the window
# holding a pending edit to a row that was deleted meanwhile pushes that edit,
# and the tombstone it would have lost to is gone. Six weeks is judged long
# enough for a phone left in a drawer over a holiday, and short enough that the
# resurrection window is not a season.
RETENTION_MS = 45 * 24 * 60 * 60 * 1000

# How long a clearly-dead invite is kept after it died (T-219). Deliberately its
# own constant and NOT RETENTION_MS: the two now answer different questions.
# Tombstone retention is about how long an offline device may stay away and
# still sync incrementally, and an invite is not part of that at all — invites
# have no `change_seq`, nothing syncs them, so purging one early cannot affect
# any client. What an invite row does hold is `invited_email`: a third party's
# address, belonging to someone who may never have become a user of this server.
# There is no reason to keep that around for a season.
INVITE_GRACE_MS = 7 * 24 * 60 * 60 * 1000

MAYBE_RUN_INTERVAL_MS = 24 * 60 * 60 * 1000  # opportunistic GC runs at most ~once/day


def run(conn, now_ms: int) -> dict:
    """Hard-delete tombstones older than the retention window.

    Lists are purged first, and purging a list unconditionally deletes ALL of
    its items (not just already-tombstoned ones), any lingering membership
    row, and any invites that were ever minted for it. This guarantees no
    dangling `items.list_id` / `memberships.list_id` / `invites.list_id` FK
    reference survives regardless of edge-case state, and specifically
    handles the case `invites.leave` sets up deliberately: an orphaned list's
    departing member keeps their membership row so their other devices can
    see the tombstone propagate via sync; that row must be removed together
    with (not after) the list row, since the FK would otherwise block the
    delete. (A used or expired invite referencing the list is equally a
    dangling FK once the list is gone, regardless of how the list was shared.)

    Standalone item tombstones (the ordinary single-item-delete case, whose
    list is still alive) are purged separately afterward.

    Finally, clearly-dead invites (expired, used, or revoked) on lists that
    are still alive are purged once they're old enough — see
    `_purge_dead_invites_for_live_lists` below. Their age is measured against
    INVITE_GRACE_MS (7 days), not the tombstone retention window: see that
    constant for why the two are separate (T-219). This is additive to the
    cascade delete above: invites on a list that itself just got purged are
    already gone by the time this step runs, so it only ever touches invites
    whose list survives. Invites are not part of the sync change_seq stream
    (they have no `change_seq` column), so purging them never advances
    `meta.gc_horizon`.

    Auth sessions idle past their inactivity window are purged too — see
    `_purge_expired_sessions` below. Like invites they carry no `change_seq`,
    so they never advance `meta.gc_horizon`.

    Advances `meta.gc_horizon` to the highest `change_seq` removed and stamps
    `meta.last_gc_at`. Returns purge counts.
    """
    cutoff = now_ms - RETENTION_MS
    max_seq = 0
    items_purged = 0
    lists_purged = 0

    list_rows = conn.execute(
        "SELECT id, change_seq FROM lists WHERE deleted = 1 AND deleted_ts < ?", (cutoff,)
    ).fetchall()
    for row in list_rows:
        max_seq = max(max_seq, row["change_seq"])
        list_id = row["id"]
        item_count = conn.execute(
            "SELECT COUNT(*) AS n FROM items WHERE list_id = ?", (list_id,)
        ).fetchone()["n"]
        items_purged += item_count
        conn.execute("DELETE FROM items WHERE list_id = ?", (list_id,))
        conn.execute("DELETE FROM memberships WHERE list_id = ?", (list_id,))
        # close_votes references lists(id), so it has to go with the list, not after it (T-157).
        conn.execute("DELETE FROM close_votes WHERE list_id = ?", (list_id,))
        conn.execute("DELETE FROM invites WHERE list_id = ?", (list_id,))
        conn.execute("DELETE FROM lists WHERE id = ?", (list_id,))
        lists_purged += 1

    item_rows = conn.execute(
        "SELECT id, change_seq FROM items WHERE deleted = 1 AND deleted_ts < ?", (cutoff,)
    ).fetchall()
    for row in item_rows:
        max_seq = max(max_seq, row["change_seq"])
        conn.execute("DELETE FROM items WHERE id = ?", (row["id"],))
    items_purged += len(item_rows)

    # Its own cutoff, on its own clock — INVITE_GRACE_MS, not RETENTION_MS (T-219).
    invites_purged = _purge_dead_invites_for_live_lists(conn, now_ms - INVITE_GRACE_MS)
    sessions_purged = _purge_expired_sessions(conn, now_ms)

    if max_seq > 0:
        conn.execute("UPDATE meta SET gc_horizon = MAX(gc_horizon, ?) WHERE id = 1", (max_seq,))
    conn.execute("UPDATE meta SET last_gc_at = ? WHERE id = 1", (now_ms,))
    conn.commit()

    return {
        "items_purged": items_purged,
        "lists_purged": lists_purged,
        "invites_purged": invites_purged,
        "sessions_purged": sessions_purged,
    }


def _purge_dead_invites_for_live_lists(conn, cutoff: int) -> int:
    """Hard-delete clearly-dead invites — expired, used, or revoked — that
    reference a still-LIVE list and are old enough to clear `cutoff`.

    `cutoff` is `now - INVITE_GRACE_MS`, i.e. dead for a week, and is passed in
    separately from the tombstone cutoff the rest of `run` works with (T-219).

    Invites for lists that get hard-deleted above are already gone by the
    time this runs, so this only ever touches lists with `deleted = 0`.

    "Dead" here reduces to a single check per invite, and the age rule is
    unchanged by T-219 — only the window it is measured against:
      - used: `used_at` is the moment it stopped being actionable, so age is
        measured from `used_at` regardless of `expires_at`/`revoked`. A
        *recently*-used invite is therefore kept even if its nominal
        `expires_at` is already old, per "keep recently-used ones so the
        members screen history stays sensible" — a week still covers that: the
        history worth reading is "who joined, and when", and by then the
        joiner is simply a member like any other.
      - not used: `expires_at` is used as the death marker for BOTH the
        naturally-expired and the revoked case. There's no dedicated
        `revoked_at` column, but once `expires_at` is itself a week in the past
        the invite is unusable either way (it is past its own 7-day life on top
        of that), so this is a safe, conservative proxy — a revoked invite is
        never purged earlier than an equivalent never-revoked one would be.

    Does NOT touch `meta.gc_horizon` / `change_seq` — invites have no
    `change_seq` column and are not part of the sync stream.
    """
    rows = conn.execute(
        "SELECT invites.id AS id FROM invites "
        "JOIN lists ON lists.id = invites.list_id "
        "WHERE lists.deleted = 0 AND ("
        "  (invites.used_at IS NOT NULL AND invites.used_at < ?)"
        "  OR (invites.used_at IS NULL AND invites.expires_at < ?)"
        ")",
        (cutoff, cutoff),
    ).fetchall()
    for row in rows:
        conn.execute("DELETE FROM invites WHERE id = ?", (row["id"],))
    return len(rows)


def _purge_expired_sessions(conn, now_ms: int) -> int:
    """Hard-delete auth_tokens past their sliding inactivity window (T-104).

    No extra retention window applies (unlike tombstones): once
    `last_seen_at + idle_ttl_ms` is in the past the session is already dead to
    `auth.require_account`, which rejects it and deletes it on sight. This sweep
    just collects the ones nobody has tried to use since they expired — without
    it, a session abandoned forever would linger in the table forever.

    Does NOT touch `meta.gc_horizon` / `change_seq` — auth_tokens have no
    `change_seq` column and are not part of the sync stream.
    """
    cur = conn.execute("DELETE FROM auth_tokens WHERE last_seen_at + idle_ttl_ms < ?", (now_ms,))
    return cur.rowcount


def maybe_run(conn) -> None:
    """Run GC iff it last ran (or has never run) more than ~24h ago."""
    now = _current_now_ms()
    row = conn.execute("SELECT last_gc_at FROM meta WHERE id = 1").fetchone()
    if now - row["last_gc_at"] >= MAYBE_RUN_INTERVAL_MS:
        run(conn, now)
