import re
import secrets
import sqlite3
import string

from werkzeug.security import check_password_hash, generate_password_hash

from . import auth, invites
from .errors import ApiError

CURRENCY_RE = re.compile(r"^[A-Z]{3}$")
RESET_PASSWORD_LENGTH = 16
INITIALS_MAX_LENGTH = 3


def _default_initials(email: str) -> str:
    """Leading 1-2 characters of the email's local-part, uppercased (T-64)."""
    local_part = email.split("@", 1)[0]
    return local_part[:2].upper()


def resolve_initials(email: str, initials: str | None) -> str:
    """The account's chosen initials, or a derived default when unset (T-64)."""
    return initials or _default_initials(email)


def _require_password(conn: sqlite3.Connection, account_id: str, password: str) -> None:
    row = conn.execute(
        "SELECT password_hash FROM accounts WHERE id = ?", (account_id,)
    ).fetchone()
    if row is None or not check_password_hash(row["password_hash"], password or ""):
        raise ApiError(401, "invalid_credentials", "Password is incorrect.")


def change_password(
    conn: sqlite3.Connection,
    account_id: str,
    current_password: str,
    new_password: str,
    current_token: str,
) -> None:
    _require_password(conn, account_id, current_password)
    auth.validate_password(new_password)
    conn.execute(
        "UPDATE accounts SET password_hash = ? WHERE id = ?",
        (generate_password_hash(new_password), account_id),
    )
    # Revoke every other session (T-45): a password change is the standard response to a
    # possibly-compromised device or token, so leaving other tokens valid would defeat it. The
    # session performing the change keeps its own token, so the user isn't logged out here.
    conn.execute(
        "DELETE FROM auth_tokens WHERE account_id = ? AND token_hash != ?",
        (account_id, auth.hash_token(current_token)),
    )
    conn.commit()


def change_email(
    conn: sqlite3.Connection, account_id: str, password: str, new_email: str
) -> None:
    _require_password(conn, account_id, password)
    auth.validate_email(new_email)
    # Pending invites bound to the old address stop matching automatically:
    # redemption always compares against the account's *current* email (S6).
    try:
        conn.execute("UPDATE accounts SET email = ? WHERE id = ?", (new_email, account_id))
    except sqlite3.IntegrityError as exc:
        raise ApiError(
            409, "email_taken", "An account with this email already exists."
        ) from exc
    conn.commit()


def list_sessions(conn: sqlite3.Connection, account_id: str, current_token: str) -> list:
    current_hash = auth.hash_token(current_token)
    rows = conn.execute(
        "SELECT id, device_label, created_at, last_seen_at, token_hash "
        "FROM auth_tokens WHERE account_id = ? ORDER BY created_at",
        (account_id,),
    ).fetchall()
    return [
        {
            "id": row["id"],
            "device_label": row["device_label"],
            "created_at": row["created_at"],
            "last_seen_at": row["last_seen_at"],
            "current": row["token_hash"] == current_hash,
        }
        for row in rows
    ]


def revoke_session(conn: sqlite3.Connection, account_id: str, session_id: str) -> None:
    cur = conn.execute(
        "DELETE FROM auth_tokens WHERE id = ? AND account_id = ?", (session_id, account_id)
    )
    conn.commit()
    if cur.rowcount == 0:
        raise ApiError(404, "session_not_found", "Session not found.")


def get_settings(conn: sqlite3.Connection, account_id: str) -> dict:
    row = conn.execute(
        "SELECT account_settings.default_currency AS default_currency, "
        "account_settings.initials AS initials, accounts.email AS email "
        "FROM account_settings JOIN accounts ON accounts.id = account_settings.account_id "
        "WHERE account_settings.account_id = ?",
        (account_id,),
    ).fetchone()
    return {
        "default_currency": row["default_currency"],
        "initials": resolve_initials(row["email"], row["initials"]),
    }


# Sentinel distinguishing "caller didn't pass this field at all" from any real value,
# including None. update_settings is a PATCH, not a PUT (T-87): a field left at this
# default is left untouched in the database, whereas initials=None is a meaningful
# value ("clear the custom override back to the derived default").
_UNSET = object()


def update_settings(
    conn: sqlite3.Connection,
    account_id: str,
    default_currency=_UNSET,
    initials=_UNSET,
) -> dict:
    set_clauses = []
    params: list = []

    if default_currency is not _UNSET:
        if not isinstance(default_currency, str) or not CURRENCY_RE.match(default_currency):
            raise ApiError(
                422,
                "invalid_currency",
                "default_currency must be a 3-letter uppercase ISO-4217 code.",
            )
        set_clauses.append("default_currency = ?")
        params.append(default_currency)

    if initials is not _UNSET:
        if initials is not None:
            if not isinstance(initials, str):
                raise ApiError(422, "invalid_initials", "initials must be a string.")
            if len(initials) > INITIALS_MAX_LENGTH:
                raise ApiError(
                    422,
                    "invalid_initials",
                    f"initials must be {INITIALS_MAX_LENGTH} characters or fewer.",
                )
        set_clauses.append("initials = ?")
        params.append(initials)

    if set_clauses:
        set_clauses.append("updated_at = ?")
        params.append(auth.now_ms())
        params.append(account_id)
        conn.execute(
            f"UPDATE account_settings SET {', '.join(set_clauses)} WHERE account_id = ?",
            params,
        )
        conn.commit()

    return get_settings(conn, account_id)


def delete_account(conn: sqlite3.Connection, account_id: str, password: str) -> None:
    _require_password(conn, account_id, password)

    # No future session of this account can exist to observe a tombstone, so
    # (unlike `invites.leave`) there's nothing to preserve propagation for:
    # remove the membership immediately, then let orphan_check clear+tombstone
    # the list if that was its last member.
    list_ids = [
        row["list_id"]
        for row in conn.execute(
            "SELECT list_id FROM memberships WHERE account_id = ?", (account_id,)
        ).fetchall()
    ]
    for list_id in list_ids:
        conn.execute(
            "DELETE FROM memberships WHERE account_id = ? AND list_id = ?",
            (account_id, list_id),
        )
        invites.orphan_check(conn, list_id)

    conn.execute("DELETE FROM auth_tokens WHERE account_id = ?", (account_id,))
    conn.execute("DELETE FROM account_settings WHERE account_id = ?", (account_id,))
    # invites.created_by REFERENCES accounts(id), so a departed account's own
    # invites must go too — that also revokes them, which is the right call:
    # otherwise an invite whose creator no longer exists would still admit
    # the invitee (T-84).
    conn.execute("DELETE FROM invites WHERE created_by = ?", (account_id,))
    conn.execute("DELETE FROM accounts WHERE id = ?", (account_id,))
    conn.commit()


def reset_password(conn: sqlite3.Connection, email: str) -> str:
    row = conn.execute(
        "SELECT id FROM accounts WHERE lower(email) = lower(?)", (email,)
    ).fetchone()
    if row is None:
        raise ApiError(404, "account_not_found", "No account with this email exists.")

    alphabet = string.ascii_letters + string.digits
    new_password = "".join(secrets.choice(alphabet) for _ in range(RESET_PASSWORD_LENGTH))
    conn.execute(
        "UPDATE accounts SET password_hash = ? WHERE id = ?",
        (generate_password_hash(new_password), row["id"]),
    )
    # Revoke every session (unlike change_password's T-45 carve-out, there is no
    # "current session" to spare here — this is an operator resetting a possibly
    # compromised account from the CLI, not the user acting from a trusted device):
    # leaving old tokens valid would let a stolen token survive the reset.
    conn.execute("DELETE FROM auth_tokens WHERE account_id = ?", (row["id"],))
    conn.commit()
    return new_password
