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
