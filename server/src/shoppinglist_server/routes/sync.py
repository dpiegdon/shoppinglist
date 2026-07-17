from flask import g, jsonify, request

from .. import gc, get_db
from .. import sync as sync_engine
from ..auth import authed
from ..errors import ApiError


def _touched_list_ids(conn, changes):
    """List ids actually touched by this request, read back from the DB so we
    never trust a client-supplied (and possibly wrong) list_id.

    Runs only after apply_changes has already rejected any structurally junk
    payload, but stays defensive against non-dict entries / non-string ids so a
    malformed shape can never turn into a crash (T-85)."""
    if not isinstance(changes, dict):
        return set()
    lists = changes.get("lists") or []
    items = changes.get("items") or []
    ids = set()
    if isinstance(lists, list):
        ids = {
            obj["id"] for obj in lists
            if isinstance(obj, dict) and isinstance(obj.get("id"), str) and obj["id"]
        }
    if isinstance(items, list):
        for obj in items:
            if not isinstance(obj, dict) or not isinstance(obj.get("id"), str):
                continue
            row = conn.execute(
                "SELECT list_id FROM items WHERE id = ?", (obj["id"],)
            ).fetchone()
            if row is not None:
                ids.add(row["list_id"])
    return ids


def register_routes(bp):
    @bp.route("/sync", methods=["POST"])
    @authed
    def sync_view():
        data = request.get_json(force=True, silent=True) or {}
        cursor = data.get("cursor")
        # Bounded above too: the cursor binds into SQL, and anything past SQLite's
        # signed int64 range raises OverflowError at bind time (T-85).
        if (
            not isinstance(cursor, int)
            or isinstance(cursor, bool)
            or cursor < 0
            or cursor > sync_engine.SQLITE_INT_MAX
        ):
            raise ApiError(422, "invalid_cursor", "cursor must be a non-negative integer.")
        device_id = data.get("device_id") or ""
        if not isinstance(device_id, str):
            raise ApiError(422, "invalid_device_id", "device_id must be a string.")
        full_lists = data.get("full_lists") or []
        if not isinstance(full_lists, list) or not all(isinstance(x, str) for x in full_lists):
            raise ApiError(422, "invalid_full_lists", "full_lists must be a list of strings.")
        changes = data.get("changes") or {}

        conn = get_db()
        gc.maybe_run(conn)  # opportunistic, at most ~once/day (Spec §6)

        cursor_error = None
        try:
            sync_engine.check_cursor(conn, cursor)
        except ApiError as exc:
            cursor_error = exc

        # Pushed changes are applied regardless of cursor staleness (Spec §6):
        # a stale cursor only affects what we can tell the client it's missing.
        sync_engine.apply_changes(conn, g.account.id, device_id, changes)
        for list_id in _touched_list_ids(conn, changes):
            sync_engine.name_merge(conn, list_id)

        if cursor_error is not None:
            conn.commit()
            raise cursor_error

        result = sync_engine.delta(conn, g.account.id, cursor, full_lists)
        conn.commit()
        return jsonify(result), 200
