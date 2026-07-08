import click
from flask import current_app
from flask.cli import with_appcontext

from . import EXTENSION_KEY
from . import accounts
from . import db as db_module
from .errors import ApiError


@click.group("shoppinglist")
def shoppinglist_cli():
    """Shopping-list server administration commands."""


@shoppinglist_cli.command("init-db")
@with_appcontext
def init_db_command():
    config = current_app.extensions[EXTENSION_KEY]
    conn = db_module.connect(config["database_path"])
    try:
        db_module.init_db(conn)
    finally:
        conn.close()
    click.echo(f"Initialized database at {config['database_path']}")


@shoppinglist_cli.command("reset-password")
@click.argument("email")
@with_appcontext
def reset_password_command(email):
    config = current_app.extensions[EXTENSION_KEY]
    conn = db_module.connect(config["database_path"])
    try:
        new_password = accounts.reset_password(conn, email)
    except ApiError as exc:
        raise click.ClickException(exc.message) from exc
    finally:
        conn.close()
    click.echo(f"New password for {email}: {new_password}")
