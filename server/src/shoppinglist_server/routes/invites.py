from flask import g, jsonify, request

from .. import audit, get_config, get_db, invites
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
        # invite_id and list_id, never the token (it is a bearer credential) and never the
        # invited address (T-121).
        audit.record(
            "invite.minted",
            account_id=g.account.id,
            invite_id=result["invite_id"],
            list_id=list_id,
        )
        return jsonify(result), 201

    @bp.route("/invites/<invite_id>", methods=["DELETE"])
    @authed
    def revoke_invite_view(invite_id):
        conn = get_db()
        invites.revoke(conn, g.account.id, invite_id)
        conn.commit()
        audit.record("invite.revoked", account_id=g.account.id, invite_id=invite_id)
        return "", 204

    @bp.route("/invites/redeem", methods=["POST"])
    @authed
    def redeem_invite_view():
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        config = get_config()
        list_id = invites.redeem(conn, config["invite_hmac_key"], g.account, data.get("token"))
        conn.commit()
        # The membership grant is the security-relevant event: this is how an account gains access
        # to someone else's data, so it is the one an operator needs to be able to reconstruct.
        audit.record("invite.redeemed", account_id=g.account.id, list_id=list_id)
        return jsonify({"list_id": list_id}), 200
