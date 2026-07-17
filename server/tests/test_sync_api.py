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


# ---- T-85: input validation — mis-typed / oversized / junk payloads ---------
#
# Every one of these must be a 422 quarantine envelope (never a 500 / stored
# garbage). Where a row is identifiable, the envelope carries row_id (+ field).


def _seed_list(client, token, list_id="list-1", ts=100):
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"lists": [_mk_list(list_id, "Groceries", ts, "devA")]},
    )
    assert resp.status_code == 200


def test_sync_updated_at_out_of_int64_range_422_not_500(client):
    token = _register_and_login(client)
    _seed_list(client, token)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"items": [_mk_item("item-1", "list-1", name=("Milk", 2 ** 65, "devA"))]},
    )
    assert resp.status_code == 422
    body = resp.get_json()
    assert body["row_id"] == "item-1"
    assert body["field"] == "name"


def test_sync_id_as_dict_422_invalid_row(client):
    token = _register_and_login(client)
    _seed_list(client, token)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"items": [{"id": {"weird": 1}, "list_id": "list-1",
                            "fields": {"name": _clock("Milk", 100, "devA")}}]},
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_row"


def test_sync_changes_as_string_422_not_500(client):
    token = _register_and_login(client)
    resp = client.post(
        "/api/v1/sync",
        json={"cursor": 0, "device_id": "devA", "full_lists": [], "changes": "junk"},
        headers=_auth(token),
    )
    assert resp.status_code == 422


def test_sync_created_at_as_string_422(client):
    token = _register_and_login(client)
    _seed_list(client, token)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"items": [{"id": "item-1", "list_id": "list-1", "created_at": "yesterday",
                            "fields": {"name": _clock("Milk", 100, "devA")}}]},
    )
    assert resp.status_code == 422
    assert resp.get_json()["row_id"] == "item-1"


def test_sync_stores_as_string_422_with_row_and_field(client):
    token = _register_and_login(client)
    _seed_list(client, token)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"items": [_mk_item("item-1", "list-1",
                                    name=("Milk", 100, "devA"),
                                    stores=("not-a-list", 100, "devA"))]},
    )
    assert resp.status_code == 422
    body = resp.get_json()
    assert body["row_id"] == "item-1"
    assert body["field"] == "stores"


def test_sync_category_order_as_dict_422_with_row_and_field(client):
    token = _register_and_login(client)
    _seed_list(client, token)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"lists": [{"id": "list-1",
                            "fields": {"category_order": _clock({"not": "a list"}, 200, "devA")}}]},
    )
    assert resp.status_code == 422
    body = resp.get_json()
    assert body["row_id"] == "list-1"
    assert body["field"] == "category_order"


def test_sync_stores_element_not_a_string_422(client):
    token = _register_and_login(client)
    _seed_list(client, token)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"items": [_mk_item("item-1", "list-1",
                                    name=("Milk", 100, "devA"),
                                    stores=(["ok", 5], 100, "devA"))]},
    )
    assert resp.status_code == 422
    assert resp.get_json()["field"] == "stores"


def test_sync_oversized_name_422(client):
    token = _register_and_login(client)
    _seed_list(client, token)
    huge = "x" * 100_000
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"items": [_mk_item("item-1", "list-1", name=(huge, 100, "devA"))]},
    )
    assert resp.status_code == 422
    assert resp.get_json()["row_id"] == "item-1"


def test_sync_quantity_as_int_rejected_not_coerced(client):
    token = _register_and_login(client)
    _seed_list(client, token)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"items": [_mk_item("item-1", "list-1",
                                    name=("Milk", 100, "devA"),
                                    quantity=(12345, 100, "devA"))]},
    )
    assert resp.status_code == 422
    assert resp.get_json()["field"] == "quantity"


def test_sync_updated_by_non_string_422(client):
    token = _register_and_login(client)
    _seed_list(client, token)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={"items": [{"id": "item-1", "list_id": "list-1",
                            "fields": {"name": {"value": "Milk", "updated_at": 100,
                                                "updated_by": {"nope": 1}}}}]},
    )
    assert resp.status_code == 422
    assert resp.get_json()["field"] == "name"


def test_sync_full_lists_not_a_list_422(client):
    token = _register_and_login(client)
    resp = client.post(
        "/api/v1/sync",
        json={"cursor": 0, "device_id": "devA", "full_lists": "list-1", "changes": {}},
        headers=_auth(token),
    )
    assert resp.status_code == 422


def test_sync_device_id_non_string_422(client):
    token = _register_and_login(client)
    resp = client.post(
        "/api/v1/sync",
        json={"cursor": 0, "device_id": {"nope": 1}, "full_lists": [], "changes": {}},
        headers=_auth(token),
    )
    assert resp.status_code == 422


def test_sync_maximal_valid_payload_applies_cleanly(client):
    """A maximal, fully-populated push (every syncable field, plus a second row
    exercising nulls on every nullable field) still applies and round-trips."""
    token = _register_and_login(client)
    resp = _sync(
        client, token, cursor=0, device_id="devA",
        changes={
            "lists": [{
                "id": "list-1", "created_at": 1000,
                "fields": {
                    "name": _clock("Groceries", 100, "devA"),
                    "category_order": _clock(["dairy", "produce", "bakery"], 100, "devA"),
                    "notes": _clock("weekly shop", 100, "devA"),
                },
            }],
            "items": [
                {
                    "id": "item-full", "list_id": "list-1", "created_at": 1000,
                    "fields": {
                        "name": _clock("Milk", 100, "devA"),
                        "category": _clock("dairy", 100, "devA"),
                        "stores": _clock(["Aldi", "Rewe"], 100, "devA"),
                        "quantity": _clock("2 l", 100, "devA"),
                        "price": _clock({"amount": "1.99", "currency": "EUR"}, 100, "devA"),
                        "note": _clock("cold aisle", 100, "devA"),
                        "status": _clock("todo", 100, "devA"),
                        "deleted": _clock(False, 100, "devA"),
                    },
                },
                {
                    "id": "item-nulls", "list_id": "list-1", "created_at": 1000,
                    "fields": {
                        "name": _clock("Bread", 100, "devA"),
                        "category": _clock(None, 100, "devA"),
                        "stores": _clock([], 100, "devA"),
                        "quantity": _clock(None, 100, "devA"),
                        "price": _clock(None, 100, "devA"),
                        "note": _clock(None, 100, "devA"),
                    },
                },
            ],
        },
    )
    assert resp.status_code == 200

    pull = _sync(client, token, cursor=0, device_id="devB").get_json()
    items = {i["id"]: i for i in pull["changes"]["items"]}
    full = items["item-full"]["fields"]
    assert full["stores"]["value"] == ["Aldi", "Rewe"]
    assert full["price"]["value"] == {"amount": "1.99", "currency": "EUR"}
    assert full["quantity"]["value"] == "2 l"
    nulls = items["item-nulls"]["fields"]
    assert nulls["category"]["value"] is None
    assert nulls["price"]["value"] is None
    assert nulls["stores"]["value"] == []
    lst = next(l for l in pull["changes"]["lists"] if l["id"] == "list-1")
    assert lst["fields"]["category_order"]["value"] == ["dairy", "produce", "bakery"]
    assert lst["fields"]["notes"]["value"] == "weekly shop"


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


# ---- adversarial: a non-member cannot reach a list by ANY path -------------


def test_non_member_cannot_access_a_list_in_any_way(client):
    """Security sweep (authorization): a second, logged-in account that was never
    made a member of a list must not be able to read, enumerate, edit, hijack,
    invite into, or forge access to it — the server is the sole enforcement point."""
    import base64

    def _b64url(raw: bytes) -> str:
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

    # Account A owns a private list with one item.
    token_a = _register_and_login(client, email="alice@example.com", device="devA")
    resp = _sync(
        client, token_a, cursor=0, device_id="devA",
        changes={
            "lists": [_mk_list("list-victim", "Alice's list", 100, "devA")],
            "items": [_mk_item("item-victim", "list-victim", name=("Milk", 100, "devA"))],
        },
    )
    assert resp.status_code == 200

    # Account B: a different, logged-in account, never invited to list-victim.
    token_b = _register_and_login(client, email="bob@example.com", device="devB")

    # (a) Enumeration: B's list index never includes it.
    lists_b = client.get("/api/v1/lists", headers=_auth(token_b)).get_json()["lists"]
    assert all(lst["id"] != "list-victim" for lst in lists_b)

    # (b) Incremental pull: B's delta carries none of A's rows.
    delta_b = _sync(client, token_b, cursor=0, device_id="devB").get_json()
    assert "list-victim" not in {lst["id"] for lst in delta_b["changes"]["lists"]}
    assert "item-victim" not in {itm["id"] for itm in delta_b["changes"]["items"]}

    # (c) Full-snapshot pull of the list id: refused, not leaked.
    resp = _sync(client, token_b, cursor=0, device_id="devB", full_lists=["list-victim"])
    assert resp.status_code == 403
    assert resp.get_json()["error"] == "not_a_member"

    # (d) Members roster: refused (uniform 403 — no exists-vs-not-yours leak).
    resp = client.get("/api/v1/lists/list-victim/members", headers=_auth(token_b))
    assert resp.status_code == 403

    # (e) Push a brand-new item into A's list: refused.
    resp = _sync(
        client, token_b, cursor=0, device_id="devB",
        changes={"items": [_mk_item("item-intruder", "list-victim", name=("Intruder", 200, "devB"))]},
    )
    assert resp.status_code == 403
    assert resp.get_json()["error"] == "not_a_member"

    # (f) Edit A's existing item: refused.
    resp = _sync(
        client, token_b, cursor=0, device_id="devB",
        changes={"items": [_mk_item("item-victim", "list-victim", name=("Hacked", 999, "devB"))]},
    )
    assert resp.status_code == 403

    # (g) Hijack via mislabelled list_id: the server authorizes against the item's
    # STORED list, not the client-supplied one, so this is still refused.
    resp = _sync(
        client, token_b, cursor=0, device_id="devB",
        changes={"items": [_mk_item("item-victim", "some-other-list", name=("Hacked", 1000, "devB"))]},
    )
    assert resp.status_code == 403

    # (h) Invite themselves in: refused (only members can mint invites).
    resp = client.post(
        "/api/v1/lists/list-victim/invites",
        json={"invited_email": "bob@example.com"},
        headers=_auth(token_b),
    )
    assert resp.status_code == 403

    # (i) Forge an invite token for the list: rejected at the HMAC signature check.
    forged_payload = _b64url(b"fake-invite:list-victim:bob@example.com:99999999999999")
    forged_token = f"{forged_payload}.{_b64url(b'not-a-real-signature')}"
    resp = client.post(
        "/api/v1/invites/redeem", json={"token": forged_token}, headers=_auth(token_b)
    )
    assert resp.status_code == 400
    assert resp.get_json()["error"] == "invalid_token"

    # A's item is still the untouched original after all of B's attempts.
    delta_a = _sync(
        client, token_a, cursor=0, device_id="devA", full_lists=["list-victim"]
    ).get_json()
    victim = next(i for i in delta_a["changes"]["items"] if i["id"] == "item-victim")
    assert victim["fields"]["name"]["value"] == "Milk"

    # And B still has no lists at all — nothing leaked into their world.
    assert client.get("/api/v1/lists", headers=_auth(token_b)).get_json()["lists"] == []
