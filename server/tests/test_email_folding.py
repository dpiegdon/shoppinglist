"""One Unicode case folding for e-mail addresses (T-328).

Every comparison of two addresses goes through emails.normalize_email (NFC, casefold, NFC) and the
stored `accounts.email_normalized` / `invites.invited_email_normalized` columns. The pairs below are
chosen so each check fails under the rules it replaced: 'É'/'é' are equal under folding but not
under SQLite's ASCII-only lower(), and 'STRASSE'/'straße' are equal under folding but not under
Python's .lower().
"""

import re
import sqlite3
import tempfile
import unicodedata
from importlib import resources
from pathlib import Path
from unittest import mock

import pytest
from flask import Flask
from hypothesis import example, given
from hypothesis import strategies as st

from shoppinglist_server import accounts, auth, create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server import invites, migrations, sync
from shoppinglist_server.errors import ApiError
from shoppinglist_server.migrations import MigrationRefused

PW = "password123"
KEY = b"test-invite-hmac-key"
BASE_URL = "http://testserver"


def _create_list(conn, account_id, list_id="list-1"):
    sync.apply_changes(
        conn,
        account_id,
        "dev",
        {
            "lists": [
                {
                    "id": list_id,
                    "created_at": 1000,
                    "fields": {
                        "name": {"value": "Groceries", "updated_at": 100, "updated_by": "dev"}
                    },
                }
            ]
        },
    )


def _account(conn, account_id):
    row = conn.execute("SELECT id, email FROM accounts WHERE id = ?", (account_id,)).fetchone()
    return auth.Account(id=row["id"], email=row["email"])


def _backdate_address(conn, account_id):
    """Make the account's address older than any invite minted from here on (T-234)."""
    conn.execute("UPDATE accounts SET email_set_at = 0 WHERE id = ?", (account_id,))
    conn.commit()


# ---- the rule itself --------------------------------------------------------------------------


def test_normalize_email_folds_case_fully_and_composes():
    assert accounts.normalize_email("É@X.co") == "é@x.co"
    assert accounts.normalize_email("STRASSE@x.co") == accounts.normalize_email("straße@x.co")
    # NFD input folds to the same form as NFC input.
    assert accounts.normalize_email(unicodedata.normalize("NFD", "É@x.co")) == "é@x.co"
    # The final NFC: 'ΐ' and its upper-case form fold to canonically equivalent strings, and
    # only composing again makes them one.
    assert accounts.normalize_email("ΐ@x.co") == accounts.normalize_email("ΐ@x.co".upper())
    assert accounts.normalize_email("  a@x.co ") == "a@x.co"


# ---- registration, login, change-email --------------------------------------------------------


def test_one_address_registers_once_whatever_its_case(db_conn):
    auth.register(db_conn, "é@example.com", PW)
    for other in ("É@example.com", "É@EXAMPLE.COM", unicodedata.normalize("NFD", "É@example.com")):
        with pytest.raises(ApiError) as exc:
            auth.register(db_conn, other, PW)
        assert exc.value.code == "email_taken"

    auth.register(db_conn, "straße@example.com", PW)
    with pytest.raises(ApiError) as exc:
        auth.register(db_conn, "STRASSE@example.com", PW)
    assert exc.value.code == "email_taken"


def test_login_accepts_every_spelling_and_shows_the_typed_one(client):
    assert client.post(
        "/api/v1/register", json={"email": "Émile@Example.com", "password": PW}
    ).status_code in (200, 201)
    for spelling in ("émile@example.com", "ÉMILE@EXAMPLE.COM", "Émile@Example.com"):
        resp = client.post(
            "/api/v1/login", json={"email": spelling, "password": PW, "device_label": "web"}
        )
        assert resp.status_code == 200, spelling
        # The address as typed at registration is what is shown, not the folded form.
        assert resp.get_json()["email"] == "Émile@Example.com"


def test_login_folds_beyond_lower(db_conn):
    account_id = auth.register(db_conn, "straße@example.com", PW)
    _token, logged_in = auth.login(db_conn, "STRASSE@EXAMPLE.COM", PW, "web")
    assert logged_in == account_id


def test_change_email_refuses_another_accounts_address_in_any_case(db_conn):
    auth.register(db_conn, "é@example.com", PW)
    other = auth.register(db_conn, "other@example.com", PW)
    with pytest.raises(ApiError) as exc:
        accounts.change_email(db_conn, other, PW, "É@example.com")
    assert exc.value.code == "email_taken"


def test_change_email_moves_the_folded_address_with_it(db_conn):
    account_id = auth.register(db_conn, "old@example.com", PW)
    accounts.change_email(db_conn, account_id, PW, "Neü@example.com")
    assert auth.login(db_conn, "NEÜ@example.com", PW, "web")[1] == account_id
    # The old address is free again, in any spelling.
    auth.register(db_conn, "OLD@example.com", PW)


def test_an_account_can_change_only_the_case_of_its_own_address(db_conn):
    account_id = auth.register(db_conn, "é@example.com", PW)
    accounts.change_email(db_conn, account_id, PW, "É@example.com")
    assert _account(db_conn, account_id).email == "É@example.com"


# ---- invites ----------------------------------------------------------------------------------


def _invite(conn, invited_email, invited_account=None):
    owner = auth.register(conn, "owner@example.com", PW)
    _create_list(conn, owner)
    if invited_account is not None:
        _backdate_address(conn, invited_account)
    minted = invites.mint(conn, KEY, BASE_URL, "list-1", invited_email, owner)
    conn.commit()
    return minted


def test_an_invite_is_offered_to_and_redeemed_by_the_folded_match(db_conn):
    invitee = auth.register(db_conn, "é@example.com", PW)
    minted = _invite(db_conn, "É@example.com", invitee)

    pending = invites.pending_for(db_conn, KEY, _account(db_conn, invitee))
    assert [p["id"] for p in pending] == [minted["invite_id"]]
    assert invites.redeem(db_conn, KEY, _account(db_conn, invitee), pending[0]["token"]) == "list-1"


def test_redeem_folds_beyond_lower(db_conn):
    invitee = auth.register(db_conn, "straße@example.com", PW)
    minted = _invite(db_conn, "STRASSE@example.com", invitee)
    assert invites.redeem(db_conn, KEY, _account(db_conn, invitee), minted["token"]) == "list-1"


def test_an_invite_is_neither_offered_to_nor_redeemed_by_a_different_address(db_conn):
    stranger = auth.register(db_conn, "e@example.com", PW)  # plain 'e', not 'é'
    minted = _invite(db_conn, "É@example.com", stranger)
    assert invites.pending_for(db_conn, KEY, _account(db_conn, stranger)) == []
    with pytest.raises(ApiError) as exc:
        invites.redeem(db_conn, KEY, _account(db_conn, stranger), minted["token"])
    assert exc.value.code == "invite_email_mismatch"


# ---- admin identity, admin list, roster, CLI --------------------------------------------------


def _app(tmp_path, admin_emails=()):
    database_path = str(tmp_path / "fold.db")
    conn = db_module.connect(database_path)
    db_module.init_db(conn)
    conn.close()
    app = Flask(__name__)
    app.config["TESTING"] = True
    app.register_blueprint(
        create_blueprint(
            database_path=database_path,
            invite_hmac_key=b"test-hmac-key",
            base_url="http://testserver",
            admin_emails=list(admin_emails),
        )
    )
    return app


def test_a_configured_admin_address_matches_by_folding(tmp_path):
    # 'ß' in the configured address, so neither side may fall back to .lower(): that keeps 'ß',
    # while the account's folded address has 'ss'.
    client = _app(tmp_path, admin_emails=["STRAßE@example.com"]).test_client()
    client.post("/api/v1/register", json={"email": "straße@example.com", "password": PW})
    resp = client.post(
        "/api/v1/login",
        json={"email": "straße@example.com", "password": PW, "device_label": "web"},
    )
    assert resp.get_json()["is_admin"] is True


def test_admin_users_are_ordered_by_the_folded_address(db_conn):
    # Folded, 'éa' < 'éb'. By lower() — which leaves 'É' as U+00C9, below 'é' at U+00E9 — the
    # order would be the other way round.
    auth.register(db_conn, "éa@example.com", PW)
    auth.register(db_conn, "Éb@example.com", PW)
    users = accounts.list_all_accounts(db_conn, frozenset())
    assert [u["email"] for u in users] == ["éa@example.com", "Éb@example.com"]


def test_the_roster_breaks_a_joined_at_tie_by_the_folded_address(db_conn):
    first = auth.register(db_conn, "Éb@example.com", PW)
    second = auth.register(db_conn, "éa@example.com", PW)
    _create_list(db_conn, first)
    db_conn.execute(
        "INSERT INTO memberships (account_id, list_id, joined_at) VALUES (?, 'list-1', 0)",
        (second,),
    )
    db_conn.execute("UPDATE memberships SET joined_at = 0 WHERE list_id = 'list-1'")
    roster = sync._rosters(db_conn, ["list-1"])["list-1"]
    assert [m["email"] for m in roster] == ["éa@example.com", "Éb@example.com"]


def test_reset_password_cli_finds_the_account_by_folding(app, cli_runner):
    from shoppinglist_server import get_config_by_name

    conn = db_module.connect(get_config_by_name(app)["database_path"])
    account_id = auth.register(conn, "straße@example.com", PW)
    conn.close()

    result = cli_runner.invoke(args=["shoppinglist", "reset-password", "STRASSE@example.com"])
    assert result.exit_code == 0, result.output
    new_password = result.output.split(": ", 1)[1].splitlines()[0]

    conn = db_module.connect(get_config_by_name(app)["database_path"])
    assert auth.login(conn, "straße@example.com", new_password, "web")[1] == account_id
    conn.close()


# ---- migration 12 -----------------------------------------------------------------------------


def _pre_12_database(path):
    """A database as schema version 11 left it: schema.sql without the folded columns and their
    indexes, with the ASCII lower() index in their place."""
    schema_sql = resources.files("shoppinglist_server").joinpath("schema.sql").read_text()
    pre = schema_sql
    for pattern, replacement in (
        (
            r"(email_set_at INTEGER NOT NULL DEFAULT 0),\s*(?:--[^\n]*\n\s*)*"
            r"email_normalized TEXT NOT NULL DEFAULT ''",
            r"\1",
        ),
        (
            r"CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_email_normalized "
            r"ON accounts \(email_normalized\);",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_email_lower ON accounts (lower(email));",
        ),
        (
            r"(used_at INTEGER),\s*(?:--[^\n]*\n\s*)*"
            r"invited_email_normalized TEXT NOT NULL DEFAULT ''",
            r"\1",
        ),
        (
            r"CREATE INDEX IF NOT EXISTS idx_invites_email_normalized "
            r"ON invites \(invited_email_normalized\);",
            "",
        ),
    ):
        pre, count = re.subn(pattern, replacement, pre)
        assert count == 1, pattern
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    conn.executescript(pre)
    conn.execute("PRAGMA user_version = 11")
    conn.commit()
    return conn


def _insert_account(conn, account_id, email):
    conn.execute(
        "INSERT INTO accounts (id, email, password_hash, created_at, email_set_at) "
        "VALUES (?, ?, 'x', 1, 1)",
        (account_id, email),
    )


def test_migration_12_backfills_both_folded_columns(tmp_path):
    path = tmp_path / "pre12.db"
    conn = _pre_12_database(path)
    _insert_account(conn, "a1", "Émile@Example.com")
    _insert_account(conn, "a2", "STRASSE@example.com")
    conn.execute(
        "INSERT INTO lists (id, created_at, change_seq, name, name_ts, name_by) "
        "VALUES ('list-1', 1, 1, 'L', 1, 'dev')"
    )
    conn.execute(
        "INSERT INTO invites (id, list_id, invited_email, created_by, created_at, expires_at) "
        "VALUES ('inv-1', 'list-1', 'ÉVE@example.com', 'a1', 1, 2)"
    )
    conn.commit()
    conn.close()

    conn = db_module.connect(str(path))  # migrates
    assert conn.execute("PRAGMA user_version").fetchone()[0] == migrations.CURRENT_VERSION
    assert dict(conn.execute("SELECT id, email_normalized FROM accounts").fetchall()) == {
        "a1": "émile@example.com",
        "a2": "strasse@example.com",
    }
    assert conn.execute("SELECT invited_email_normalized FROM invites").fetchone()[0] == (
        "éve@example.com"
    )
    indexes = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='index'")}
    assert "idx_accounts_email_lower" not in indexes
    # The new unique index holds: a second spelling of a migrated address cannot be registered.
    with pytest.raises(ApiError) as exc:
        auth.register(conn, "strasse@EXAMPLE.com", PW)
    assert exc.value.code == "email_taken"
    conn.close()


def test_fresh_schema_and_migration_12_agree(tmp_path):
    fresh = db_module.connect(str(tmp_path / "fresh.db"))
    db_module.init_db(fresh)
    _pre_12_database(tmp_path / "migrated.db").close()
    migrated = db_module.connect(str(tmp_path / "migrated.db"))

    def _shape(conn):
        columns = {
            table: [
                (row["name"], row["type"], row["notnull"], row["dflt_value"])
                for row in conn.execute(f"PRAGMA table_info({table})").fetchall()
            ]
            for table in ("accounts", "invites")
        }
        indexes = sorted(
            (row["name"], row["sql"].replace("IF NOT EXISTS ", ""))
            for row in conn.execute(
                "SELECT name, sql FROM sqlite_master WHERE type = 'index' "
                "AND tbl_name IN ('accounts', 'invites') AND sql IS NOT NULL"
            )
        )
        return columns, indexes

    assert _shape(fresh) == _shape(migrated)
    fresh.close()
    migrated.close()


def test_migration_12_refuses_accounts_that_collide_after_folding(tmp_path):
    path = tmp_path / "collide.db"
    conn = _pre_12_database(path)
    # The ASCII-only lower() index let both in.
    _insert_account(conn, "a1", "É@example.com")
    _insert_account(conn, "a2", "é@example.com")
    _insert_account(conn, "a3", "fine@example.com")
    conn.commit()
    conn.close()

    with pytest.raises(MigrationRefused) as exc:
        db_module.connect(str(path))
    message = str(exc.value)
    assert "'É@example.com'" in message and "'é@example.com'" in message
    assert "fine@example.com" not in message

    # Nothing moved: the version, the columns and the old index are all as they were, so an
    # operator can fix the rows by hand and the next connection migrates cleanly.
    raw = sqlite3.connect(str(path))
    assert raw.execute("PRAGMA user_version").fetchone()[0] == 11
    assert "email_normalized" not in {r[1] for r in raw.execute("PRAGMA table_info(accounts)")}
    raw.execute("UPDATE accounts SET email = 'e2@example.com' WHERE id = 'a2'")
    raw.commit()
    raw.close()
    conn = db_module.connect(str(path))
    assert conn.execute("PRAGMA user_version").fetchone()[0] == migrations.CURRENT_VERSION
    conn.close()


# ---- round trip over arbitrary local parts ----------------------------------------------------

# Anything EMAIL_RE and the colon rule accept in a local part. 'ı' (dotless i) is left out: its
# upper case is 'I', which folds to 'i', and Unicode's default folding keeps 'ı' distinct — the
# one documented pair that does not round-trip (see emails.normalize_email).
_LOCAL_CHARS = st.characters(
    blacklist_categories=("Cs", "Cc", "Zs", "Zl", "Zp"), blacklist_characters="@:ı"
).filter(lambda c: not c.isspace())
_LOCAL_PARTS = st.text(_LOCAL_CHARS, min_size=1, max_size=16)


def _variants(address):
    return {
        address,
        address.upper(),
        address.lower(),
        address.casefold(),
        address.swapcase(),
        unicodedata.normalize("NFD", address),
        unicodedata.normalize("NFD", address).upper(),
    }


@example(local="ΐ")
@example(local="straße")
@example(local="É")
@given(local=_LOCAL_PARTS)
def test_registration_and_login_round_trip_with_any_case_variant(local):
    address = f"{local}@Example.com"
    with tempfile.TemporaryDirectory() as tmp:
        conn = db_module.connect(str(Path(tmp) / "rt.db"))
        db_module.init_db(conn)
        try:
            # A cheap hash: this is about the address lookup, and scrypt per example is slow.
            with mock.patch.object(auth, "generate_password_hash", _cheap_hash):
                account_id = auth.register(conn, address, PW)
                for variant in _variants(address):
                    assert auth.login(conn, variant, PW, "web")[1] == account_id, variant
                    with pytest.raises(ApiError) as exc:
                        auth.register(conn, variant, PW)
                    assert exc.value.code == "email_taken", variant
        finally:
            conn.close()


def _cheap_hash(password):
    from werkzeug.security import generate_password_hash

    return generate_password_hash(password, method="pbkdf2:sha256:1")
