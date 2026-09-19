from flask import g, jsonify

from .. import get_db
from .. import sync as sync_engine
from ..auth import authed
from ..errors import ApiError
from ..request_body import json_body


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
            obj["id"]
            for obj in lists
            if isinstance(obj, dict) and isinstance(obj.get("id"), str) and obj["id"]
        }
    if isinstance(items, list):
        for obj in items:
            if not isinstance(obj, dict) or not isinstance(obj.get("id"), str):
                continue
            row = conn.execute("SELECT list_id FROM items WHERE id = ?", (obj["id"],)).fetchone()
            if row is not None:
                ids.add(row["list_id"])
    return ids


def register_routes(bp):
    @bp.route("/sync", methods=["POST"])
    @authed
    def sync_view():
        data = json_body()
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
        # Deduplicated before the cap (T-237): a repeated id costs delta another snapshot query
        # pair and adds nothing, since the response merges rows by id. dict.fromkeys keeps
        # first-seen order, so which entry a 403 not_a_member blames stays deterministic.
        full_lists = list(dict.fromkeys(full_lists))
        if len(full_lists) > sync_engine.MAX_FULL_LISTS_PER_SYNC:
            raise ApiError(
                422,
                "invalid_full_lists",
                f"full_lists may name at most {sync_engine.MAX_FULL_LISTS_PER_SYNC} lists.",
            )
        changes = data.get("changes") or {}

        conn = get_db()
        # No gc.maybe_run here any more (T-218): the blueprint's after_request hook in
        # __init__.py runs it — and the wider housekeeping sweep — for every authenticated
        # request, of which this is one. Keeping a second call would have been redundant, and
        # would have kept alive the impression that /sync is the only thing that sweeps.

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
