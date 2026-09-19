import click
from flask import current_app
from flask.cli import with_appcontext

from . import accounts
from . import db as db_module
from . import gc, get_config_by_name, housekeeping
from .auth import now_ms
from .errors import ApiError

_instance_option = click.option(
    "--instance",
    "-i",
    "instance_name",
    default=None,
    help="Blueprint instance name (only needed when multiple instances are mounted).",
)


def _resolve_config(instance_name):
    try:
        return get_config_by_name(current_app, instance_name)
    except ValueError as exc:
        raise click.ClickException(str(exc)) from exc


@click.group("shoppinglist")
def shoppinglist_cli():
    """Shopping-list server administration commands."""


@shoppinglist_cli.command("init-db")
@_instance_option
@with_appcontext
def init_db_command(instance_name):
    config = _resolve_config(instance_name)
    conn = db_module.connect(config["database_path"])
    try:
        db_module.init_db(conn)
    finally:
        conn.close()
    click.echo(f"Initialized database at {config['database_path']}")


@shoppinglist_cli.command("reset-password")
@click.argument("email")
@_instance_option
@with_appcontext
def reset_password_command(email, instance_name):
    """Reset the account's password and sign out all of its devices (T-92)."""
    config = _resolve_config(instance_name)
    conn = db_module.connect(config["database_path"])
    try:
        new_password = accounts.reset_password(conn, email)
    except ApiError as exc:
        raise click.ClickException(exc.message) from exc
    finally:
        conn.close()
    click.echo(f"New password for {email}: {new_password}")
    click.echo("All existing sessions for this account have been signed out.")


@shoppinglist_cli.command("gc")
@_instance_option
@with_appcontext
def gc_command(instance_name):
    config = _resolve_config(instance_name)
    conn = db_module.connect(config["database_path"])
    try:
        result = gc.run(conn, now_ms())
    finally:
        conn.close()
    click.echo(
        f"GC purged {result['items_purged']} items, {result['lists_purged']} lists, "
        f"{result['invites_purged']} invites, {result['sessions_purged']} expired sessions."
    )


@shoppinglist_cli.command("audit")
@_instance_option
@with_appcontext
def audit_command(instance_name):
    """Check the database invariants the schema cannot state, and report violations (T-218).

    Read-only: it never deletes or repairs anything, so it is safe to run at any time against a
    live server. Exits 1 if any violation is found and 0 otherwise, so it can be run straight
    from cron or a monitoring check. The same checks run automatically as part of the
    housekeeping sweep (first request of a server run, then about weekly).
    """
    config = _resolve_config(instance_name)
    conn = db_module.connect(config["database_path"])
    try:
        findings = housekeeping.audit(conn, now_ms())
    finally:
        conn.close()
    if not findings:
        click.echo("Audit found no violations.")
        return
    for finding in findings:
        samples = ", ".join(finding.samples)
        click.echo(f"{finding.check}: {finding.count} row(s); e.g. {samples}")
    raise SystemExit(1)
