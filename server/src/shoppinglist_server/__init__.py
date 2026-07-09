from flask import Blueprint, current_app, g, jsonify

from . import db as db_module
from .errors import ApiError

EXTENSION_KEY = "shoppinglist_server"


def create_blueprint(
    database_path: str,
    invite_hmac_key: bytes,
    base_url: str,
    url_prefix: str = "/api/v1",
) -> Blueprint:
    bp = Blueprint(
        "shoppinglist_server", __name__, url_prefix=url_prefix, template_folder="templates"
    )

    config = {
        "database_path": database_path,
        "invite_hmac_key": invite_hmac_key,
        "base_url": base_url,
    }

    @bp.record_once
    def _record(setup_state):
        app = setup_state.app
        app.extensions[EXTENSION_KEY] = config
        app.register_error_handler(ApiError, _handle_api_error)

        # The invite landing page is deliberately NOT under url_prefix (Spec
        # §5's share URL is https://<server>/invite/<token>, no /api/v1), so
        # it's registered directly on the app rather than through `bp`. A
        # blueprint's template_folder is searched app-wide regardless of which
        # blueprint (if any) a view belongs to, so invite.html still resolves.
        from .routes.landing import register_routes as register_landing_routes

        register_landing_routes(app)

    @bp.teardown_app_request
    def _close_db(exception=None):
        conn = g.pop("shoppinglist_db", None)
        if conn is not None:
            conn.close()

    from .routes.account import register_routes as register_account_routes
    from .routes.auth import register_routes as register_auth_routes
    from .routes.invites import register_routes as register_invites_routes
    from .routes.lists import register_routes as register_lists_routes
    from .routes.sync import register_routes as register_sync_routes

    register_auth_routes(bp)
    register_account_routes(bp)
    register_lists_routes(bp)
    register_invites_routes(bp)
    register_sync_routes(bp)

    return bp


def _handle_api_error(err: ApiError):
    return jsonify({"error": err.code, "message": err.message}), err.status


def get_config() -> dict:
    return current_app.extensions[EXTENSION_KEY]


def get_db():
    if "shoppinglist_db" not in g:
        config = get_config()
        g.shoppinglist_db = db_module.connect(config["database_path"])
    return g.shoppinglist_db
