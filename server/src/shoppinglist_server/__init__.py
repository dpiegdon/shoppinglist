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
    bp = Blueprint("shoppinglist_server", __name__, url_prefix=url_prefix)

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

    @bp.teardown_app_request
    def _close_db(exception=None):
        conn = g.pop("shoppinglist_db", None)
        if conn is not None:
            conn.close()

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
