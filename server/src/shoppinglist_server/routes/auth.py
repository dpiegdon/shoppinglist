from flask import g, jsonify, request

from .. import get_db
from ..auth import authed
from ..auth import login as auth_login
from ..auth import logout as auth_logout
from ..auth import register as auth_register


def register_routes(bp):
    @bp.route("/register", methods=["POST"])
    def register_view():
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        account_id = auth_register(conn, data.get("email"), data.get("password"))
        return jsonify({"account_id": account_id}), 201

    @bp.route("/login", methods=["POST"])
    def login_view():
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        token, account_id = auth_login(
            conn, data.get("email"), data.get("password"), data.get("device_label")
        )
        row = conn.execute(
            "SELECT email FROM accounts WHERE id = ?", (account_id,)
        ).fetchone()
        return (
            jsonify({"token": token, "account_id": account_id, "email": row["email"]}),
            200,
        )

    @bp.route("/logout", methods=["POST"])
    @authed
    def logout_view():
        conn = get_db()
        auth_logout(conn, g.token)
        return "", 204
