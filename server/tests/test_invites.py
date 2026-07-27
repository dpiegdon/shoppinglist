"""Tests for sharing: invites, members, leave, orphans (S6)."""

import pytest

from shoppinglist_server import accounts, auth, invites, sync
from shoppinglist_server.errors import ApiError

PW = "password123"
KEY = b"test-invite-hmac-key"
BASE_URL = "http://testserver"


def _register(conn, email):
    return auth.register(conn, email, PW)


def _create_list(conn, account_id, device, list_id="list-1", name="Groceries"):
    sync.apply_changes(
        conn, account_id, device,
        {"lists": [{"id": list_id, "created_at": 1000,
                   "fields": {"name": {"value": name, "updated_at": 100, "updated_by": device}}}]},
    )


def _live_items(conn, list_id):
    return conn.execute(
        "SELECT * FROM items WHERE list_id = ? AND deleted = 0", (list_id,)
    ).fetchall()


def _membership_exists(conn, account_id, list_id):
    return conn.execute(
        "SELECT 1 FROM memberships WHERE account_id = ? AND list_id = ?", (account_id, list_id)
    ).fetchone() is not None


def _list_row(conn, list_id):
    return conn.execute("SELECT * FROM lists WHERE id = ?", (list_id,)).fetchone()


# ---- full lifecycle ---------------------------------------------------------


def test_full_invite_lifecycle_mint_redeem_membership_sync(db_conn):
    owner = _register(db_conn, "owner@example.com")
    invitee = _register(db_conn, "invitee@example.com")
    _create_list(db_conn, owner, "devOwner")
    sync.apply_changes(
        db_conn, owner, "devOwner",
        {"items": [{"id": "item-1", "list_id": "list-1", "created_at": 1000,
                   "fields": {"name": {"value": "Milk", "updated_at": 100, "updated_by": "devOwner"}}}]},
    )

    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)
    assert result["url"] == f"{BASE_URL}/invite/{result['token']}"
    assert result["expires_at"] > auth.now_ms()

    invitee_account = auth.Account(id=invitee, email="invitee@example.com")
    list_id = invites.redeem(db_conn, KEY, invitee_account, result["token"])
    assert list_id == "list-1"
    assert _membership_exists(db_conn, invitee, "list-1")

    # sync delivers the list to the invitee via full_lists, regardless of cursor.
    delivered = sync.delta(db_conn, invitee, cursor=9999, full_lists=["list-1"])
    assert {l["id"] for l in delivered["changes"]["lists"]} == {"list-1"}
    assert {i["id"] for i in delivered["changes"]["items"]} == {"item-1"}


# ---- the five redemption conditions -----------------------------------------


def _mint_and_break(db_conn, owner, mutate):
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)
    mutate(result["token"])
    return result


def test_redeem_forged_hmac_raises_400(db_conn):
    owner = _register(db_conn, "owner@example.com")
    invitee = _register(db_conn, "invitee@example.com")
    _create_list(db_conn, owner, "devOwner")
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)
    payload, _sig = result["token"].split(".", 1)
    forged = payload + ".not-a-real-signature"

    invitee_account = auth.Account(id=invitee, email="invitee@example.com")
    with pytest.raises(ApiError) as excinfo:
        invites.redeem(db_conn, KEY, invitee_account, forged)
    assert excinfo.value.status == 400


def test_redeem_garbage_token_raises_400(db_conn):
    owner = _register(db_conn, "owner@example.com")
    invitee = _register(db_conn, "invitee@example.com")
    invitee_account = auth.Account(id=invitee, email="invitee@example.com")
    with pytest.raises(ApiError) as excinfo:
        invites.redeem(db_conn, KEY, invitee_account, "not-a-token-at-all")
    assert excinfo.value.status == 400


def test_redeem_email_mismatch_raises_409(db_conn):
    owner = _register(db_conn, "owner@example.com")
    stranger = _register(db_conn, "stranger@example.com")
    _create_list(db_conn, owner, "devOwner")
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)

    stranger_account = auth.Account(id=stranger, email="stranger@example.com")
    with pytest.raises(ApiError) as excinfo:
        invites.redeem(db_conn, KEY, stranger_account, result["token"])
    assert excinfo.value.status == 409
    assert excinfo.value.code == "invite_email_mismatch"


def test_redeem_expired_raises_409(db_conn, monkeypatch):
    owner = _register(db_conn, "owner@example.com")
    invitee = _register(db_conn, "invitee@example.com")
    _create_list(db_conn, owner, "devOwner")
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)

    far_future = result["expires_at"] + 1
    monkeypatch.setattr(invites, "now_ms", lambda: far_future)

    invitee_account = auth.Account(id=invitee, email="invitee@example.com")
    with pytest.raises(ApiError) as excinfo:
        invites.redeem(db_conn, KEY, invitee_account, result["token"])
    assert excinfo.value.status == 409
    assert excinfo.value.code == "invite_expired"


def test_redeem_revoked_raises_409(db_conn):
    owner = _register(db_conn, "owner@example.com")
    invitee = _register(db_conn, "invitee@example.com")
    _create_list(db_conn, owner, "devOwner")
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)
    invites.revoke(db_conn, owner, result["invite_id"])

    invitee_account = auth.Account(id=invitee, email="invitee@example.com")
    with pytest.raises(ApiError) as excinfo:
        invites.redeem(db_conn, KEY, invitee_account, result["token"])
    assert excinfo.value.status == 409
    assert excinfo.value.code == "invite_revoked"


def test_redeem_already_used_raises_409(db_conn):
    owner = _register(db_conn, "owner@example.com")
    invitee = _register(db_conn, "invitee@example.com")
    _create_list(db_conn, owner, "devOwner")
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)

    invitee_account = auth.Account(id=invitee, email="invitee@example.com")
    invites.redeem(db_conn, KEY, invitee_account, result["token"])

    with pytest.raises(ApiError) as excinfo:
        invites.redeem(db_conn, KEY, invitee_account, result["token"])
    assert excinfo.value.status == 409
    assert excinfo.value.code == "invite_used"


def test_mint_expiry_is_fixed_seven_days_and_ignores_nothing_client_supplied(db_conn):
    owner = _register(db_conn, "owner@example.com")
    _create_list(db_conn, owner, "devOwner")
    before = auth.now_ms()
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)
    after = auth.now_ms()
    seven_days_ms = 7 * 24 * 60 * 60 * 1000
    assert before + seven_days_ms <= result["expires_at"] <= after + seven_days_ms


def test_mint_by_non_member_raises_403(db_conn):
    owner = _register(db_conn, "owner@example.com")
    intruder = _register(db_conn, "intruder@example.com")
    _create_list(db_conn, owner, "devOwner")
    with pytest.raises(ApiError) as excinfo:
        invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", intruder)
    assert excinfo.value.status == 403


# ---- invited_email validation (T-93: token payload is colon-delimited, so a ---
# ---- colon-containing email mints a token that can never decode) -------------


def test_mint_rejects_colon_in_email(db_conn):
    owner = _register(db_conn, "owner@example.com")
    _create_list(db_conn, owner, "devOwner")
    with pytest.raises(ApiError) as excinfo:
        invites.mint(db_conn, KEY, BASE_URL, "list-1", "bad:actor@example.com", owner)
    assert excinfo.value.status == 422
    assert excinfo.value.code == "invalid_email"


def test_mint_rejects_overlong_email(db_conn):
    owner = _register(db_conn, "owner@example.com")
    _create_list(db_conn, owner, "devOwner")
    overlong = ("a" * (invites.MAX_INVITED_EMAIL_LENGTH - len("@example.com") + 1)) + "@example.com"
    assert len(overlong) > invites.MAX_INVITED_EMAIL_LENGTH
    with pytest.raises(ApiError) as excinfo:
        invites.mint(db_conn, KEY, BASE_URL, "list-1", overlong, owner)
    assert excinfo.value.status == 422
    assert excinfo.value.code == "invalid_email"


def test_mint_rejects_email_that_fails_the_registration_email_regex(db_conn):
    # Same EMAIL_RE as registration is used, e.g. it requires a domain dot;
    # the old "@" in email check alone would have let this mint fine.
    owner = _register(db_conn, "owner@example.com")
    _create_list(db_conn, owner, "devOwner")
    with pytest.raises(ApiError) as excinfo:
        invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@localhost", owner)
    assert excinfo.value.status == 422
    assert excinfo.value.code == "invalid_email"


# ---- revoke ------------------------------------------------------------------


def test_revoke_by_non_member_raises_403(db_conn):
    owner = _register(db_conn, "owner@example.com")
    intruder = _register(db_conn, "intruder@example.com")
    _create_list(db_conn, owner, "devOwner")
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)
    with pytest.raises(ApiError) as excinfo:
        invites.revoke(db_conn, intruder, result["invite_id"])
    assert excinfo.value.status == 403


def test_revoke_unknown_invite_raises_404(db_conn):
    owner = _register(db_conn, "owner@example.com")
    with pytest.raises(ApiError) as excinfo:
        invites.revoke(db_conn, owner, "not-a-real-invite-id")
    assert excinfo.value.status == 404


# ---- leave / orphans ----------------------------------------------------------


def test_leave_by_non_member_raises_403(db_conn):
    owner = _register(db_conn, "owner@example.com")
    stranger = _register(db_conn, "stranger@example.com")
    _create_list(db_conn, owner, "devOwner")
    with pytest.raises(ApiError) as excinfo:
        invites.leave(db_conn, stranger, "list-1")
    assert excinfo.value.status == 403


def test_leave_with_remaining_members_just_removes_membership(db_conn):
    owner = _register(db_conn, "owner@example.com")
    other = _register(db_conn, "other@example.com")
    _create_list(db_conn, owner, "devOwner")
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "other@example.com", owner)
    other_account = auth.Account(id=other, email="other@example.com")
    invites.redeem(db_conn, KEY, other_account, result["token"])

    invites.leave(db_conn, owner, "list-1")

    assert not _membership_exists(db_conn, owner, "list-1")
    assert _membership_exists(db_conn, other, "list-1")
    assert _list_row(db_conn, "list-1")["deleted"] == 0  # list survives, not orphaned


def test_last_leave_orphans_clears_items_tombstones_list_and_other_device_sees_it(db_conn):
    owner = _register(db_conn, "owner@example.com")
    _create_list(db_conn, owner, "devA")
    sync.apply_changes(
        db_conn, owner, "devA",
        {"items": [{"id": "item-1", "list_id": "list-1", "created_at": 1000,
                   "fields": {"name": {"value": "Milk", "updated_at": 100, "updated_by": "devA"}}}]},
    )
    # A second device of the SAME account does a cold sync and gets the list.
    initial_b = sync.delta(db_conn, owner, cursor=0, full_lists=[])
    cursor_b = initial_b["cursor"]
    assert {l["id"] for l in initial_b["changes"]["lists"]} == {"list-1"}

    # Device A leaves — this is the sole membership, so it orphans the list.
    invites.leave(db_conn, owner, "list-1")

    assert _live_items(db_conn, "list-1") == []
    list_row = _list_row(db_conn, "list-1")
    assert list_row["deleted"] == 1

    # The departing account's membership row is deliberately KEPT so device B
    # (same account) still converges on the deletion via a normal incremental
    # sync (Spec §3) — it is not zero, so GET /lists-style membership queries
    # would still find it, but the list itself is tombstoned (invisible).
    assert _membership_exists(db_conn, owner, "list-1")

    # Device B's next incremental sync (no full_lists needed) sees the tombstone.
    update_b = sync.delta(db_conn, owner, cursor=cursor_b, full_lists=[])
    tombstoned_list = next(l for l in update_b["changes"]["lists"] if l["id"] == "list-1")
    assert tombstoned_list["fields"]["deleted"]["value"] is True
    tombstoned_item = next(i for i in update_b["changes"]["items"] if i["id"] == "item-1")
    assert tombstoned_item["fields"]["deleted"]["value"] is True


def test_delete_account_orphan_cascade_clears_and_tombstones(db_conn):
    owner = _register(db_conn, "owner@example.com")
    _create_list(db_conn, owner, "devA")
    sync.apply_changes(
        db_conn, owner, "devA",
        {"items": [{"id": "item-1", "list_id": "list-1", "created_at": 1000,
                   "fields": {"name": {"value": "Milk", "updated_at": 100, "updated_by": "devA"}}}]},
    )

    accounts.delete_account(db_conn, owner, PW)

    assert _live_items(db_conn, "list-1") == []
    assert _list_row(db_conn, "list-1")["deleted"] == 1
    # Deleting the account cascades its membership too (no future session to
    # preserve propagation for).
    assert not _membership_exists(db_conn, owner, "list-1")


# ---- change-email invalidates pending invite match (ties S3) ----------------


def test_change_email_invalidates_pending_invite_match(db_conn):
    owner = _register(db_conn, "owner@example.com")
    invitee = _register(db_conn, "invitee@example.com")
    _create_list(db_conn, owner, "devOwner")
    result = invites.mint(db_conn, KEY, BASE_URL, "list-1", "invitee@example.com", owner)

    accounts.change_email(db_conn, invitee, PW, "newaddress@example.com")

    changed_account = auth.Account(id=invitee, email="newaddress@example.com")
    with pytest.raises(ApiError) as excinfo:
        invites.redeem(db_conn, KEY, changed_account, result["token"])
    assert excinfo.value.status == 409
    assert excinfo.value.code == "invite_email_mismatch"


# ---- HTTP layer ---------------------------------------------------------------


def _auth(token):
    return {"Authorization": f"Bearer {token}"}


def _register_and_login_http(client, email, device="dev"):
    client.post("/api/v1/register", json={"email": email, "password": PW})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": PW, "device_label": device}
    )
    return resp.get_json()["token"]


def _sync_http(client, token, changes, device_id="dev"):
    return client.post(
        "/api/v1/sync",
        json={"cursor": 0, "device_id": device_id, "full_lists": [], "changes": changes},
        headers=_auth(token),
    )


def test_mint_revoke_redeem_members_leave_http_flow(client):
    owner_token = _register_and_login_http(client, "owner3@example.com")
    invitee_token = _register_and_login_http(client, "invitee3@example.com")

    _sync_http(
        client, owner_token,
        {"lists": [{"id": "list-h1", "fields": {
            "name": {"value": "Groceries", "updated_at": 100, "updated_by": "dev"}
        }}]},
    )

    resp = client.post(
        "/api/v1/lists/list-h1/invites",
        json={"invited_email": "invitee3@example.com"},
        headers=_auth(owner_token),
    )
    assert resp.status_code == 201
    body = resp.get_json()
    assert set(body) == {"invite_id", "token", "url", "expires_at"}
    assert body["url"].endswith(f"/invite/{body['token']}")

    resp = client.get("/api/v1/lists/list-h1/members", headers=_auth(owner_token))
    assert resp.status_code == 200
    members_body = resp.get_json()
    assert [m["email"] for m in members_body["members"]] == ["owner3@example.com"]
    assert len(members_body["invites"]) == 1
    assert members_body["invites"][0]["id"] == body["invite_id"]

    resp = client.post(
        "/api/v1/invites/redeem", json={"token": body["token"]}, headers=_auth(invitee_token)
    )
    assert resp.status_code == 200
    assert resp.get_json() == {"list_id": "list-h1"}

    resp = client.get("/api/v1/lists/list-h1/members", headers=_auth(owner_token))
    emails = {m["email"] for m in resp.get_json()["members"]}
    assert emails == {"owner3@example.com", "invitee3@example.com"}
    assert resp.get_json()["invites"] == []  # now used, no longer pending

    resp = client.post("/api/v1/lists/list-h1/leave", headers=_auth(invitee_token))
    assert resp.status_code == 204

    resp = client.get("/api/v1/lists/list-h1/members", headers=_auth(invitee_token))
    assert resp.status_code == 403  # no longer a member


def test_members_response_includes_account_id_and_initials(client):
    owner_token = _register_and_login_http(client, "owner5@example.com")
    _sync_http(
        client, owner_token,
        {"lists": [{"id": "list-h5", "fields": {
            "name": {"value": "Groceries", "updated_at": 100, "updated_by": "dev"}
        }}]},
    )

    resp = client.get("/api/v1/lists/list-h5/members", headers=_auth(owner_token))

    assert resp.status_code == 200
    [member] = resp.get_json()["members"]
    assert member["email"] == "owner5@example.com"
    assert member["initials"] == "OW"  # derived default: owner5@... -> "OW"
    assert member["account_id"]  # present and non-empty; exact value not asserted


def test_revoke_invite_http(client):
    owner_token = _register_and_login_http(client, "owner4@example.com")
    invitee_token = _register_and_login_http(client, "invitee4@example.com")
    _sync_http(
        client, owner_token,
        {"lists": [{"id": "list-h2", "fields": {
            "name": {"value": "Groceries", "updated_at": 100, "updated_by": "dev"}
        }}]},
    )
    resp = client.post(
        "/api/v1/lists/list-h2/invites",
        json={"invited_email": "invitee4@example.com"},
        headers=_auth(owner_token),
    )
    invite_id = resp.get_json()["invite_id"]
    token = resp.get_json()["token"]

    resp = client.delete(f"/api/v1/invites/{invite_id}", headers=_auth(owner_token))
    assert resp.status_code == 204

    resp = client.post(
        "/api/v1/invites/redeem", json={"token": token}, headers=_auth(invitee_token)
    )
    assert resp.status_code == 409
    assert resp.get_json()["error"] == "invite_revoked"


def test_invite_routes_without_token_401(client):
    resp = client.post("/api/v1/lists/list-x/invites", json={"invited_email": "a@example.com"})
    assert resp.status_code == 401
    resp = client.post("/api/v1/invites/redeem", json={"token": "whatever"})
    assert resp.status_code == 401
    resp = client.get("/api/v1/lists/list-x/members")
    assert resp.status_code == 401
    resp = client.post("/api/v1/lists/list-x/leave")
    assert resp.status_code == 401


# ---- token payload delimiter safety (T-117) ---------------------------------


def test_mint_rejects_a_list_id_containing_a_colon(db_conn):
    """The token payload is colon-delimited, and list ids are client-minted arbitrary strings —
    so a list id carrying extra colons made the server sign a payload that re-splits into
    different fields. Never redeemable (the trailing int(expires_at) cast always failed), but the
    whole defence rested on that cast."""
    owner = _register(db_conn, "owner@example.com")
    evil_id = "TARGETLIST:attacker@evil.com:99999999999999"
    _create_list(db_conn, owner, "devOwner", list_id=evil_id, name="Evil")

    with pytest.raises(ApiError) as excinfo:
        invites.mint(
            db_conn, KEY, BASE_URL, evil_id, "owner@example.com", owner
        )

    assert excinfo.value.status == 422
    assert excinfo.value.code == "invalid_list_id"


def test_redeem_joins_the_list_recorded_in_the_db_not_the_one_in_the_token(db_conn):
    """Defence in depth: even a token whose payload names a different list must only ever grant
    the membership the invite row records."""
    owner = _register(db_conn, "owner@example.com")
    invitee = _register(db_conn, "guest@example.com")
    _create_list(db_conn, owner, "devOwner", list_id="list-real", name="Real")
    _create_list(db_conn, owner, "devOwner", list_id="list-other", name="Other")

    minted = invites.mint(db_conn, KEY, BASE_URL, "list-real", "guest@example.com", owner)

    # Re-sign a payload naming a DIFFERENT list with the server's own key, so the HMAC is valid
    # and only the DB lookup can catch the mismatch.
    forged = invites._encode_token(
        KEY, minted["invite_id"], "list-other", "guest@example.com", minted["expires_at"]
    )
    account = auth.Account(id=invitee, email="guest@example.com")
    joined = invites.redeem(db_conn, KEY, account, forged)

    assert joined == "list-real"
    assert _membership_exists(db_conn, invitee, "list-real")
    assert not _membership_exists(db_conn, invitee, "list-other")
