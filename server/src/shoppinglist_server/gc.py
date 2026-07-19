"""Tombstone garbage collection (Spec §6 deletes/tombstones, §2 operator CLI)."""

from .auth import now_ms as _current_now_ms

RETENTION_MS = 90 * 24 * 60 * 60 * 1000  # fixed 90-day tombstone retention (Spec §6)
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
    `_purge_dead_invites_for_live_lists` below. This is additive to the
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

    invites_purged = _purge_dead_invites_for_live_lists(conn, cutoff)
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

    Invites for lists that get hard-deleted above are already gone by the
    time this runs, so this only ever touches lists with `deleted = 0`.

    "Dead" here reduces to a single check per invite:
      - used: `used_at` is the moment it stopped being actionable, so age is
        measured from `used_at` regardless of `expires_at`/`revoked` (this
        also means a *recently*-used invite is kept even if its nominal
        `expires_at` is already old, per "keep recently-used ones so the
        members screen history stays sensible").
      - not used: `expires_at` is used as the death marker for BOTH the
        naturally-expired and the revoked case. There's no dedicated
        `revoked_at` column, but once `expires_at` is itself older than the
        retention window the invite is unusable either way (it's long past
        its own 7-day life), so this is a safe, conservative proxy — a
        revoked invite is never purged earlier than an equivalent
        never-revoked one would be.

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
    cur = conn.execute(
        "DELETE FROM auth_tokens WHERE last_seen_at + idle_ttl_ms < ?", (now_ms,)
    )
    return cur.rowcount


def maybe_run(conn) -> None:
    """Run GC iff it last ran (or has never run) more than ~24h ago."""
    now = _current_now_ms()
    row = conn.execute("SELECT last_gc_at FROM meta WHERE id = 1").fetchone()
    if now - row["last_gc_at"] >= MAYBE_RUN_INTERVAL_MS:
        run(conn, now)
