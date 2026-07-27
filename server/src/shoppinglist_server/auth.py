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

from . import get_config, get_db
from .errors import ApiError

DEFAULT_CURRENCY = "EUR"
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
MIN_PASSWORD_LENGTH = 8

# Upper bounds on credential inputs (T-118). Without these MAX_CONTENT_LENGTH (4 MB) was the only
# bound, so a 100k-character email registered happily and was then echoed to every co-member via
# GET /lists/<id>/members and to every admin via GET /admin/users.
#
# 254, not 320: RFC 5321 §4.5.3.1.1/§4.5.3.1.2 cap the local-part at 64 octets and the domain at
# 255, which sums to the widely-quoted 320 — but §4.5.3.1.3 caps a whole reverse-path/forward-path
# at 256 octets *including* the angle brackets, i.e. 254 for the address itself. The two limits
# can't both be saturated, so 320 describes an address that no SMTP path can carry. 254 must also
# stay <= invites.MAX_INVITED_EMAIL_LENGTH, or an account could exist that mint() can never invite.
MAX_EMAIL_LENGTH = 254
# No RFC governs this one; scrypt has no short-input limit (unlike bcrypt's 72 bytes), so this is
# purely an anti-bloat bound and can be generous.
MAX_PASSWORD_LENGTH = 320
# A client-supplied device name for the sessions list. Same scale as the sync engine's
# UPDATED_BY_MAX_LENGTH, for the same reason: it is an identifier-ish label, not free text.
MAX_DEVICE_LABEL_LENGTH = 128

# last_seen_at feeds the sessions list in Settings and the idle expiry below,
# neither of which cares about minute-level precision (the shortest window is 7
# days). Throttle the write (and its commit) to this staleness window instead of
# touching the DB on every authed request.
LAST_SEEN_REFRESH_MS = 15 * 60 * 1000

# Sliding inactivity windows (T-104). A session dies when it goes untouched for
# longer than its window; any authed request slides it forward (subject to the
# LAST_SEEN_REFRESH_MS throttle above, which is ~4 orders of magnitude smaller
# than the shortest window and so can't cause a premature expiry).
#
# Web is short because a browser is far more likely to be a shared or public
# machine; the app is also same-origin with this server, so a web client is
# never a stale version and always declares its platform. Android is long
# because it's a personal device holding the token in EncryptedSharedPreferences.
WEB_IDLE_TTL_MS = 7 * 24 * 60 * 60 * 1000  # 7 days
ANDROID_IDLE_TTL_MS = 62 * 24 * 60 * 60 * 1000  # 62 days (~2 months)
PLATFORM_IDLE_TTL_MS = {"web": WEB_IDLE_TTL_MS, "android": ANDROID_IDLE_TTL_MS}

# Used when a client declares no platform (or an unrecognized one): pre-T-104
# Android builds, curl, future clients. Deliberately the LONGER window — an
# unexpected early logout is worse than a late one, and the only client that
# needs the short window (web) is served by this same process and therefore
# always new enough to declare itself. Must match schema.sql's column DEFAULT
# and migration 3's backfill.
DEFAULT_IDLE_TTL_MS = ANDROID_IDLE_TTL_MS


def resolve_idle_ttl_ms(platform) -> int:
    """Map a client-declared platform to its inactivity window.

    Unknown/absent/non-string platforms fall back to DEFAULT_IDLE_TTL_MS rather
    than raising: login must not hard-fail because a client is newer or older
    than this server. The value is always resolved HERE, server-side — a client
    can pick which bucket it lands in but never the duration itself.
    """
    if not isinstance(platform, str):
        return DEFAULT_IDLE_TTL_MS
    return PLATFORM_IDLE_TTL_MS.get(platform.strip().lower(), DEFAULT_IDLE_TTL_MS)


# Verified against when no account matches, so a failed login costs the same scrypt work whether
# or not the address exists (T-115 — see login()). Generated once at import rather than per miss:
# generate_password_hash is ~200 ms, far too slow to run on every failed login, and a lazily-built
# one would make the process's first unknown-address login measurably different from the rest.
# The password below is not a secret and guards nothing — the hash exists only to be burned.
_TIMING_EQUALIZER_HASH = generate_password_hash("not-a-secret-this-hash-exists-only-to-burn-time")


@dataclass
class Account:
    id: str
    email: str


def now_ms() -> int:
    return time.time_ns() // 1_000_000


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def validate_email(email):
    # isinstance first (T-116): EMAIL_RE.match() raises TypeError on a non-string, which used to
    # surface as an unauthenticated 500 on POST /register with e.g. {"email": 5}. A falsy non-string
    # ({}, []) happened to be caught by the old `not email` guard; a truthy one was not.
    if not isinstance(email, str) or not EMAIL_RE.match(email):
        raise ApiError(422, "invalid_email", "Email address is not valid.")
    if len(email) > MAX_EMAIL_LENGTH:
        raise ApiError(
            422, "invalid_email", f"Email address must be {MAX_EMAIL_LENGTH} characters or fewer."
        )
    # EMAIL_RE permits ':' (it only excludes '@' and whitespace), but invites.mint() rejects a
    # colon in invited_email because the token payload is colon-delimited. An account whose address
    # contained one could therefore never be invited to any list — a silent, permanent dead end.
    # Reject it at the source instead (T-118).
    if ":" in email:
        raise ApiError(422, "invalid_email", "Email address must not contain a colon.")


def validate_password(password):
    # isinstance first (T-116): len() on a non-string raises TypeError.
    if not isinstance(password, str) or len(password) < MIN_PASSWORD_LENGTH:
        raise ApiError(
            422,
            "invalid_password",
            f"Password must be at least {MIN_PASSWORD_LENGTH} characters.",
        )
    if len(password) > MAX_PASSWORD_LENGTH:
        raise ApiError(
            422, "invalid_password", f"Password must be {MAX_PASSWORD_LENGTH} characters or fewer."
        )


def validate_device_label(device_label):
    """A device label is optional (NULL is a legitimate stored value), but when present it must be
    a string within cap (T-116/T-118) — a dict used to reach the INSERT and 500 at bind time."""
    if device_label is None:
        return
    if not isinstance(device_label, str):
        raise ApiError(422, "invalid_device_label", "device_label must be a string.")
    if len(device_label) > MAX_DEVICE_LABEL_LENGTH:
        raise ApiError(
            422,
            "invalid_device_label",
            f"device_label must be {MAX_DEVICE_LABEL_LENGTH} characters or fewer.",
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


def login(
    conn: sqlite3.Connection,
    email: str,
    password: str,
    device_label: str,
    platform=None,
):
    # A malformed credential is simply an incorrect one: answering 422 here would hand back a
    # response that a correct-shaped guess never gets, so keep the uniform 401 (T-116). Without the
    # type check a dict/list email reached the SQL bind below and 500'd, unauthenticated.
    if not isinstance(email, str) or not isinstance(password, str):
        raise ApiError(401, "invalid_credentials", "Email or password is incorrect.")
    # Checked before the lookup: the answer doesn't depend on the account, so it leaks nothing.
    validate_device_label(device_label)

    row = conn.execute(
        "SELECT id, password_hash FROM accounts WHERE lower(email) = lower(?)",
        (email,),
    ).fetchone()
    if row is None:
        # Hash against a throwaway digest so an unknown address costs the same scrypt work as a
        # real one (T-115). Previously check_password_hash ran only when a row was found, and the
        # resulting 52x timing gap (196 ms vs 3.7 ms, measured) enumerated accounts — while
        # server/README.md claimed /login does not enumerate. The status and body were already
        # identical; only the clock gave it away.
        check_password_hash(_TIMING_EQUALIZER_HASH, password)
        raise ApiError(401, "invalid_credentials", "Email or password is incorrect.")
    if not check_password_hash(row["password_hash"], password):
        raise ApiError(401, "invalid_credentials", "Email or password is incorrect.")

    token = secrets.token_urlsafe(32)  # 256 bits of randomness
    token_hash = hash_token(token)
    now = now_ms()
    conn.execute(
        "INSERT INTO auth_tokens "
        "(id, token_hash, account_id, device_label, created_at, last_seen_at, idle_ttl_ms) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        (
            str(uuid.uuid4()),
            token_hash,
            row["id"],
            device_label,
            now,
            now,
            resolve_idle_ttl_ms(platform),
        ),
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
        "auth_tokens.last_seen_at AS last_seen_at, auth_tokens.idle_ttl_ms AS idle_ttl_ms "
        "FROM auth_tokens JOIN accounts ON accounts.id = auth_tokens.account_id "
        "WHERE auth_tokens.token_hash = ?",
        (token_hash,),
    ).fetchone()
    if row is None:
        raise ApiError(401, "invalid_token", "The bearer token is invalid or has been revoked.")

    now = now_ms()
    # Idle expiry (T-104) is enforced HERE, not only in the GC sweep: gc.maybe_run
    # fires at most ~once a day, so an expired token would otherwise keep working
    # until the next sweep happened to run. Delete the row on the way out so the
    # session also disappears from the Settings list immediately, without waiting
    # for GC. Distinct code from invalid_token so clients can tell "you were idle
    # too long" from "revoked", but still a 401 — both clients force a logout on
    # 401 status regardless of code (T-89/T-100), which is exactly what's wanted.
    if now - row["last_seen_at"] > row["idle_ttl_ms"]:
        conn.execute("DELETE FROM auth_tokens WHERE token_hash = ?", (token_hash,))
        conn.commit()
        raise ApiError(
            401, "session_expired", "This session expired after a period of inactivity."
        )

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


def is_admin_email(email, admin_emails) -> bool:
    """Admin identity (T-107): membership in the instance's static admin_emails set, matched
    case-insensitively (login/uniqueness are already case-insensitive). Config is the ONLY source;
    no API path can grant it."""
    return isinstance(email, str) and email.strip().lower() in admin_emails


def admin_required(view_func):
    """Like `authed`, plus a 403 unless the account's email is a configured admin (T-107)."""

    @wraps(view_func)
    def wrapper(*args, **kwargs):
        conn = get_db()
        g.account = require_account(conn, request)
        g.token = _extract_token(request)
        if not is_admin_email(g.account.email, get_config().get("admin_emails", frozenset())):
            raise ApiError(403, "not_admin", "Admin access is required.")
        return view_func(*args, **kwargs)

    return wrapper
