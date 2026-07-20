"""App-level hardening from T-45: request size cap and security headers."""


def test_max_content_length_default_is_applied(app):
    assert app.config["MAX_CONTENT_LENGTH"] == 4 * 1024 * 1024


def test_oversized_request_body_returns_413_json(app, client):
    # Shrink the cap rather than send megabytes; the enforcement path is the same.
    app.config["MAX_CONTENT_LENGTH"] = 50

    resp = client.post(
        "/api/v1/login",
        json={"email": "a" * 200, "password": "x", "device_label": "d"},
    )

    assert resp.status_code == 413
    assert resp.get_json()["error"] == "payload_too_large"


def test_html_routes_carry_full_security_headers(client):
    # The embedded SPA index is text/html.
    resp = client.get("/")

    assert resp.status_code == 200
    assert "text/html" in resp.headers["Content-Type"]
    assert resp.headers["X-Content-Type-Options"] == "nosniff"
    assert resp.headers["Referrer-Policy"] == "no-referrer"
    assert resp.headers["X-Frame-Options"] == "DENY"
    assert "default-src 'self'" in resp.headers["Content-Security-Policy"]
    assert "frame-ancestors 'none'" in resp.headers["Content-Security-Policy"]


def test_invite_landing_sends_no_referrer_so_the_token_cannot_leak(client):
    # Any /invite/<token> response is HTML; an invalid token still exercises the headers path.
    resp = client.get("/invite/not-a-real-token")

    assert "text/html" in resp.headers["Content-Type"]
    assert resp.headers["Referrer-Policy"] == "no-referrer"
    assert "Content-Security-Policy" in resp.headers


def test_json_api_responses_carry_baseline_headers_but_no_csp(client):
    # A JSON error response: baseline headers yes, HTML-only CSP/X-Frame-Options no.
    resp = client.post("/api/v1/login", json={"email": "nobody@example.com", "password": "wrong"})

    assert resp.status_code == 401
    assert "application/json" in resp.headers["Content-Type"]
    assert resp.headers["X-Content-Type-Options"] == "nosniff"
    assert resp.headers["Referrer-Policy"] == "no-referrer"
    assert "Content-Security-Policy" not in resp.headers
    assert "X-Frame-Options" not in resp.headers


# ---- headers stay scoped to routes we own (T-106) ----------------------------


def _app_hosting_our_blueprint_and_a_foreign_one(tmp_path):
    """One host app with our blueprint mounted alongside an unrelated blueprint
    that owns its own HTML route — the exact situation where app-wide headers
    would leak onto someone else's routes."""
    from flask import Blueprint, Flask

    from shoppinglist_server import create_blueprint
    from shoppinglist_server import db as db_module

    db_path = str(tmp_path / "scoped.db")
    conn = db_module.connect(db_path)
    db_module.init_db(conn)
    conn.close()

    app = Flask(__name__)
    app.config["TESTING"] = True
    app.register_blueprint(
        create_blueprint(
            database_path=db_path,
            invite_hmac_key=b"k",
            base_url="http://host.example.com",
            # This co-mounted host serves its own root content, so our site-root
            # HTML routes are off — the API blueprint is all we contribute here.
            serve_web_client=False,
            serve_invite_landing_page=False,
            serve_android_apk=False,
        )
    )

    other = Blueprint("other_tenant", __name__)

    @other.route("/other/page")
    def other_page():
        # A neighbor's HTML page that legitimately uses an inline script and sets
        # no CSP of its own — our default-src 'self' would break it if it leaked.
        return "<html><body><script>1</script></body></html>", 200, {"Content-Type": "text/html"}

    @other.route("/other/upload", methods=["POST"])
    def other_upload():
        return "", 204

    app.register_blueprint(other)
    return app


def test_our_headers_do_not_leak_onto_a_co_mounted_blueprints_html(tmp_path):
    client = _app_hosting_our_blueprint_and_a_foreign_one(tmp_path).test_client()

    resp = client.get("/other/page")

    assert resp.status_code == 200
    # None of our policy may land on a route we don't own — CSP would block the
    # neighbor's inline <script>, and no-referrer would send Origin: null on its
    # form POSTs and trip an Origin-checking CSRF guard.
    assert "Content-Security-Policy" not in resp.headers
    assert "X-Frame-Options" not in resp.headers
    assert "Referrer-Policy" not in resp.headers
    assert "X-Content-Type-Options" not in resp.headers


def test_our_headers_do_not_leak_onto_a_co_mounted_blueprints_post(tmp_path):
    client = _app_hosting_our_blueprint_and_a_foreign_one(tmp_path).test_client()

    resp = client.post("/other/upload")

    assert resp.status_code == 204
    assert "Referrer-Policy" not in resp.headers


def test_our_api_still_gets_headers_even_when_co_mounted(tmp_path):
    # The flip side: scoping must not stop headers reaching our OWN routes.
    client = _app_hosting_our_blueprint_and_a_foreign_one(tmp_path).test_client()

    resp = client.post("/api/v1/login", json={"email": "x@example.com", "password": "wrong"})

    assert resp.status_code == 401
    assert resp.headers["X-Content-Type-Options"] == "nosniff"
    assert resp.headers["Referrer-Policy"] == "no-referrer"


def test_a_host_route_the_extension_does_not_own_is_left_alone(tmp_path):
    # Not a blueprint at all — a plain route on the host app. Still not ours.
    app = _app_hosting_our_blueprint_and_a_foreign_one(tmp_path)

    @app.route("/host-root")
    def host_root():
        return "<html><body>host</body></html>", 200, {"Content-Type": "text/html"}

    resp = app.test_client().get("/host-root")

    assert resp.status_code == 200
    assert "Content-Security-Policy" not in resp.headers
    assert "Referrer-Policy" not in resp.headers
