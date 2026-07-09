"""Tombstone garbage collection (Spec §6 deletes/tombstones, §2 operator CLI)."""

from .auth import now_ms as _current_now_ms

RETENTION_MS = 90 * 24 * 60 * 60 * 1000  # fixed 90-day tombstone retention (Spec §6)
MAYBE_RUN_INTERVAL_MS = 24 * 60 * 60 * 1000  # opportunistic GC runs at most ~once/day


def run(conn, now_ms: int) -> dict:
    """Hard-delete tombstones older than the retention window.

    Lists are purged first, and purging a list unconditionally deletes ALL of
    its items (not just already-tombstoned ones) plus any lingering
    membership rows. This guarantees no dangling `items.list_id` /
    `memberships.list_id` FK reference survives regardless of edge-case item
    state, and specifically handles the case `invites.leave` sets up
    deliberately: an orphaned list's departing member keeps their membership
    row so their other devices can see the tombstone propagate via sync; that
    row must be removed together with (not after) the list row, since the FK
    would otherwise block the delete.

    Standalone item tombstones (the ordinary single-item-delete case, whose
    list is still alive) are purged separately afterward.

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
        conn.execute("DELETE FROM lists WHERE id = ?", (list_id,))
        lists_purged += 1

    item_rows = conn.execute(
        "SELECT id, change_seq FROM items WHERE deleted = 1 AND deleted_ts < ?", (cutoff,)
    ).fetchall()
    for row in item_rows:
        max_seq = max(max_seq, row["change_seq"])
        conn.execute("DELETE FROM items WHERE id = ?", (row["id"],))
    items_purged += len(item_rows)

    if max_seq > 0:
        conn.execute("UPDATE meta SET gc_horizon = MAX(gc_horizon, ?) WHERE id = 1", (max_seq,))
    conn.execute("UPDATE meta SET last_gc_at = ? WHERE id = 1", (now_ms,))
    conn.commit()

    return {"items_purged": items_purged, "lists_purged": lists_purged}


def maybe_run(conn) -> None:
    """Run GC iff it last ran (or has never run) more than ~24h ago."""
    now = _current_now_ms()
    row = conn.execute("SELECT last_gc_at FROM meta WHERE id = 1").fetchone()
    if now - row["last_gc_at"] >= MAYBE_RUN_INTERVAL_MS:
        run(conn, now)
