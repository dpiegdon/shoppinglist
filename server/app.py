import os

from flask import Flask

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server.cli import shoppinglist_cli


def create_app() -> Flask:
    app = Flask(__name__)
    app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "dev-secret-key")

    database_path = os.environ.get("DATABASE_PATH", "shoppinglist.db")
    invite_hmac_key = os.environ.get("INVITE_HMAC_KEY", "dev-invite-hmac-key").encode()
    base_url = os.environ.get("BASE_URL", "http://localhost:5000")

    # Dev convenience only: auto-initialize the schema (idempotent) so
    # `flask --app server/app.py run` works immediately with no separate
    # step. A real deployment still runs `flask shoppinglist init-db`
    # explicitly as part of provisioning (see README).
    conn = db_module.connect(database_path)
    try:
        db_module.init_db(conn)
    finally:
        conn.close()

    bp = create_blueprint(
        database_path=database_path,
        invite_hmac_key=invite_hmac_key,
        base_url=base_url,
    )
    app.register_blueprint(bp)
    app.cli.add_command(shoppinglist_cli)

    return app


app = create_app()
