import json

from flask import g, jsonify

from .. import accounts, get_db, invites
from ..auth import authed
from ..errors import ApiError


def register_routes(bp):
    @bp.route("/lists", methods=["GET"])
    @authed
    def get_lists_view():
        conn = get_db()
        rows = conn.execute(
            "SELECT lists.id, lists.name, lists.category_order "
            "FROM lists JOIN memberships ON memberships.list_id = lists.id "
            "WHERE memberships.account_id = ? AND lists.deleted = 0 "
            "ORDER BY lists.name",
            (g.account.id,),
        ).fetchall()
        lists = [
            {
                "id": row["id"],
                "name": row["name"],
                "category_order": json.loads(row["category_order"]),
            }
            for row in rows
        ]
        return jsonify({"lists": lists}), 200

    @bp.route("/lists/<list_id>/members", methods=["GET"])
    @authed
    def list_members_view(list_id):
        conn = get_db()
        # Uniform 403 regardless of whether list_id exists at all, so a
        # non-member can't distinguish "not found" from "not yours" (no
        # existence-leak).
        if not invites.is_member(conn, g.account.id, list_id):
            raise ApiError(403, "not_a_member", "You are not a member of this list.")

        members = [
            {
                "account_id": row["account_id"],
                "email": row["email"],
                # Resolved default-or-override (T-64) so clients rendering the last-touched-by
                # badge don't need a second per-account lookup.
                "initials": accounts.resolve_initials(row["email"], row["initials"]),
                "joined_at": row["joined_at"],
            }
            for row in conn.execute(
                "SELECT accounts.id AS account_id, accounts.email AS email, "
                "account_settings.initials AS initials, memberships.joined_at AS joined_at "
                "FROM memberships "
                "JOIN accounts ON accounts.id = memberships.account_id "
                "JOIN account_settings ON account_settings.account_id = accounts.id "
                "WHERE memberships.list_id = ? ORDER BY memberships.joined_at",
                (list_id,),
            )
        ]
        pending_invites = [
            {"id": row["id"], "invited_email": row["invited_email"], "expires_at": row["expires_at"]}
            for row in conn.execute(
                "SELECT id, invited_email, expires_at FROM invites "
                "WHERE list_id = ? AND revoked = 0 AND used_at IS NULL "
                "ORDER BY created_at",
                (list_id,),
            )
        ]
        return jsonify({"members": members, "invites": pending_invites}), 200

    @bp.route("/lists/<list_id>/leave", methods=["POST"])
    @authed
    def leave_list_view(list_id):
        conn = get_db()
        invites.leave(conn, g.account.id, list_id)
        conn.commit()
        return "", 204
