"""The app-version endpoint the Android client checks for updates (T-135)."""

from importlib.metadata import PackageNotFoundError, version

from flask import Flask

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server.protocol import PROTOCOL_VERSION


def _app(tmp_path, name="noapk", **kwargs):
    database_path = str(tmp_path / f"{name}.db")
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
            **kwargs,
        )
    )
    return app


def test_reports_the_package_version_and_an_absolute_apk_url(client):
    resp = client.get("/api/v1/app-version")

    assert resp.status_code == 200
    body = resp.get_json()
    # The package version IS the embedded APK's version — one artifact, one version.
    assert body["version"] == version("shoppinglist-server")
    assert body["download_url"] == "http://testserver/shoppinglist.apk"


def test_the_advertised_url_is_the_one_that_actually_serves_the_apk(client):
    download_url = client.get("/api/v1/app-version").get_json()["download_url"]

    # Follow the advertised URL rather than asserting its shape twice: this is the property
    # that matters, and it would catch a base_url/root_path mismatch that a string compare
    # against a hardcoded expectation would happily agree with.
    resp = client.get(download_url)

    assert resp.status_code == 200
    assert resp.data[:2] == b"PK"


def test_needs_no_token(client):
    # No Authorization header anywhere in this module; assert it explicitly so the property
    # isn't silently lost. Checking for an update is not an account operation.
    assert client.get("/api/v1/app-version").status_code == 200


def test_response_is_no_store_like_the_rest_of_the_api(client):
    resp = client.get("/api/v1/app-version")

    assert resp.headers["Cache-Control"] == "no-store"


def test_404_when_this_instance_serves_no_apk(tmp_path):
    client = _app(tmp_path, serve_android_apk=False).test_client()

    resp = client.get("/api/v1/app-version")

    assert resp.status_code == 404
    assert resp.get_json()["error"] == "no_app_package"


def test_the_no_apk_404_still_answers_the_protocol(tmp_path):
    client = _app(tmp_path, serve_android_apk=False).test_client()

    body = client.get("/api/v1/app-version").get_json()

    # The client asks for the protocol before signing in. Without it here, a server that
    # simply carries no APK would look like one from before this endpoint and be refused
    # (T-297). The canonical keys come first; protocol is additive.
    assert body["protocol"] == PROTOCOL_VERSION
    assert list(body)[:2] == ["error", "message"]


def test_the_source_checkout_404_still_answers_the_protocol(client, monkeypatch):
    from shoppinglist_server.routes import app_version as app_version_module

    def _not_installed(_name):
        raise PackageNotFoundError(_name)

    monkeypatch.setattr(app_version_module, "version", _not_installed)

    resp = client.get("/api/v1/app-version")

    assert resp.status_code == 404
    body = resp.get_json()
    assert body["error"] == "no_app_package"
    assert body["protocol"] == PROTOCOL_VERSION


def test_the_200_answers_the_protocol_too(client):
    assert client.get("/api/v1/app-version").get_json()["protocol"] == PROTOCOL_VERSION


def test_absolute_url_follows_a_subpath_mount(tmp_path):
    client = _app(tmp_path, name="prefixed", base_url="http://testserver/shopping/").test_client()

    body = client.get("/shopping/api/v1/app-version").get_json()

    assert body["download_url"] == "http://testserver/shopping/shoppinglist.apk"
    assert client.get("/shopping/shoppinglist.apk").status_code == 200
