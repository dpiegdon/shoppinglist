from flask import g, jsonify, request

from .. import accounts, get_config, get_db, server_settings
from ..auth import admin_required, is_admin_email
from ..errors import ApiError


def register_routes(bp):
    """Admin-only endpoints (T-107). Admin identity is the instance's static admin_emails config —
    unwritable at runtime — so these can't be used to escalate. Destructive actions re-verify the
    admin's OWN password (step-up)."""

    @bp.route("/admin/users", methods=["GET"])
    @admin_required
    def admin_users_view():
        conn = get_db()
        admin_emails = get_config().get("admin_emails", frozenset())
        return jsonify({"users": accounts.list_all_accounts(conn, admin_emails)}), 200

    @bp.route("/admin/server-settings", methods=["GET"])
    @admin_required
    def admin_get_server_settings_view():
        conn = get_db()
        default = get_config().get("allow_registration", True)
        return (
            jsonify({"allow_registration": server_settings.effective_allow_registration(conn, default)}),
            200,
        )

    @bp.route("/admin/server-settings", methods=["PUT"])
    @admin_required
    def admin_set_server_settings_view():
        data = request.get_json(force=True, silent=True) or {}
        allow = data.get("allow_registration")
        if not isinstance(allow, bool):
            raise ApiError(422, "invalid_request", "allow_registration must be true or false.")
        conn = get_db()
        # Runtime override only — resets to the config default on restart (T-107).
        server_settings.set_registration_override(conn, allow)
        return jsonify({"allow_registration": allow}), 200

    @bp.route("/admin/users/<account_id>/reset-password", methods=["POST"])
    @admin_required
    def admin_reset_password_view(account_id):
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        accounts.require_password(conn, g.account.id, data.get("password"))  # step-up
        new_password = accounts.admin_reset_password(conn, account_id)
        # Shown once to the admin, relayed out of band — same trust model as invite tokens.
        return jsonify({"password": new_password}), 200

    @bp.route("/admin/users/<account_id>", methods=["DELETE"])
    @admin_required
    def admin_delete_user_view(account_id):
        data = request.get_json(force=True, silent=True) or {}
        conn = get_db()
        accounts.require_password(conn, g.account.id, data.get("password"))  # step-up
        if account_id == g.account.id:
            raise ApiError(
                403, "cannot_delete_self", "Delete your own account from your settings, not here."
            )
        target = conn.execute(
            "SELECT email FROM accounts WHERE id = ?", (account_id,)
        ).fetchone()
        if target is None:
            raise ApiError(404, "account_not_found", "No account with this id exists.")
        # An admin's email would stay admin-listed but point at nothing — and admins shouldn't be
        # deletable via the API anyway (config owns admin identity). Remove them from config instead.
        if is_admin_email(target["email"], get_config().get("admin_emails", frozenset())):
            raise ApiError(
                403, "cannot_delete_admin", "Admins can't be deleted here — edit the server config."
            )
        accounts.admin_delete_account(conn, account_id)
        return "", 204
