"""The protocol gate (T-243).

Every API request declares `X-Client-Protocol`; anything older than this server's
`PROTOCOL_VERSION` — including the missing header of every client built before 3.0.0 — is turned
away with `426 client_outdated` before authentication, before the database is opened, and before
anything is written. `GET /app-version` is the single exemption, because it is how an outdated
app finds the update that fixes the problem.

The suite's test clients send the current header by default (see conftest's `ProtocolClient`), so
a test that wants an outdated or malformed client either passes the header explicitly or takes a
client built with `protocol=None`.
"""

import logging
import os

import pytest
from flask import Flask

from shoppinglist_server import audit, create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server.protocol import PROTOCOL_HEADER, PROTOCOL_VERSION, client_is_current

PW = "password123"

# A sample across the surface — auth, account, sync, lists, invites, admin — rather than every
# route: the gate is one blueprint-wide hook, so what matters is that it is not somehow scoped to
# a subset of the routes.
SAMPLE_ROUTES = [
    ("post", "/api/v1/register"),
    ("post", "/api/v1/login"),
    ("get", "/api/v1/registration-status"),
    ("post", "/api/v1/sync"),
    ("get", "/api/v1/lists"),
    ("get", "/api/v1/settings"),
    ("get", "/api/v1/invites/pending"),
    ("post", "/api/v1/invites/redeem"),
    ("get", "/api/v1/admin/users"),
]

# Everything a client can send that is not a plain positive integer, plus the versions that are
# too old. `""` and `" "` are what a client that built the header from an empty variable sends.
REFUSED_HEADERS = ["", " ", " 3", "3 ", "abc", "3.0", "v3", "+3", "-1", "-3", "0", "1", "2", "٣"]

ACCEPTED_HEADERS = [str(PROTOCOL_VERSION), str(PROTOCOL_VERSION + 1), "99", "0003"]


def _app(tmp_path, name="protocol", init_db=True, **kwargs):
    database_path = kwargs.pop("database_path", str(tmp_path / f"{name}.db"))
    if init_db:
        conn = db_module.connect(database_path)
        db_module.init_db(conn)
        conn.close()
    app = Flask(__name__)
    app.config["TESTING"] = True
    app.register_blueprint(
        create_blueprint(
            database_path=database_path,
            invite_hmac_key=b"test-hmac-key",
            base_url=kwargs.pop("base_url", "http://testserver"),
            name=name,
            **kwargs,
        )
    )
    return app


def _outdated(app):
    """A client that declares no protocol at all — every app built before 3.0.0."""
    return app.test_client(protocol=None)


# ---- the parsing rule --------------------------------------------------------


@pytest.mark.parametrize("value", REFUSED_HEADERS)
def test_client_is_current_rejects_anything_but_a_plain_current_integer(value):
    assert client_is_current(value) is False


@pytest.mark.parametrize("value", ACCEPTED_HEADERS)
def test_client_is_current_accepts_this_version_and_newer(value):
    assert client_is_current(value) is True


def test_client_is_current_rejects_a_missing_header():
    assert client_is_current(None) is False


# ---- the gate, over a sample of routes ---------------------------------------


@pytest.mark.parametrize("method,path", SAMPLE_ROUTES)
def test_a_client_that_declares_nothing_is_refused(app, method, path):
    resp = getattr(_outdated(app), method)(path)

    assert resp.status_code == 426
    assert resp.get_json()["error"] == "client_outdated"


@pytest.mark.parametrize("method,path", SAMPLE_ROUTES)
def test_a_current_client_reaches_the_route(app, method, path):
    # Not asserting a success code: most of these need a token, a body or admin rights. What
    # matters is that the gate is out of the way and the route itself answered.
    resp = getattr(app.test_client(), method)(path)

    assert resp.status_code != 426


@pytest.mark.parametrize("value", REFUSED_HEADERS)
def test_a_malformed_or_older_protocol_is_refused(client, value):
    resp = client.get("/api/v1/lists", headers={PROTOCOL_HEADER: value})

    assert resp.status_code == 426
    assert resp.get_json()["error"] == "client_outdated"


@pytest.mark.parametrize("value", ACCEPTED_HEADERS)
def test_an_equal_or_newer_protocol_passes(client, value):
    # A client NEWER than the server is deliberately let through: an older server cannot know
    # what a newer client needs, and refusing would only break a setup that works.
    resp = client.get("/api/v1/lists", headers={PROTOCOL_HEADER: value})

    assert resp.status_code == 401  # missing_token — the route, not the gate


def test_the_refusal_is_the_standard_envelope_plus_the_servers_protocol(app):
    resp = _outdated(app).get("/api/v1/lists")

    assert resp.status_code == 426
    body = resp.get_json()
    assert set(body) == {"error", "message", "protocol"}
    assert body["error"] == "client_outdated"
    assert body["message"]
    assert body["protocol"] == PROTOCOL_VERSION
    assert resp.headers["Cache-Control"] == "no-store"


# ---- before everything else --------------------------------------------------


def test_the_gate_runs_before_authentication(app):
    client = app.test_client()
    client.post("/api/v1/register", json={"email": "alice@example.com", "password": PW})
    token = client.post(
        "/api/v1/login",
        json={"email": "alice@example.com", "password": PW, "device_label": "dev"},
    ).get_json()["token"]

    auth = {"Authorization": f"Bearer {token}"}
    # A perfectly good session, an outdated app: the protocol answer wins.
    assert _outdated(app).get("/api/v1/lists", headers=auth).status_code == 426
    # And a missing token does not turn the refusal into a 401: the gate is first.
    assert _outdated(app).get("/api/v1/lists").status_code == 426
    # The session itself is untouched — the token still works from a current client.
    assert app.test_client().get("/api/v1/lists", headers=auth).status_code == 200


def test_the_gate_opens_no_database(tmp_path):
    database_path = str(tmp_path / "never-touched.db")
    app = _app(tmp_path, name="untouched", init_db=False, database_path=database_path)

    assert (
        _outdated(app)
        .post("/api/v1/register", json={"email": "alice@example.com", "password": PW})
        .status_code
        == 426
    )
    # sqlite3.connect() creates the file on sight, so its absence proves nothing connected.
    assert not os.path.exists(database_path)


def test_a_database_path_that_cannot_be_opened_still_answers_426(tmp_path):
    # An unopenable database (its directory does not exist) makes any route that reaches the
    # database fail loudly, so a gate that connected first could not answer 426 here.
    app = _app(
        tmp_path,
        name="nodir",
        init_db=False,
        database_path=str(tmp_path / "no-such-dir" / "app.db"),
    )
    app.config["TESTING"] = False

    assert _outdated(app).get("/api/v1/lists").status_code == 426
    # The control: the same request from a current client does reach the database and fails.
    assert (
        app.test_client()
        .post("/api/v1/register", json={"email": "alice@example.com", "password": PW})
        .status_code
        == 500
    )


def test_the_refusal_writes_no_audit_record(app, caplog):
    caplog.set_level(logging.INFO, logger=audit.LOGGER_NAME)

    assert _outdated(app).get("/api/v1/lists").status_code == 426

    assert [r.getMessage() for r in caplog.records if r.name == audit.LOGGER_NAME] == []


# ---- the exemption -----------------------------------------------------------


def test_app_version_answers_an_outdated_client(app):
    resp = _outdated(app).get("/api/v1/app-version")

    assert resp.status_code == 200
    assert resp.get_json()["protocol"] == PROTOCOL_VERSION


@pytest.mark.parametrize("value", REFUSED_HEADERS)
def test_app_version_answers_whatever_the_client_declares(client, value):
    resp = client.get("/api/v1/app-version", headers={PROTOCOL_HEADER: value})

    assert resp.status_code == 200
    assert resp.get_json()["protocol"] == PROTOCOL_VERSION


def test_app_version_still_reports_the_version_and_download_url(client):
    body = client.get("/api/v1/app-version").get_json()

    assert set(body) == {"version", "download_url", "protocol"}


# ---- mount shapes ------------------------------------------------------------


def _mount_two(tmp_path):
    app = Flask(__name__)
    app.config["TESTING"] = True
    for name, prefix in (("tenant_a", "/tenant-a/api"), ("tenant_b", "/tenant-b/api")):
        database_path = str(tmp_path / f"{name}.db")
        conn = db_module.connect(database_path)
        db_module.init_db(conn)
        conn.close()
        app.register_blueprint(
            create_blueprint(
                database_path=database_path,
                invite_hmac_key=b"key",
                base_url=f"http://{name}.example.com",
                url_prefix=prefix,
                name=name,
                serve_web_client=False,
                serve_invite_landing_page=False,
                serve_android_apk=False,
            )
        )
    return app


def test_every_mounted_instance_gates_its_own_routes(tmp_path):
    app = _mount_two(tmp_path)
    outdated = _outdated(app)

    assert outdated.get("/tenant-a/api/lists").status_code == 426
    assert outdated.get("/tenant-b/api/lists").status_code == 426
    assert app.test_client().get("/tenant-a/api/lists").status_code == 401
    assert app.test_client().get("/tenant-b/api/lists").status_code == 401


def test_every_mounted_instance_exempts_its_own_app_version(tmp_path):
    app = _mount_two(tmp_path)
    outdated = _outdated(app)

    # These instances serve no APK, so the route answers 404 no_app_package — which is the
    # point: the request reached the route instead of being refused at the gate.
    for prefix in ("/tenant-a", "/tenant-b"):
        resp = outdated.get(f"{prefix}/api/app-version")
        assert resp.status_code == 404
        assert resp.get_json()["error"] == "no_app_package"


def test_a_prefix_mount_is_gated_and_exempts_its_app_version(tmp_path):
    app = _app(tmp_path, name="prefixed", base_url="http://testserver/shopping/")
    outdated = _outdated(app)

    assert outdated.get("/shopping/api/v1/lists").status_code == 426
    assert app.test_client().get("/shopping/api/v1/lists").status_code == 401
    assert outdated.get("/shopping/api/v1/app-version").status_code == 200


def test_an_explicit_url_prefix_is_gated(tmp_path):
    app = _app(tmp_path, name="elsewhere", url_prefix="/elsewhere/api")

    assert _outdated(app).get("/elsewhere/api/lists").status_code == 426
    assert app.test_client().get("/elsewhere/api/lists").status_code == 401


# ---- the site-root routes are not the API ------------------------------------


def test_the_site_root_routes_are_not_gated(app):
    # A browser has no protocol to declare: it is handed the SPA, which is always this server's
    # own build, and the invite landing page and the APK are what a brand-new user opens first.
    outdated = _outdated(app)

    assert outdated.get("/").status_code == 200
    assert outdated.get("/favicon.svg").status_code == 200
    assert outdated.get("/shoppinglist.apk").status_code == 200
    assert outdated.get("/invite/not-a-real-token").status_code not in (426,)


def test_a_co_mounted_blueprint_is_untouched(tmp_path):
    app = _app(tmp_path, name="guest_host", serve_web_client=False)
    from flask import Blueprint

    other = Blueprint("other", __name__, url_prefix="/other")

    @other.route("/thing")
    def thing():
        return "ok"

    app.register_blueprint(other)

    assert _outdated(app).get("/other/thing").status_code == 200
    assert _outdated(app).get("/api/v1/lists").status_code == 426
