"""The embedded Android APK download (T-59)."""

from flask import Flask

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module


def _app_without_apk(tmp_path):
    database_path = str(tmp_path / "noapk.db")
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
            serve_android_apk=False,
        )
    )
    return app


def test_apk_downloads_with_android_mimetype(client):
    resp = client.get("/shoppinglist.apk")

    assert resp.status_code == 200
    assert resp.content_type == "application/vnd.android.package-archive"
    assert "attachment" in resp.headers["Content-Disposition"]
    # A real APK, not an error page: ZIP magic.
    assert resp.data[:2] == b"PK"


def test_apk_route_ranks_above_the_spa_catch_all(client):
    # The SPA catch-all serves index.html for unmatched GETs; the APK must not fall through to it.
    resp = client.get("/shoppinglist.apk")
    assert not resp.content_type.startswith("text/html")


def test_disabled_apk_serving_falls_through_to_the_spa(tmp_path):
    client = _app_without_apk(tmp_path).test_client()

    resp = client.get("/shoppinglist.apk")

    # No APK route -> the web client's catch-all answers (SPA route resolution), not a download.
    assert resp.content_type.startswith("text/html")
