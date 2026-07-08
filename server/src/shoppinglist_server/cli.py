import click
from flask import current_app

from . import EXTENSION_KEY
from . import db as db_module


@click.group("shoppinglist")
def shoppinglist_cli():
    """Shopping-list server administration commands."""


@shoppinglist_cli.command("init-db")
def init_db_command():
    config = current_app.extensions[EXTENSION_KEY]
    conn = db_module.connect(config["database_path"])
    try:
        db_module.init_db(conn)
    finally:
        conn.close()
    click.echo(f"Initialized database at {config['database_path']}")
