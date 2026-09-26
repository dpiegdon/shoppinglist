from flask import g, jsonify

from .. import accounts, audit, get_config, get_db, server_settings
from ..auth import admin_required, is_admin_email
from ..errors import ApiError
from ..request_body import json_body


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

    def _server_settings_body(conn):
        default = get_config().get("allow_registration", True)
        return {
            "allow_registration": server_settings.effective_allow_registration(conn, default),
            "message": server_settings.get_message(conn),
        }

    @bp.route("/admin/server-settings", methods=["GET"])
    @admin_required
    def admin_get_server_settings_view():
        return jsonify(_server_settings_body(get_db())), 200

    @bp.route("/admin/server-settings", methods=["PUT"])
    @admin_required
    def admin_set_server_settings_view():
        # Partial (T-315): each setting is optional, a missing one stays as it is, and at least
        # one must be present. Both are validated before either is written.
        data = json_body()
        if "allow_registration" not in data and "message" not in data:
            raise ApiError(422, "invalid_request", "Send allow_registration, message, or both.")
        allow = data.get("allow_registration")
        if "allow_registration" in data and not isinstance(allow, bool):
            raise ApiError(422, "invalid_request", "allow_registration must be true or false.")
        message = None
        if "message" in data:
            message = server_settings.validate_message(data["message"])
        conn = get_db()
        if "allow_registration" in data:
            # Runtime override only — resets to the config default on restart (T-107).
            server_settings.set_registration_override(conn, allow)
            audit.record(
                "admin.registration_toggled",
                account_id=g.shoppinglist_account.id,
                allow_registration=allow,
            )
        if message is not None:
            # Durable (T-315). The audit log gets the length only, never the text.
            server_settings.set_message(conn, message)
            if message:
                audit.record(
                    "admin.message_set", account_id=g.shoppinglist_account.id, length=len(message)
                )
            else:
                audit.record("admin.message_cleared", account_id=g.shoppinglist_account.id)
        return jsonify(_server_settings_body(conn)), 200

    @bp.route("/admin/users/<account_id>/reset-password", methods=["POST"])
    @admin_required
    def admin_reset_password_view(account_id):
        data = json_body()
        conn = get_db()
        accounts.require_password(conn, g.shoppinglist_account.id, data.get("password"))  # step-up
        new_password = accounts.admin_reset_password(conn, account_id)
        # Both parties recorded: who did it and to whom. The password itself never goes near the
        # log (audit.py redacts the key even if a future edit passes it).
        audit.record(
            "admin.password_reset",
            account_id=g.shoppinglist_account.id,
            target_account_id=account_id,
        )
        # Shown once to the admin, relayed out of band — same trust model as invite tokens.
        return jsonify({"password": new_password}), 200

    @bp.route("/admin/users/<account_id>", methods=["DELETE"])
    @admin_required
    def admin_delete_user_view(account_id):
        data = json_body()
        conn = get_db()
        accounts.require_password(conn, g.shoppinglist_account.id, data.get("password"))  # step-up
        if account_id == g.shoppinglist_account.id:
            raise ApiError(
                403, "cannot_delete_self", "Delete your own account from your settings, not here."
            )
        target = conn.execute("SELECT email FROM accounts WHERE id = ?", (account_id,)).fetchone()
        if target is None:
            raise ApiError(404, "account_not_found", "No account with this id exists.")
        # An admin's email would stay admin-listed but point at nothing — and admins shouldn't be
        # deletable via the API anyway (config owns admin identity). Remove them from config instead.
        if is_admin_email(target["email"], get_config().get("admin_emails", frozenset())):
            raise ApiError(
                403, "cannot_delete_admin", "Admins can't be deleted here — edit the server config."
            )
        accounts.admin_delete_account(conn, account_id)
        audit.record(
            "admin.user_deleted", account_id=g.shoppinglist_account.id, target_account_id=account_id
        )
        return "", 204
