import hashlib
import re
import secrets
import sqlite3
import time
import uuid
from dataclasses import dataclass
from functools import wraps

from flask import g, request
from werkzeug.security import check_password_hash, generate_password_hash

from . import get_db
from .errors import ApiError

DEFAULT_CURRENCY = "EUR"
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
MIN_PASSWORD_LENGTH = 8

# last_seen_at only feeds the sessions list in Settings, where minute-level
# precision is meaningless. Throttle the write (and its commit) to this
# staleness window instead of touching the DB on every authed request.
LAST_SEEN_REFRESH_MS = 15 * 60 * 1000


@dataclass
class Account:
    id: str
    email: str


def now_ms() -> int:
    return time.time_ns() // 1_000_000


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def validate_email(email):
    if not email or not EMAIL_RE.match(email):
        raise ApiError(422, "invalid_email", "Email address is not valid.")


def validate_password(password):
    if not password or len(password) < MIN_PASSWORD_LENGTH:
        raise ApiError(
            422,
            "invalid_password",
            f"Password must be at least {MIN_PASSWORD_LENGTH} characters.",
        )


def register(conn: sqlite3.Connection, email: str, password: str) -> str:
    validate_email(email)
    validate_password(password)

    account_id = str(uuid.uuid4())
    now = now_ms()
    password_hash = generate_password_hash(password)

    try:
        conn.execute(
            "INSERT INTO accounts (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
            (account_id, email, password_hash, now),
        )
    except sqlite3.IntegrityError as exc:
        raise ApiError(
            409, "email_taken", "An account with this email already exists."
        ) from exc

    conn.execute(
        "INSERT INTO account_settings (account_id, default_currency, updated_at) "
        "VALUES (?, ?, ?)",
        (account_id, DEFAULT_CURRENCY, now),
    )
    conn.commit()
    return account_id


def login(conn: sqlite3.Connection, email: str, password: str, device_label: str):
    row = conn.execute(
        "SELECT id, password_hash FROM accounts WHERE lower(email) = lower(?)",
        (email,),
    ).fetchone()
    if row is None or not check_password_hash(row["password_hash"], password):
        raise ApiError(401, "invalid_credentials", "Email or password is incorrect.")

    token = secrets.token_urlsafe(32)  # 256 bits of randomness
    token_hash = hash_token(token)
    now = now_ms()
    conn.execute(
        "INSERT INTO auth_tokens (id, token_hash, account_id, device_label, created_at, last_seen_at) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (str(uuid.uuid4()), token_hash, row["id"], device_label, now, now),
    )
    conn.commit()
    return token, row["id"]


def logout(conn: sqlite3.Connection, token: str) -> None:
    conn.execute("DELETE FROM auth_tokens WHERE token_hash = ?", (hash_token(token),))
    conn.commit()


def _extract_token(req) -> str:
    auth_header = req.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        raise ApiError(
            401, "missing_token", "Authorization header with a bearer token is required."
        )
    token = auth_header[len("Bearer "):].strip()
    if not token:
        raise ApiError(
            401, "missing_token", "Authorization header with a bearer token is required."
        )
    return token


def require_account(conn: sqlite3.Connection, req) -> Account:
    token = _extract_token(req)
    token_hash = hash_token(token)

    row = conn.execute(
        "SELECT auth_tokens.account_id AS account_id, accounts.email AS email, "
        "auth_tokens.last_seen_at AS last_seen_at "
        "FROM auth_tokens JOIN accounts ON accounts.id = auth_tokens.account_id "
        "WHERE auth_tokens.token_hash = ?",
        (token_hash,),
    ).fetchone()
    if row is None:
        raise ApiError(401, "invalid_token", "The bearer token is invalid or has been revoked.")

    now = now_ms()
    if now - row["last_seen_at"] > LAST_SEEN_REFRESH_MS:
        conn.execute(
            "UPDATE auth_tokens SET last_seen_at = ? WHERE token_hash = ?",
            (now, token_hash),
        )
        conn.commit()
    return Account(id=row["account_id"], email=row["email"])


def authed(view_func):
    @wraps(view_func)
    def wrapper(*args, **kwargs):
        conn = get_db()
        g.account = require_account(conn, request)
        g.token = _extract_token(request)
        return view_func(*args, **kwargs)

    return wrapper
