"""Sharing: invites (email-bound, fixed 7-day expiry), members, leave, orphans.

Spec §3 (orphaned lists), §5 (invite scheme).
"""

import base64
import hmac as hmac_module
import uuid
from hashlib import sha256

from . import db as db_module
from .auth import EMAIL_RE, now_ms, resolve_initials
from .errors import ApiError

INVITE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000  # fixed 7 days; never client-supplied
SERVER_ORPHAN = "server-orphan"
MAX_INVITED_EMAIL_LENGTH = 254  # RFC 5321 practical email length cap


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


def decode_token(key: bytes, token: str | None):
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


def mint(
    conn, key: bytes, base_url: str, list_id: str, invited_email: str | None, created_by: str
) -> dict:
    if not is_member(conn, created_by, list_id):
        raise ApiError(403, "not_a_member", "You are not a member of this list.")
    _refuse_if_closed(conn, list_id)
    # The token payload is colon-delimited (see _encode_token/decode_token), so NO component may
    # contain a colon. invited_email was always checked; list_id was not — and list ids are
    # client-minted arbitrary strings (sync._parse_row accepts any non-empty string up to 128
    # chars), so a caller could create a list whose id embedded extra colons and have the server
    # sign a payload that re-splits into different fields. That was never redeemable, because the
    # trailing int(expires_at) cast always failed on the re-split — but the whole defence rested on
    # that cast, and a refactor of decode_token would have turned it into arbitrary list-membership
    # theft. Reject both components explicitly instead (T-117).
    if ":" in list_id:
        raise ApiError(
            422, "invalid_list_id", "List id must not contain a colon and cannot be invited to."
        )
    if (
        not invited_email
        or len(invited_email) > MAX_INVITED_EMAIL_LENGTH
        or ":" in invited_email
        or not EMAIL_RE.match(invited_email)
    ):
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


def redeem(conn, key: bytes, account, token: str | None) -> str:
    invite_id, _token_list_id, invited_email, expires_at = decode_token(key, token)

    row = conn.execute(
        "SELECT list_id, revoked, used_at FROM invites WHERE id = ?", (invite_id,)
    ).fetchone()
    if row is None:
        raise ApiError(400, "invalid_token", "Invite not found.")

    # Defence in depth (T-117): join the list recorded in the DB, not the one carried by the token.
    # The token is HMAC-signed so the two agree, but "the token's list_id is trustworthy" depends
    # on the colon-delimited payload never re-splitting ambiguously. The invite row is
    # authoritative by construction, so reading it here makes that question moot.
    list_id = row["list_id"]

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
    # A link minted before the list closed must stop working, or closing would not be final.
    _refuse_if_closed(conn, list_id)

    conn.execute(
        "INSERT OR IGNORE INTO memberships (account_id, list_id, joined_at) VALUES (?, ?, ?)",
        (account.id, list_id, now_ms()),
    )
    conn.execute("UPDATE invites SET used_at = ? WHERE id = ?", (now_ms(), invite_id))
    touch_list(conn, list_id)  # the joiner is now in every member's roster (T-152)
    return list_id


def pending_for(conn, key: bytes, account) -> list[dict]:
    """The live invites addressed to `account`'s email, for the overview (T-233).

    Live means what `redeem` would accept: not used, not revoked, not expired, on a list that
    still exists and is not closed. A list the account is already on is left out too — that
    invite is redeemable (the membership insert is a no-op) but pointless to offer.

    Each row carries its token, minted again from the row (the encoding is deterministic). That
    is the credential the share URL hands the same person, and it admits only this address, so
    the addressee learns nothing they were not meant to hold — and joining from the overview is
    `redeem`, with every check that path has, rather than a second grant path.
    """
    rows = conn.execute(
        "SELECT invites.id AS id, invites.list_id AS list_id, "
        "invites.invited_email AS invited_email, invites.expires_at AS expires_at, "
        "lists.name AS list_name, lists.kind AS list_kind, "
        "inviter.email AS inviter_email, inviter_settings.initials AS inviter_initials "
        "FROM invites JOIN lists ON lists.id = invites.list_id "
        "JOIN accounts AS inviter ON inviter.id = invites.created_by "
        "JOIN account_settings AS inviter_settings ON inviter_settings.account_id = inviter.id "
        "WHERE lower(invites.invited_email) = lower(?) "
        "AND invites.revoked = 0 AND invites.used_at IS NULL AND invites.expires_at > ? "
        "AND lists.deleted = 0 AND lists.closed_at IS NULL "
        "AND NOT EXISTS (SELECT 1 FROM memberships "
        "WHERE memberships.list_id = lists.id AND memberships.account_id = ?) "
        "ORDER BY invites.created_at",
        (account.email, now_ms(), account.id),
    ).fetchall()
    return [
        {
            "id": row["id"],
            "list_id": row["list_id"],
            "list_name": row["list_name"],
            "list_kind": row["list_kind"] or "shopping",
            # Always someone: deleting an account deletes the invites it minted (T-84).
            "invited_by_initials": resolve_initials(row["inviter_email"], row["inviter_initials"]),
            "expires_at": row["expires_at"],
            "token": _encode_token(
                key, row["id"], row["list_id"], row["invited_email"], row["expires_at"]
            ),
        }
        for row in rows
    ]


# ---- leave / orphans -----------------------------------------------------------


def clear_and_tombstone(conn, list_id: str) -> None:
    """Tombstone every live item plus the list itself (Spec §3).

    Public rather than module-private because housekeeping.py reuses it for the one repair it
    performs — a live list that somehow has no memberships left is put into exactly the state
    `orphan_check` would have left it in, rather than hard-deleted (T-218).
    """
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


def _refuse_if_closed(conn, list_id: str) -> None:
    """A closed list takes no new members (T-157) — it is an archive, not a place to be added to."""
    from . import closing  # deferred: see the note in leave()

    if closing.is_closed(conn, list_id):
        raise ApiError(409, "list_closed", "This list is closed.")


def touch_list(conn, list_id: str) -> None:
    """Bump a list's change_seq without changing any of its fields (T-152).

    The roster rides on the synced list object, so a membership change has to move the row even
    though no field value did — otherwise a device would keep showing someone who has left, or
    miss someone who joined, until the next unrelated edit. Same mechanism for an email or
    initials change, which are equally visible in the roster.
    """
    conn.execute(
        "UPDATE lists SET change_seq = ? WHERE id = ?",
        (db_module.next_change_seq(conn), list_id),
    )


def touch_lists_of_account(conn, account_id: str) -> None:
    """Bump every list this account is a member of — for a change to the account itself."""
    for row in conn.execute(
        "SELECT list_id FROM memberships WHERE account_id = ?", (account_id,)
    ).fetchall():
        touch_list(conn, row["list_id"])


def orphan_check(conn, list_id: str) -> bool:
    """Tombstone `list_id`'s content iff it currently has zero memberships.

    Call this *after* removing a membership row. Intended for paths with no
    surviving session to preserve tombstone-propagation for (e.g. account
    deletion, S3) — contrast with `leave`, which keeps the departing member's
    row around in the orphaning case specifically so it still propagates.

    Returns whether the list was orphaned, so a caller can skip the roster bump that the
    tombstone write has already made redundant.
    """
    remaining = conn.execute(
        "SELECT COUNT(*) AS n FROM memberships WHERE list_id = ?", (list_id,)
    ).fetchone()["n"]
    if remaining == 0:
        clear_and_tombstone(conn, list_id)
        return True
    return False


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

    # Deferred import: closing reads the sync engine, which reaches this module through accounts.
    from . import closing

    row = conn.execute("SELECT kind, closed_at FROM lists WHERE id = ?", (list_id,)).fetchone()
    if row is not None and row["kind"] == closing.EXPENSES_KIND and row["closed_at"] is None:
        # Walking away from an unsettled shared ledger is exactly what closing exists to prevent
        # (T-157). Once the list is closed, leaving is how it is finally let go.
        raise ApiError(
            409,
            "list_open",
            "This expenses list is still open. It has to be closed before you can leave it.",
        )

    remaining = conn.execute(
        "SELECT COUNT(*) AS n FROM memberships WHERE list_id = ?", (list_id,)
    ).fetchone()["n"]

    if remaining <= 1:
        clear_and_tombstone(conn, list_id)
    else:
        conn.execute(
            "DELETE FROM memberships WHERE account_id = ? AND list_id = ?",
            (account_id, list_id),
        )
        # A close vote belongs to a member, so it leaves with them — on a closed list it is spent
        # history, and on an open one it must not stand in for someone who is no longer here.
        conn.execute(
            "DELETE FROM close_votes WHERE list_id = ? AND account_id = ?", (list_id, account_id)
        )
        touch_list(conn, list_id)  # the leaver drops out of every remaining roster (T-152)
        # Their departure can be what completes a vote: everyone still here had already agreed.
        closing.close_if_unanimous_after_membership_change(conn, list_id)
