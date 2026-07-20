from flask import g, jsonify, request

from .. import get_config, get_db, server_settings
from ..auth import authed, is_admin_email
from ..auth import login as auth_login
from ..auth import logout as auth_logout
from ..auth import register as auth_register
from ..errors import ApiError


def register_routes(bp):
    @bp.route("/register", methods=["POST"])
    def register_view():
        # Operator opt-out for invite-only/closed instances (T-61), possibly overridden at runtime
        # by an admin (T-107). The effective flag = a live override for this server run, else the
        # per-instance config default.
        conn = get_db()
        default = get_config().get("allow_registration", True)
        if not server_settings.effective_allow_registration(conn, default):
            raise ApiError(
                403, "registration_disabled", "Registration is disabled on this server."
            )
        data = request.get_json(force=True, silent=True) or {}
        account_id = auth_register(conn, data.get("email"), data.get("password"))
        return jsonify({"account_id": account_id}), 201

    @bp.route("/registration-status", methods=["GET"])
    def registration_status_view():
        # Unauthenticated: the login/register screen reads the EFFECTIVE flag here rather than the
        # index.html meta tag baked at startup, which can't reflect a runtime admin toggle (T-107).
        conn = get_db()
        default = get_config().get("allow_registration", True)
        return (
            jsonify({"allow_registration": server_settings.effective_allow_registration(conn, default)}),
            200,
        )

    @bp.route("/login", methods=["POST"])
    def login_view():
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        token, account_id = auth_login(
            conn,
            data.get("email"),
            data.get("password"),
            data.get("device_label"),
            # Optional (T-104): picks the session's inactivity window. Absent for
            # pre-T-104 clients, which fall back to the long default.
            data.get("platform"),
        )
        row = conn.execute(
            "SELECT email FROM accounts WHERE id = ?", (account_id,)
        ).fetchone()
        return (
            jsonify(
                {
                    "token": token,
                    "account_id": account_id,
                    "email": row["email"],
                    # So the client can show/hide the admin tab (T-107); derived from static config.
                    "is_admin": is_admin_email(
                        row["email"], get_config().get("admin_emails", frozenset())
                    ),
                }
            ),
            200,
        )

    @bp.route("/logout", methods=["POST"])
    @authed
    def logout_view():
        conn = get_db()
        auth_logout(conn, g.token)
        return "", 204
