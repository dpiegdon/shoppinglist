"""Sharing: invites (email-bound, fixed 7-day expiry), members, leave, orphans.

Spec §3 (orphaned lists), §5 (invite scheme).
"""

import base64
import hmac as hmac_module
import uuid
from hashlib import sha256

from . import db as db_module
from .auth import now_ms
from .errors import ApiError

INVITE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000  # fixed 7 days; never client-supplied
SERVER_ORPHAN = "server-orphan"


# ---- token encode / decode (Wire Contract format) ---------------------------


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_decode(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _sign(key: bytes, payload_b64: str) -> bytes:
    return hmac_module.new(key, payload_b64.encode("ascii"), sha256).digest()


def _encode_token(key: bytes, invite_id: str, list_id: str, email: str, expires_at: int) -> str:
    plaintext = f"{invite_id}:{list_id}:{email}:{expires_at}"
    payload_b64 = _b64url_encode(plaintext.encode("utf-8"))
    signature_b64 = _b64url_encode(_sign(key, payload_b64))
    return f"{payload_b64}.{signature_b64}"


def _decode_token(key: bytes, token: str):
    try:
        payload_b64, signature_b64 = (token or "").split(".", 1)
        expected = _sign(key, payload_b64)
        given = _b64url_decode(signature_b64)
        if not hmac_module.compare_digest(expected, given):
            raise ValueError("signature mismatch")
        plaintext = _b64url_decode(payload_b64).decode("utf-8")
        invite_id, list_id, email, expires_at_str = plaintext.split(":", 3)
        return invite_id, list_id, email, int(expires_at_str)
    except (ValueError, UnicodeDecodeError) as exc:
        raise ApiError(400, "invalid_token", "Invite token is malformed or invalid.") from exc


# ---- membership helper --------------------------------------------------------


def is_member(conn, account_id, list_id) -> bool:
    return (
        conn.execute(
            "SELECT 1 FROM memberships WHERE account_id = ? AND list_id = ?",
            (account_id, list_id),
        ).fetchone()
        is not None
    )


# ---- mint / revoke / redeem ---------------------------------------------------


def mint(conn, key: bytes, base_url: str, list_id: str, invited_email: str, created_by: str) -> dict:
    if not is_member(conn, created_by, list_id):
        raise ApiError(403, "not_a_member", "You are not a member of this list.")
    if not invited_email or "@" not in invited_email:
        raise ApiError(422, "invalid_email", "invited_email is not valid.")

    invite_id = str(uuid.uuid4())
    now = now_ms()
    expires_at = now + INVITE_EXPIRY_MS
    token = _encode_token(key, invite_id, list_id, invited_email, expires_at)

    conn.execute(
        "INSERT INTO invites (id, list_id, invited_email, created_by, created_at, expires_at, revoked, used_at) "
        "VALUES (?, ?, ?, ?, ?, ?, 0, NULL)",
        (invite_id, list_id, invited_email, created_by, now, expires_at),
    )
    return {
        "invite_id": invite_id,
        "token": token,
        "url": f"{base_url.rstrip('/')}/invite/{token}",
        "expires_at": expires_at,
    }


def revoke(conn, account_id: str, invite_id: str) -> None:
    row = conn.execute("SELECT list_id FROM invites WHERE id = ?", (invite_id,)).fetchone()
    if row is None:
        raise ApiError(404, "invite_not_found", "Invite not found.")
    if not is_member(conn, account_id, row["list_id"]):
        raise ApiError(403, "not_a_member", "You are not a member of this list.")
    conn.execute("UPDATE invites SET revoked = 1 WHERE id = ?", (invite_id,))


def redeem(conn, key: bytes, account, token: str) -> str:
    invite_id, list_id, invited_email, expires_at = _decode_token(key, token)

    row = conn.execute(
        "SELECT revoked, used_at FROM invites WHERE id = ?", (invite_id,)
    ).fetchone()
    if row is None:
        raise ApiError(400, "invalid_token", "Invite not found.")

    if account.email.lower() != invited_email.lower():
        raise ApiError(
            409, "invite_email_mismatch", "This invite is bound to a different email address."
        )
    if now_ms() >= expires_at:
        raise ApiError(409, "invite_expired", "This invite has expired.")
    if row["revoked"]:
        raise ApiError(409, "invite_revoked", "This invite has been revoked.")
    if row["used_at"] is not None:
        raise ApiError(409, "invite_used", "This invite has already been used.")

    conn.execute(
        "INSERT OR IGNORE INTO memberships (account_id, list_id, joined_at) VALUES (?, ?, ?)",
        (account.id, list_id, now_ms()),
    )
    conn.execute("UPDATE invites SET used_at = ? WHERE id = ?", (now_ms(), invite_id))
    return list_id


# ---- leave / orphans -----------------------------------------------------------


def _clear_and_tombstone(conn, list_id: str) -> None:
    """Tombstone every live item plus the list itself (Spec §3)."""
    now = now_ms()
    for row in conn.execute(
        "SELECT id FROM items WHERE list_id = ? AND deleted = 0", (list_id,)
    ).fetchall():
        conn.execute(
            "UPDATE items SET deleted = 1, deleted_ts = ?, deleted_by = ?, change_seq = ? WHERE id = ?",
            (now, SERVER_ORPHAN, db_module.next_change_seq(conn), row["id"]),
        )
    conn.execute(
        "UPDATE lists SET deleted = 1, deleted_ts = ?, deleted_by = ?, change_seq = ? WHERE id = ?",
        (now, SERVER_ORPHAN, db_module.next_change_seq(conn), list_id),
    )


def orphan_check(conn, list_id: str) -> None:
    """Tombstone `list_id`'s content iff it currently has zero memberships.

    Call this *after* removing a membership row. Intended for paths with no
    surviving session to preserve tombstone-propagation for (e.g. account
    deletion, S3) — contrast with `leave`, which keeps the departing member's
    row around in the orphaning case specifically so it still propagates.
    """
    remaining = conn.execute(
        "SELECT COUNT(*) AS n FROM memberships WHERE list_id = ?", (list_id,)
    ).fetchone()["n"]
    if remaining == 0:
        _clear_and_tombstone(conn, list_id)


def leave(conn, account_id: str, list_id: str) -> None:
    """Remove `account_id`'s membership in `list_id` (Spec §7 POST .../leave).

    If this is the list's last membership, the list is orphaned: content is
    tombstoned immediately, but — unlike orphan_check — the departing
    account's membership row is deliberately *kept*, so that account's other
    devices still see the tombstone via a normal incremental /sync (Spec §3:
    "so all of the departing user's other devices converge on the deletion via
    sync"). The lingering row is cleaned up by GC (S8) alongside the
    tombstoned list once the tombstone retention window elapses — GC must
    delete it together with (not after) the list row, since the `memberships`
    FK would otherwise block hard-deleting a still-referenced list.

    If other members remain, the row is removed immediately as usual.
    """
    if not is_member(conn, account_id, list_id):
        raise ApiError(403, "not_a_member", "You are not a member of this list.")

    remaining = conn.execute(
        "SELECT COUNT(*) AS n FROM memberships WHERE list_id = ?", (list_id,)
    ).fetchone()["n"]

    if remaining <= 1:
        _clear_and_tombstone(conn, list_id)
    else:
        conn.execute(
            "DELETE FROM memberships WHERE account_id = ? AND list_id = ?",
            (account_id, list_id),
        )
