import os

from flask import Flask

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server.cli import shoppinglist_cli


# Never a usable value — only a marker for the check in create_app() below (T-122).
_REJECTED_INVITE_HMAC_KEY = "dev-invite-hmac-key"


def create_app() -> Flask:
    app = Flask(__name__)
    # Unlike INVITE_HMAC_KEY below, a weak value here is harmless: the blueprint uses no Flask
    # sessions and no cookies (auth is bearer tokens), so nothing is signed with it. Kept only as
    # ordinary Flask hygiene in case a host app grows something that does.
    app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "dev-secret-key")

    database_path = os.environ.get("DATABASE_PATH", "shoppinglist.db")
    # Refuse to start rather than silently run on a publicly-known signing key (T-122). This
    # previously defaulted to the literal below, so forgetting the variable produced a server that
    # worked perfectly and was wholly insecure: anyone who has read this (public) repository could
    # mint valid invite tokens for arbitrary lists and join them. A missing key is an operator
    # mistake that must be loud, and the failure mode here was as silent as it gets.
    invite_hmac_key_value = os.environ.get("INVITE_HMAC_KEY", "")
    if not invite_hmac_key_value or invite_hmac_key_value == _REJECTED_INVITE_HMAC_KEY:
        raise RuntimeError(
            "INVITE_HMAC_KEY is unset or still the published dev default. Invite tokens are "
            "signed with it, so anyone holding it can mint an invite for any list. Generate one "
            "with:  python -c 'import secrets; print(secrets.token_hex(32))'  and keep it stable "
            "(rotating it invalidates outstanding invite links). See server/README.md."
        )
    invite_hmac_key = invite_hmac_key_value.encode()
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
