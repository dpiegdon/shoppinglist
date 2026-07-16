from flask import g, jsonify, request

from .. import accounts, get_db
from ..auth import authed


def register_routes(bp):
    @bp.route("/account/change-password", methods=["POST"])
    @authed
    def change_password_view():
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        accounts.change_password(
            conn,
            g.account.id,
            data.get("current_password"),
            data.get("new_password"),
            g.token,
        )
        return "", 204

    @bp.route("/account/change-email", methods=["POST"])
    @authed
    def change_email_view():
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        accounts.change_email(conn, g.account.id, data.get("password"), data.get("new_email"))
        return "", 204

    @bp.route("/account/sessions", methods=["GET"])
    @authed
    def list_sessions_view():
        conn = get_db()
        sessions = accounts.list_sessions(conn, g.account.id, g.token)
        return jsonify({"sessions": sessions}), 200

    @bp.route("/account/sessions/<session_id>", methods=["DELETE"])
    @authed
    def revoke_session_view(session_id):
        conn = get_db()
        accounts.revoke_session(conn, g.account.id, session_id)
        return "", 204

    @bp.route("/account", methods=["DELETE"])
    @authed
    def delete_account_view():
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        accounts.delete_account(conn, g.account.id, data.get("password"))
        return "", 204

    @bp.route("/settings", methods=["GET"])
    @authed
    def get_settings_view():
        conn = get_db()
        return jsonify(accounts.get_settings(conn, g.account.id)), 200

    @bp.route("/settings", methods=["PATCH"])
    @authed
    def update_settings_view():
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        # PATCH, not PUT (T-87): only forward keys the caller actually sent, so an
        # absent key means "leave unchanged" rather than being coerced to None and
        # wiping the column. A present `initials: null` still reaches update_settings
        # as None, which it treats as "clear the override back to the derived default".
        kwargs = {}
        if "default_currency" in data:
            kwargs["default_currency"] = data["default_currency"]
        if "initials" in data:
            kwargs["initials"] = data["initials"]
        result = accounts.update_settings(conn, g.account.id, **kwargs)
        return jsonify(result), 200
