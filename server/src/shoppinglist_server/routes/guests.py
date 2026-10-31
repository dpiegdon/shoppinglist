"""A ledger's guests (T-364): add, rename, lock, remove, claim and unclaim.

Every endpoint answers `403 not_a_member` first, uniformly whether or not the list exists, then
the checks of guests.py in the order the wire contract gives them.
"""

from flask import g, jsonify

from .. import get_db, guests, invites
from ..auth import authed
from ..errors import ApiError
from ..request_body import json_body


def register_routes(bp):
    def _require_member(conn, list_id):
        if not invites.is_member(conn, g.shoppinglist_account.id, list_id):
            raise ApiError(403, "not_a_member", "You are not a member of this list.")

    @bp.route("/lists/<list_id>/guests", methods=["POST"])
    @authed
    def add_guest_view(list_id):
        data = json_body()
        conn = get_db()
        _require_member(conn, list_id)
        guest = guests.add(conn, g.shoppinglist_account.id, list_id, data.get("name"))
        conn.commit()
        return jsonify(guest), 201

    @bp.route("/lists/<list_id>/guests/<guest_id>", methods=["PATCH"])
    @authed
    def update_guest_view(list_id, guest_id):
        data = json_body()
        conn = get_db()
        _require_member(conn, list_id)
        guest = guests.update(conn, g.shoppinglist_account.id, list_id, guest_id, data)
        conn.commit()
        return jsonify(guest), 200

    @bp.route("/lists/<list_id>/guests/<guest_id>", methods=["DELETE"])
    @authed
    def remove_guest_view(list_id, guest_id):
        conn = get_db()
        _require_member(conn, list_id)
        guests.remove(conn, g.shoppinglist_account.id, list_id, guest_id)
        conn.commit()
        return "", 204

    @bp.route("/lists/<list_id>/guests/<guest_id>/claim", methods=["POST"])
    @authed
    def claim_guest_view(list_id, guest_id):
        conn = get_db()
        _require_member(conn, list_id)
        guest = guests.claim(conn, g.shoppinglist_account.id, list_id, guest_id)
        conn.commit()
        return jsonify(guest), 200

    @bp.route("/lists/<list_id>/guests/<guest_id>/claim", methods=["DELETE"])
    @authed
    def unclaim_guest_view(list_id, guest_id):
        conn = get_db()
        _require_member(conn, list_id)
        guest = guests.unclaim(conn, g.shoppinglist_account.id, list_id, guest_id)
        conn.commit()
        return jsonify(guest), 200
