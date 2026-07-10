"""HTTP-layer integration tests for POST /sync and GET /lists."""

EMAIL = "carol@example.com"
PW = "password123"


def _clock(value, ts, by):
    return {"value": value, "updated_at": ts, "updated_by": by}


def _mk_list(list_id, name, ts, by, category_order=None):
    fields = {"name": _clock(name, ts, by)}
    if category_order is not None:
        fields["category_order"] = _clock(category_order, ts, by)
    return {"id": list_id, "fields": fields}


def _mk_item(item_id, list_id, **field_clocks):
    fields = {k: _clock(*v) for k, v in field_clocks.items()}
    return {"id": item_id, "list_id": list_id, "fields": fields}


def _auth(token):
    return {"Authorization": f"Bearer {token}"}


def _register_and_login(client, email=EMAIL, device="devA"):
    client.post("/api/v1/register", json={"email": email, "password": PW})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": PW, "device_label": device}
    )
    return resp.get_json()["token"]


def _sync(client, token, cursor, device_id, changes=None, full_lists=None):
    return client.post(
        "/api/v1/sync",
        json={
            "cursor": cursor,
            "device_id": device_id,
            "full_lists": full_lists or [],
            "changes": changes or {},
        },
        headers=_auth(token),
    )


# ---- two-device convergence (main acceptance criterion) --------------------


def test_two_devices_converge_after_sync(client):
    token_a = _register_and_login(client, device="devA")
    token_b = _register_and_login(client, device="devB")  # same account, 2nd device

    # Device A creates a list with one item.
    resp = _sync(
        client, token_a, cursor=0, device_id="devA",
        changes={
            "lists": [_mk_list("list-1", "Groceries", 100, "devA")],
            "items": [
                _mk_item(
                    "item-1", "list-1",
                    name=("Milk", 100, "devA"),
                    category=("dairy", 100, "devA"),
                )
            ],
        },
    )
    assert resp.status_code == 200
    cursor_a = resp.get_json()["cursor"]

    # Device B, starting cold (cursor 0), receives the list + item.
    resp = _sync(client, token_b, cursor=0, device_id="devB")
    assert resp.status_code == 200
    body_b = resp.get_json()
    cursor_b = body_b["cursor"]
    assert {l["id"] for l in body_b["changes"]["lists"]} == {"list-1"}
    item = next(i for i in body_b["changes"]["items"] if i["id"] == "item-1")
    assert item["fields"]["name"]["value"] == "Milk"
    assert item["fields"]["category"]["value"] == "dairy"

    # Both edit "offline": A changes category + renames (ts=200); B changes
    # quantity + renames with a later ts=210 -> same-field (name) conflict.
    resp = _sync(
        client, token_a, cursor=cursor_a, device_id="devA",
        changes={
            "items": [
                _mk_item(
                    "item-1", "list-1",
                    category=("fridge", 200, "devA"),
                    name=("MilkA", 200, "devA"),
                )
            ]
        },
    )
    assert resp.status_code == 200

    resp = _sync(
        client, token_b, cursor=cursor_b, device_id="devB",
        changes={
            "items": [
                _mk_item(
                    "item-1", "list-1",
                    quantity=("2l", 210, "devB"),
                    name=("MilkB", 210, "devB"),
                )
            ]
        },
    )
    assert resp.status_code == 200

    # Both devices now pull a fresh full sync: mirrors must be identical and
    # correctly field-merged, with exact field-clock round-trip.
    resp_a = _sync(client, token_a, cursor=0, device_id="devA")
    resp_b = _sync(client, token_b, cursor=0, device_id="devB")
    changes_a = resp_a.get_json()["changes"]
    changes_b = resp_b.get_json()["changes"]
    assert changes_a == changes_b

    item = next(i for i in changes_a["items"] if i["id"] == "item-1")
    assert item["fields"]["category"] == {
        "value": "fridge", "updated_at": 200, "updated_by": "devA"
    }
    assert item["fields"]["quantity"] == {
        "value": "2l", "updated_at": 210, "updated_by": "devB"
    }
    # Later timestamp (devB, ts=210) wins the same-field name conflict.
    assert item["fields"]["name"] == {
        "value": "MilkB", "updated_at": 210, "updated_by": "devB"
    }


# ---- error paths ------------------------------------------------------------


def test_sync_without_token_401(client):
    resp = client.post("/api/v1/sync", json={"cursor": 0, "device_id": "d", "changes": {}})
    assert resp.status_code == 401


def test_sync_full_lists_non_member_403(client):
    token_a = _register_and_login(client, "owner@example.com", "devA")
    token_b = _register_and_login(client, "outsider@example.com", "devB")
    _sync(
        client, token_a, cursor=0, device_id="devA",
        changes={"lists": [_mk_list("list-1", "Groceries", 100, "devA")]},
    )
    resp = _sync(client, token_b, cursor=0, device_id="devB", full_lists=["list-1"])
    assert resp.status_code == 403


def test_sync_stale_cursor_410_but_still_applies_pushed_changes(client, app):
    token = _register_and_login(client)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"lists": [_mk_list("list-1", "Groceries", 100, "devA")]},
    )
    assert resp.status_code == 200

    from shoppinglist_server import db as db_module
    from shoppinglist_server import get_config_by_name

    config = get_config_by_name(app)
    conn = db_module.connect(config["database_path"])
    conn.execute("UPDATE meta SET gc_horizon = 999999")
    conn.commit()
    conn.close()

    resp = _sync(
        client, token, cursor=1, device_id="devA",
        changes={
            "items": [_mk_item("item-1", "list-1", name=("Milk", 500, "devA"))]
        },
    )
    assert resp.status_code == 410
    assert resp.get_json()["error"] == "full_resync_required"

    # The pushed item must have been applied despite the 410.
    resp = _sync(client, token, cursor=0, device_id="devA")
    assert resp.status_code == 200
    item_ids = {i["id"] for i in resp.get_json()["changes"]["items"]}
    assert "item-1" in item_ids


def test_sync_invalid_status_422(client):
    token = _register_and_login(client)
    _sync(
        client, token, cursor=0, device_id="devA",
        changes={"lists": [_mk_list("list-1", "Groceries", 100, "devA")]},
    )
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={
            "items": [
                _mk_item(
                    "item-1", "list-1",
                    name=("Milk", 100, "devA"),
                    status=("bought", 100, "devA"),
                )
            ]
        },
    )
    assert resp.status_code == 422


def test_sync_validation_422_names_the_offending_row_and_field(client):
    token = _register_and_login(client)
    _sync(
        client, token, cursor=0, device_id="devA",
        changes={"lists": [_mk_list("list-1", "Groceries", 100, "devA")]},
    )
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={
            "items": [
                _mk_item(
                    "item-1", "list-1",
                    name=("Milk", 100, "devA"),
                    price=({"amount": "1,99", "currency": "EUR"}, 100, "devA"),
                )
            ]
        },
    )
    assert resp.status_code == 422
    body = resp.get_json()
    # Canonical envelope keys are still present and unshadowed...
    assert body["error"] == "invalid_price"
    assert body["message"]
    # ...plus the additive quarantine hints the client uses (T-32).
    assert body["row_id"] == "item-1"
    assert body["field"] == "price"


def test_sync_missing_cursor_422(client):
    token = _register_and_login(client)
    resp = client.post(
        "/api/v1/sync",
        json={"device_id": "devA", "changes": {}},
        headers=_auth(token),
    )
    assert resp.status_code == 422


# ---- GET /lists --------------------------------------------------------------


def test_get_lists_returns_only_member_lists(client):
    token_a = _register_and_login(client, "owner2@example.com", "devA")
    token_b = _register_and_login(client, "outsider2@example.com", "devB")
    _sync(
        client, token_a, cursor=0, device_id="devA",
        changes={
            "lists": [
                _mk_list("list-x", "Groceries", 100, "devA", category_order=["dairy"])
            ]
        },
    )

    resp = client.get("/api/v1/lists", headers=_auth(token_a))
    assert resp.status_code == 200
    lists = resp.get_json()["lists"]
    assert lists == [{"id": "list-x", "name": "Groceries", "category_order": ["dairy"]}]

    resp = client.get("/api/v1/lists", headers=_auth(token_b))
    assert resp.get_json()["lists"] == []


def test_get_lists_excludes_deleted(client):
    token = _register_and_login(client, "deleter@example.com", "devA")
    _sync(
        client, token, cursor=0, device_id="devA",
        changes={"lists": [_mk_list("list-y", "ToDelete", 100, "devA")]},
    )
    _sync(
        client, token, cursor=0, device_id="devA",
        changes={
            "lists": [
                {"id": "list-y", "fields": {"deleted": _clock(True, 200, "devA")}}
            ]
        },
    )
    resp = client.get("/api/v1/lists", headers=_auth(token))
    assert resp.get_json()["lists"] == []


def test_get_lists_without_token_401(client):
    resp = client.get("/api/v1/lists")
    assert resp.status_code == 401
