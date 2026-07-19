"""End-to-end full-system test (S9): the complete sharing + sync + GC
lifecycle through the real HTTP API, tying together every server ticket
(S1-S8) into one story."""

from shoppinglist_server import gc

PW = "password123"


def _auth(token):
    return {"Authorization": f"Bearer {token}"}


def _register_and_login(client, email, device):
    client.post("/api/v1/register", json={"email": email, "password": PW})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": PW, "device_label": device}
    )
    return resp.get_json()["token"]


def _sync(client, token, cursor, device_id, changes=None, full_lists=None):
    resp = client.post(
        "/api/v1/sync",
        json={
            "cursor": cursor,
            "device_id": device_id,
            "full_lists": full_lists or [],
            "changes": changes or {},
        },
        headers=_auth(token),
    )
    assert resp.status_code == 200, resp.get_json()
    return resp.get_json()


def test_full_system_lifecycle(client, app):
    # 1. Register two accounts.
    token_a = _register_and_login(client, "owner@example.com", "devA")
    token_b = _register_and_login(client, "invitee@example.com", "devB")

    # 2. A creates a list with one item.
    body = _sync(
        client, token_a, cursor=0, device_id="devA",
        changes={
            "lists": [{"id": "list-1", "fields": {
                "name": {"value": "Groceries", "updated_at": 100, "updated_by": "devA"}
            }}],
            "items": [{"id": "item-1", "list_id": "list-1", "fields": {
                "name": {"value": "Milk", "updated_at": 100, "updated_by": "devA"}
            }}],
        },
    )
    cursor_a = body["cursor"]

    # 3. A invites B.
    resp = client.post(
        "/api/v1/lists/list-1/invites",
        json={"invited_email": "invitee@example.com"},
        headers=_auth(token_a),
    )
    assert resp.status_code == 201
    invite = resp.get_json()

    # 4. B redeems the invite.
    resp = client.post(
        "/api/v1/invites/redeem", json={"token": invite["token"]}, headers=_auth(token_b)
    )
    assert resp.status_code == 200
    assert resp.get_json() == {"list_id": "list-1"}

    # 5. B pulls the list via full_lists (join-flow snapshot sync).
    body = _sync(client, token_b, cursor=0, device_id="devB", full_lists=["list-1"])
    cursor_b = body["cursor"]
    assert {l["id"] for l in body["changes"]["lists"]} == {"list-1"}
    item = next(i for i in body["changes"]["items"] if i["id"] == "item-1")
    assert item["fields"]["name"]["value"] == "Milk"

    # 6. Concurrent offline edits: A edits category, B edits quantity.
    _sync(
        client, token_a, cursor=cursor_a, device_id="devA",
        changes={"items": [{"id": "item-1", "list_id": "list-1", "fields": {
            "category": {"value": "dairy", "updated_at": 200, "updated_by": "devA"}
        }}]},
    )
    _sync(
        client, token_b, cursor=cursor_b, device_id="devB",
        changes={"items": [{"id": "item-1", "list_id": "list-1", "fields": {
            "quantity": {"value": "2l", "updated_at": 210, "updated_by": "devB"}
        }}]},
    )

    # 7. Both pull fresh; mirrors are identical and correctly field-merged.
    body_a = _sync(client, token_a, cursor=0, device_id="devA")
    body_b = _sync(client, token_b, cursor=0, device_id="devB")
    assert body_a["changes"] == body_b["changes"]
    item = next(i for i in body_a["changes"]["items"] if i["id"] == "item-1")
    assert item["fields"]["category"]["value"] == "dairy"
    assert item["fields"]["quantity"]["value"] == "2l"

    # 8. Leave x2: A leaves first (B remains -> list survives); then B leaves
    # (now the last member -> orphans the list).
    resp = client.post("/api/v1/lists/list-1/leave", headers=_auth(token_a))
    assert resp.status_code == 204

    resp = client.get("/api/v1/lists/list-1/members", headers=_auth(token_b))
    assert resp.status_code == 200
    assert [m["email"] for m in resp.get_json()["members"]] == ["invitee@example.com"]

    resp = client.post("/api/v1/lists/list-1/leave", headers=_auth(token_b))
    assert resp.status_code == 204

    # List is now tombstoned (orphaned): gone from GET /lists.
    resp = client.get("/api/v1/lists", headers=_auth(token_b))
    assert resp.get_json()["lists"] == []

    # 9. Orphan purge via gc.run (simulate 91 days later).
    from shoppinglist_server import auth as auth_module
    from shoppinglist_server import db as db_module
    from shoppinglist_server import get_config_by_name

    config = get_config_by_name(app)

    conn = db_module.connect(config["database_path"])
    far_future = auth_module.now_ms() + 91 * 24 * 60 * 60 * 1000
    result = gc.run(conn, far_future)
    # Both logins above are 91 days idle at far_future, past even the long
    # 62-day default window, so the same sweep collects their sessions (T-104).
    assert result == {
        "items_purged": 1,
        "lists_purged": 1,
        "invites_purged": 0,
        "sessions_purged": 2,
    }
    assert conn.execute("SELECT COUNT(*) AS n FROM auth_tokens").fetchone()["n"] == 0

    assert conn.execute("SELECT 1 FROM lists WHERE id = 'list-1'").fetchone() is None
    assert conn.execute("SELECT 1 FROM items WHERE id = 'item-1'").fetchone() is None
    assert conn.execute(
        "SELECT 1 FROM memberships WHERE list_id = 'list-1'"
    ).fetchone() is None
    conn.close()
