from flask import g, jsonify, request

from .. import get_config, get_db, invites
from ..auth import authed


def register_routes(bp):
    @bp.route("/lists/<list_id>/invites", methods=["POST"])
    @authed
    def create_invite_view(list_id):
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        config = get_config()
        result = invites.mint(
            conn,
            config["invite_hmac_key"],
            config["base_url"],
            list_id,
            data.get("invited_email"),
            g.account.id,
        )
        conn.commit()
        return jsonify(result), 201

    @bp.route("/invites/<invite_id>", methods=["DELETE"])
    @authed
    def revoke_invite_view(invite_id):
        conn = get_db()
        invites.revoke(conn, g.account.id, invite_id)
        conn.commit()
        return "", 204

    @bp.route("/invites/redeem", methods=["POST"])
    @authed
    def redeem_invite_view():
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        config = get_config()
        list_id = invites.redeem(conn, config["invite_hmac_key"], g.account, data.get("token"))
        conn.commit()
        return jsonify({"list_id": list_id}), 200
