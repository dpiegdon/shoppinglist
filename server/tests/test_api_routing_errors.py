"""Unknown paths, wrong methods and crashes under the API prefix answer JSON (T-316).

Before, an unknown `/api/v1/...` path fell through to the web client's catch-all and answered
index.html with 200, a wrong method answered Flask's HTML 405 without our headers, and an
unhandled exception Flask's HTML 500. All three are now the JSON envelope, and — T-250 — only for
this blueprint's own prefix: a co-mounted service's routing errors and crashes stay its own.
"""

import pytest
from flask import Blueprint, Flask

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module


def _app(tmp_path, serve_web_client=True, **kwargs):
    database_path = str(tmp_path / "routing.db")
    conn = db_module.connect(database_path)
    db_module.init_db(conn)
    conn.close()
    app = Flask(__name__)
    app.config["TESTING"] = True
    app.register_blueprint(
        create_blueprint(
            database_path=database_path,
            invite_hmac_key=b"test-hmac-key",
            base_url="http://testserver",
            serve_web_client=serve_web_client,
            **kwargs,
        )
    )
    return app


def _assert_ours(resp):
    assert resp.headers["X-Content-Type-Options"] == "nosniff"
    assert resp.headers["Cache-Control"] == "no-store"
    assert resp.mimetype == "application/json"


@pytest.mark.parametrize("serve_web_client", [True, False])
@pytest.mark.parametrize(
    "method,path",
    [
        ("GET", "/api/v1/no-such-endpoint"),
        ("POST", "/api/v1/lists/abc/no-such-action"),
        ("GET", "/api/v1/"),
        ("GET", "/api/v1"),
        ("DELETE", "/api/v1/nothing/here/at/all"),
        ("OPTIONS", "/api/v1/no-such-endpoint"),
    ],
)
def test_an_unknown_api_path_is_a_json_404(tmp_path, serve_web_client, method, path):
    client = _app(tmp_path, serve_web_client=serve_web_client).test_client()

    resp = client.open(path, method=method)

    assert resp.status_code == 404
    assert resp.get_json()["error"] == "not_found"
    _assert_ours(resp)


@pytest.mark.parametrize(
    "method,path,allow",
    [
        ("GET", "/api/v1/sync", "OPTIONS, POST"),
        ("DELETE", "/api/v1/login", "OPTIONS, POST"),
        ("PUT", "/api/v1/lists/abc/members", "GET, HEAD, OPTIONS"),
        ("POST", "/api/v1/admin/server-settings", "GET, HEAD, OPTIONS, PUT"),
    ],
)
def test_a_wrong_method_is_a_json_405_naming_the_right_ones(tmp_path, method, path, allow):
    client = _app(tmp_path).test_client()

    resp = client.open(path, method=method)

    assert resp.status_code == 405
    assert resp.get_json()["error"] == "method_not_allowed"
    assert resp.headers["Allow"] == allow
    _assert_ours(resp)


def test_options_on_a_real_route_lists_only_its_own_methods(tmp_path):
    client = _app(tmp_path).test_client()

    resp = client.options("/api/v1/sync")

    assert resp.status_code == 200
    assert resp.headers["Allow"] == "OPTIONS, POST"


def test_real_routes_still_win(tmp_path):
    client = _app(tmp_path).test_client()

    assert client.get("/api/v1/registration-status").status_code == 200
    assert client.post("/api/v1/sync", json={"cursor": 0}).get_json()["error"] == "missing_token"
    # And the web client keeps everything outside the prefix.
    assert client.get("/some/client/route").mimetype == "text/html"


def test_an_unknown_api_path_is_behind_the_protocol_gate_like_any_api_request(tmp_path):
    client = _app(tmp_path).test_client(protocol=None)

    assert client.get("/api/v1/no-such-endpoint").status_code == 426


def test_a_custom_prefix_gets_its_own_404(tmp_path):
    client = _app(tmp_path, url_prefix="/elsewhere/api").test_client()

    resp = client.get("/elsewhere/api/nope")
    assert resp.status_code == 404
    assert resp.get_json()["error"] == "not_found"
    # The default prefix is not ours here: it is the web client's.
    assert client.get("/api/v1/nope").mimetype == "text/html"


def test_a_co_mounted_service_keeps_its_own_routing_errors(tmp_path):
    app = _app(tmp_path, serve_web_client=False)
    other = Blueprint("other", __name__, url_prefix="/other")

    @other.route("/thing", methods=["POST"])
    def thing():
        return "ok"

    app.register_blueprint(other)
    client = app.test_client()

    missing = client.get("/other/nope")
    assert missing.status_code == 404
    assert missing.mimetype == "text/html"
    wrong = client.get("/other/thing")
    assert wrong.status_code == 405
    assert wrong.mimetype == "text/html"


# ---- an unhandled exception is the JSON envelope, and only ours ------------------------------


def test_an_unhandled_exception_answers_the_json_envelope_without_details(tmp_path, monkeypatch):
    from shoppinglist_server.routes import auth as auth_routes

    def broken(*args, **kwargs):
        raise RuntimeError("secret internals /var/lib/db")

    monkeypatch.setattr(auth_routes, "auth_register", broken)
    app = _app(tmp_path)
    # With TESTING on, Flask re-raises into the test instead of answering as production does.
    app.config["TESTING"] = False

    resp = app.test_client().post(
        "/api/v1/register", json={"email": "x@example.com", "password": "password123"}
    )

    assert resp.status_code == 500
    assert set(resp.get_json()) == {"error", "message"}
    assert resp.get_json()["error"] == "internal_error"
    assert "secret" not in resp.get_data(as_text=True)
    _assert_ours(resp)


def test_a_co_mounted_service_keeps_its_own_500(tmp_path):
    app = _app(tmp_path, serve_web_client=False)
    app.config["TESTING"] = False
    other = Blueprint("other", __name__, url_prefix="/other")

    @other.route("/crash")
    def crash():
        raise RuntimeError("theirs")

    app.register_blueprint(other)

    resp = app.test_client().get("/other/crash")
    assert resp.status_code == 500
    assert resp.mimetype == "text/html"
