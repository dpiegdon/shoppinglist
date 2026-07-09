from flask import g, jsonify, request

from .. import gc, get_db
from .. import sync as sync_engine
from ..auth import authed
from ..errors import ApiError


def _touched_list_ids(conn, changes):
    """List ids actually touched by this request, read back from the DB so we
    never trust a client-supplied (and possibly wrong) list_id."""
    ids = {obj.get("id") for obj in changes.get("lists", []) if obj.get("id")}
    for obj in changes.get("items", []):
        row = conn.execute(
            "SELECT list_id FROM items WHERE id = ?", (obj.get("id"),)
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
        if not isinstance(cursor, int) or isinstance(cursor, bool) or cursor < 0:
            raise ApiError(422, "invalid_cursor", "cursor must be a non-negative integer.")
        device_id = data.get("device_id") or ""
        full_lists = data.get("full_lists") or []
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
