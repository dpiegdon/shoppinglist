"""A JSON body that is not an object is a 422, never a 500 (T-236).

`[1]`, `"abc"`, `5` and `true` are all valid JSON and all truthy, so the old
`request.get_json(...) or {}` handed them straight to `.get` and the route died with an
AttributeError. These routes are reachable without a token, so the crash was too.
"""

import pytest

EMAIL = "body@example.com"
PW = "password123"

NON_OBJECT_BODIES = ["[1]", '"abc"', "5", "true"]

UNAUTHENTICATED_ROUTES = [
    ("POST", "/api/v1/register"),
    ("POST", "/api/v1/login"),
]

AUTHENTICATED_ROUTES = [
    ("POST", "/api/v1/sync"),
    ("POST", "/api/v1/account/change-password"),
    ("POST", "/api/v1/account/change-email"),
    ("POST", "/api/v1/invites/redeem"),
    ("PATCH", "/api/v1/settings"),
    ("DELETE", "/api/v1/account"),
]


def _token(client):
    client.post("/api/v1/register", json={"email": EMAIL, "password": PW})
    resp = client.post(
        "/api/v1/login", json={"email": EMAIL, "password": PW, "device_label": "dev"}
    )
    return resp.get_json()["token"]


def _send(client, method, path, body, headers=None):
    return client.open(
        path,
        method=method,
        data=body,
        content_type="application/json",
        headers=headers or {},
    )


@pytest.mark.parametrize("method,path", UNAUTHENTICATED_ROUTES)
@pytest.mark.parametrize("body", NON_OBJECT_BODIES)
def test_non_object_body_is_422_on_unauthenticated_routes(client, method, path, body):
    resp = _send(client, method, path, body)

    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_request"


@pytest.mark.parametrize("method,path", AUTHENTICATED_ROUTES)
@pytest.mark.parametrize("body", NON_OBJECT_BODIES)
def test_non_object_body_is_422_on_authenticated_routes(client, method, path, body):
    headers = {"Authorization": f"Bearer {_token(client)}"}

    resp = _send(client, method, path, body, headers)

    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_request"


@pytest.mark.parametrize("body", ["", "null", "not json at all", "{}"])
def test_missing_or_unparseable_body_still_reads_as_an_empty_object(client, body):
    """Unchanged behaviour: the route sees its fields as absent and answers for that,
    rather than for the body's shape."""
    resp = _send(client, "POST", "/api/v1/register", body)

    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_email"


# ---- field TYPES, not just body shape (T-256) -------------------------------
#
# T-236 (above) only proved the body as a whole is an object. It never sent a well-shaped body
# whose individual FIELDS were the wrong JSON type — a non-string where a route expects a string —
# and three call sites turned out to skip an isinstance check T-116 put on register/login:
# invites.decode_token ((token or "").split), accounts._require_password (hands the value straight
# to werkzeug) and invites.mint (len()/EMAIL_RE.match()). Each is an unhandled 500 in production
# (TESTING off; see `prod_client` below) rather than the documented JSON error envelope.
#
# This sweep exercises every field read from a JSON body across every route that reads one, so a
# future site with the same gap fails here instead of shipping. Each case sends a request whose
# OTHER fields are valid and only the field under test carries a wrong-typed value, so the
# assertion isolates that one field's handling.

FIELD_TYPE_ADMIN_EMAIL = "fieldtypes-admin@example.com"

# Representative non-string JSON scalars/containers. `True` matters on its own: `x or default`
# treats it as present (unlike `None`/`""`), so a bug that relies on falsiness to filter bad input
# lets a bool straight through, same as the int/float/list/dict cases.
NON_STRING_VALUES = [5, 5.5, [1], {"a": 1}, True]
NON_BOOL_VALUES = ["true", 1, 1.5, [1], {"a": 1}]


@pytest.fixture
def prod_client(app):
    """The same app as `client`, but with TESTING off (T-256).

    Flask's PROPAGATE_EXCEPTIONS follows TESTING when unset: with TESTING on, an exception this
    module's error handlers don't recognise (ApiError, RequestEntityTooLarge,
    sqlite3.OperationalError — see create_blueprint) escapes straight into the test process rather
    than becoming a response, which would make a genuine unhandled-500 regression fail with an
    AttributeError/TypeError traceback pointing at the WRONG place instead of as the 500 a real
    deployment sends. Off, it becomes that real response, so `resp.status_code == 500` actually
    means what it says (this is the same fixture-config pattern test_hardening.py's
    test_a_genuine_operational_error_is_not_disguised_as_congestion uses).
    """
    app.config["TESTING"] = False
    return app.test_client()


def _bearer(token):
    return {"Authorization": f"Bearer {token}"}


def _registered_token(client, email="fieldtypes@example.com", password="password123"):
    client.post("/api/v1/register", json={"email": email, "password": password})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": password, "device_label": "dev"}
    )
    return resp.get_json()["token"]


def _assert_not_a_crash(resp):
    """A wrong-typed field must never reach an unhandled 500 — which Flask (TESTING off) renders
    as an HTML page, not JSON. Most fields are meant to be rejected (a 4xx with the documented
    JSON error envelope); a few (e.g. login's `platform`) are deliberately permissive and fall
    back to a default instead of refusing the whole request, so 2xx is a legitimate outcome here
    too — this only guards against the crash, not against every field being made strict."""
    assert resp.status_code < 500, resp.get_data(as_text=True)[:500]
    if resp.status_code >= 400:
        body = resp.get_json()
        assert body is not None and "error" in body, resp.get_data(as_text=True)[:500]


def _field_case_id(value):
    return f"{type(value).__name__}:{value!r}"


@pytest.mark.parametrize("field", ["email", "password"])
@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_register_field_types(prod_client, field, bad):
    body = {"email": "reg-types@example.com", "password": "password123"}
    body[field] = bad
    resp = prod_client.post("/api/v1/register", json=body)
    _assert_not_a_crash(resp)


@pytest.mark.parametrize("field", ["email", "password", "device_label", "platform"])
@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_login_field_types(prod_client, field, bad):
    prod_client.post(
        "/api/v1/register", json={"email": "login-types@example.com", "password": "password123"}
    )
    body = {
        "email": "login-types@example.com",
        "password": "password123",
        "device_label": "dev",
        "platform": "web",
    }
    body[field] = bad
    resp = prod_client.post("/api/v1/login", json=body)
    _assert_not_a_crash(resp)


@pytest.mark.parametrize("field", ["current_password", "new_password"])
@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_change_password_field_types(prod_client, field, bad):
    """Covers accounts._require_password (current_password) directly (T-256)."""
    token = _registered_token(prod_client, email="chpw-types@example.com")
    body = {"current_password": "password123", "new_password": "newpassword456"}
    body[field] = bad
    resp = prod_client.post("/api/v1/account/change-password", json=body, headers=_bearer(token))
    _assert_not_a_crash(resp)


@pytest.mark.parametrize("field", ["password", "new_email"])
@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_change_email_field_types(prod_client, field, bad):
    """Covers accounts._require_password (password) directly (T-256)."""
    token = _registered_token(prod_client, email="chem-types@example.com")
    body = {"password": "password123", "new_email": "new-chem-types@example.com"}
    body[field] = bad
    resp = prod_client.post("/api/v1/account/change-email", json=body, headers=_bearer(token))
    _assert_not_a_crash(resp)


@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_delete_account_field_types(prod_client, bad):
    """Covers accounts._require_password (password) directly (T-256)."""
    token = _registered_token(prod_client, email="delacct-types@example.com")
    resp = prod_client.delete("/api/v1/account", json={"password": bad}, headers=_bearer(token))
    _assert_not_a_crash(resp)


@pytest.mark.parametrize("field", ["default_currency", "initials"])
@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_settings_patch_field_types(prod_client, field, bad):
    token = _registered_token(prod_client, email="settings-types@example.com")
    body = {"default_currency": "EUR", "initials": "AB"}
    body[field] = bad
    resp = prod_client.patch("/api/v1/settings", json=body, headers=_bearer(token))
    _assert_not_a_crash(resp)


@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_redeem_invite_field_types(prod_client, bad):
    """Covers invites.decode_token directly (T-256)."""
    token = _registered_token(prod_client, email="redeem-types@example.com")
    resp = prod_client.post("/api/v1/invites/redeem", json={"token": bad}, headers=_bearer(token))
    _assert_not_a_crash(resp)


@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_mint_invite_field_types(prod_client, bad):
    """Covers invites.mint directly (T-256): the caller must be a member, so a list is created
    first via /sync."""
    token = _registered_token(prod_client, email="mint-types@example.com")
    resp = prod_client.post(
        "/api/v1/sync",
        json={
            "cursor": 0,
            "device_id": "dev",
            "changes": {
                "lists": [
                    {
                        "id": "list-mint-types",
                        "fields": {"name": {"value": "L", "updated_at": 100, "updated_by": "dev"}},
                    }
                ]
            },
        },
        headers=_bearer(token),
    )
    assert resp.status_code == 200

    resp = prod_client.post(
        "/api/v1/lists/list-mint-types/invites",
        json={"invited_email": bad},
        headers=_bearer(token),
    )
    _assert_not_a_crash(resp)


def _admin_prod_client(tmp_path, admin_emails=(FIELD_TYPE_ADMIN_EMAIL,)):
    from flask import Flask

    from shoppinglist_server import create_blueprint
    from shoppinglist_server import db as db_module

    database_path = str(tmp_path / "field_types_admin.db")
    conn = db_module.connect(database_path)
    db_module.init_db(conn)
    conn.close()

    flask_app = Flask(__name__)
    flask_app.config["TESTING"] = False  # see `prod_client` above
    flask_app.register_blueprint(
        create_blueprint(
            database_path=database_path,
            invite_hmac_key=b"field-types-test-key",
            base_url="http://testserver",
            admin_emails=list(admin_emails),
        )
    )
    return flask_app.test_client()


def _admin_token(client, email=FIELD_TYPE_ADMIN_EMAIL, password="password123"):
    client.post("/api/v1/register", json={"email": email, "password": password})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": password, "device_label": "dev"}
    )
    return resp.get_json()["token"]


@pytest.mark.parametrize("bad", NON_BOOL_VALUES, ids=_field_case_id)
def test_admin_set_server_settings_field_types(tmp_path, bad):
    client = _admin_prod_client(tmp_path)
    token = _admin_token(client)
    resp = client.put(
        "/api/v1/admin/server-settings",
        json={"allow_registration": bad},
        headers=_bearer(token),
    )
    _assert_not_a_crash(resp)


@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_admin_reset_password_field_types(tmp_path, bad):
    """Covers accounts._require_password (step-up) directly (T-256)."""
    client = _admin_prod_client(tmp_path)
    token = _admin_token(client)
    resp = client.post(
        "/api/v1/admin/users/some-account-id/reset-password",
        json={"password": bad},
        headers=_bearer(token),
    )
    _assert_not_a_crash(resp)


@pytest.mark.parametrize("bad", NON_STRING_VALUES, ids=_field_case_id)
def test_admin_delete_user_field_types(tmp_path, bad):
    """Covers accounts._require_password (step-up) directly (T-256)."""
    client = _admin_prod_client(tmp_path)
    token = _admin_token(client)
    resp = client.delete(
        "/api/v1/admin/users/some-account-id",
        json={"password": bad},
        headers=_bearer(token),
    )
    _assert_not_a_crash(resp)


# ---- bodies that parse but cannot be used (T-316) ---------------------------


def test_deeply_nested_json_is_422_not_500(client):
    # 50 000 brackets parse into a RecursionError, which silent=True never caught.
    body = "[" * 50_000 + "]" * 50_000
    resp = _send(client, "POST", "/api/v1/login", body)

    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_request"


@pytest.mark.parametrize(
    "body",
    [
        '{"email": "a\\ud800@example.com", "password": "password123"}',
        '{"email": "a@example.com", "password": "pass\\udfffword"}',
        '{"email": "a@example.com", "password": "password123", "\\ud800": 1}',
    ],
)
def test_a_lone_surrogate_anywhere_is_422_not_500(client, body):
    # Legal JSON, but not text: SQLite and the password hash fail on the first encode.
    resp = _send(client, "POST", "/api/v1/register", body)

    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_request"


def test_a_surrogate_pair_is_one_character_and_accepted(client):
    body = '{"email": "pair@example.com", "password": "password123\\ud83d\\ude00"}'
    assert _send(client, "POST", "/api/v1/register", body).status_code == 201
    resp = client.post(
        "/api/v1/login",
        json={"email": "pair@example.com", "password": "password123\U0001f600"},
    )
    assert resp.status_code == 200
