import json

from flask import g, jsonify

from .. import accounts, audit, closing, get_db, invites
from ..auth import authed, now_ms
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
            (g.shoppinglist_account.id,),
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
        if not invites.is_member(conn, g.shoppinglist_account.id, list_id):
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
                # LEFT, not JOIN (T-258): sync._rosters uses LEFT here for the same roster data,
                # and the LEFT is the safe one of the two — a member whose settings row is
                # missing for any reason is still a member and belongs in this list, not silently
                # dropped from it. resolve_initials already handles a null initials value.
                "LEFT JOIN account_settings ON account_settings.account_id = accounts.id "
                "WHERE memberships.list_id = ? ORDER BY memberships.joined_at",
                (list_id,),
            )
        ]
        pending_invites = [
            {
                "id": row["id"],
                "invited_email": row["invited_email"],
                "expires_at": row["expires_at"],
            }
            for row in conn.execute(
                "SELECT id, invited_email, expires_at FROM invites "
                # Live ones only, the same rule as GET /invites/pending (T-316): an expired invite
                # can no longer be redeemed, so it is no longer pending.
                "WHERE list_id = ? AND revoked = 0 AND used_at IS NULL AND expires_at > ? "
                "ORDER BY created_at",
                (list_id, now_ms()),
            )
        ]
        return jsonify({"members": members, "invites": pending_invites}), 200

    def _vote_state(conn, list_id):
        return {
            "close_votes": closing.votes(conn, list_id),
            "closed_at": closing.closed_at(conn, list_id),
        }

    def _require_member(conn, list_id):
        # Uniform 403 whether or not the list exists, like GET /members above: a non-member must
        # not be able to probe which list ids are in use.
        if not invites.is_member(conn, g.shoppinglist_account.id, list_id):
            raise ApiError(403, "not_a_member", "You are not a member of this list.")

    @bp.route("/lists/<list_id>/close-votes", methods=["POST"])
    @authed
    def cast_close_vote_view(list_id):
        """Agree to close this expenses list (T-157).

        The list closes in this same transaction if this was the last current member's vote, so a
        client never sees a state where everyone has agreed but the list is still open.
        """
        conn = get_db()
        _require_member(conn, list_id)
        closing.cast_vote(conn, g.shoppinglist_account.id, list_id)
        state = _vote_state(conn, list_id)
        conn.commit()
        audit.record("list.close_vote_cast", account_id=g.shoppinglist_account.id, list_id=list_id)
        if state["closed_at"] is not None:
            audit.record("list.closed", account_id=g.shoppinglist_account.id, list_id=list_id)
        return jsonify(state), 200

    @bp.route("/lists/<list_id>/close-votes", methods=["DELETE"])
    @authed
    def withdraw_close_vote_view(list_id):
        conn = get_db()
        _require_member(conn, list_id)
        closing.withdraw_vote(conn, g.shoppinglist_account.id, list_id)
        state = _vote_state(conn, list_id)
        conn.commit()
        audit.record(
            "list.close_vote_withdrawn", account_id=g.shoppinglist_account.id, list_id=list_id
        )
        return jsonify(state), 200

    @bp.route("/lists/<list_id>/leave", methods=["POST"])
    @authed
    def leave_list_view(list_id):
        conn = get_db()
        invites.leave(conn, g.shoppinglist_account.id, list_id)
        conn.commit()
        return "", 204
