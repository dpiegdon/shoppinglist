"""Hardening from T-45 and T-250: request size cap and security headers, both scoped to our own
routes so a co-mounted blueprint is left alone."""

import pytest

MIB = 1024 * 1024


def _host_app(tmp_path, *, host_limit=None, name="shoppinglist_server", prefix=None, **kwargs):
    """A host app serving OUR blueprint plus an unrelated one (T-250).

    The neighbour has three routes: one that reads whatever body it is sent, one that raises the
    sqlite "locked" error our 503 handler is written for, and one that trips Werkzeug's own body
    limit — everything our blueprint must leave exactly as the neighbour wrote it.
    """
    import sqlite3

    from flask import Blueprint, Flask, request

    from shoppinglist_server import create_blueprint
    from shoppinglist_server import db as db_module

    db_path = str(tmp_path / f"{name}.db")
    conn = db_module.connect(db_path)
    db_module.init_db(conn)
    conn.close()

    app = Flask(__name__)
    app.config["TESTING"] = True
    if host_limit is not None:
        app.config["MAX_CONTENT_LENGTH"] = host_limit
    app.register_blueprint(
        create_blueprint(
            database_path=db_path,
            invite_hmac_key=b"k",
            base_url="http://host.example.com",
            name=name,
            url_prefix=prefix,
            serve_web_client=False,
            serve_invite_landing_page=False,
            serve_android_apk=False,
            **kwargs,
        )
    )

    other = Blueprint("other_service", __name__)

    @other.route("/other/read", methods=["POST"])
    def other_read():
        return {"bytes": len(request.get_data())}

    @other.route("/other/locked")
    def other_locked():
        raise sqlite3.OperationalError("database is locked")

    @other.route("/other/small", methods=["POST"])
    def other_small():
        request.max_content_length = 1024  # the neighbour's own, stricter, limit
        return {"bytes": len(request.get_data())}

    app.register_blueprint(other)
    return app


def test_mounting_the_blueprint_does_not_write_the_hosts_body_limit(tmp_path):
    # The bug (T-250): _record set app.config["MAX_CONTENT_LENGTH"] to 4 MiB, capping every
    # other service on the app.
    app = _host_app(tmp_path)

    assert app.config["MAX_CONTENT_LENGTH"] is None


def test_a_hosts_own_body_limit_is_left_exactly_as_it_was(tmp_path):
    app = _host_app(tmp_path, host_limit=50 * MIB)

    assert app.config["MAX_CONTENT_LENGTH"] == 50 * MIB


def test_a_co_mounted_service_still_takes_a_10_mib_upload_while_our_api_refuses_5_mib(tmp_path):
    client = _host_app(tmp_path).test_client()

    neighbour = client.post("/other/read", data=b"x" * (10 * MIB))
    ours = client.post("/api/v1/login", data=b"x" * (5 * MIB), content_type="application/json")

    assert neighbour.status_code == 200
    assert neighbour.get_json() == {"bytes": 10 * MIB}
    assert ours.status_code == 413
    assert ours.get_json()["error"] == "payload_too_large"


def test_our_api_takes_a_body_just_under_its_limit_and_refuses_one_just_over(tmp_path):
    client = _host_app(tmp_path).test_client()

    def login(size):
        # Padded JSON: the body's size is what is under test, not what it says.
        body = b'{"email": "a@example.com", "password": "x", "pad": "' + b"p" * size + b'"}'
        return client.post("/api/v1/login", data=body, content_type="application/json")

    assert login(4 * MIB - 200).status_code == 401  # read, parsed, wrong credentials
    assert login(4 * MIB).status_code == 413


def test_a_generous_host_limit_does_not_loosen_ours(tmp_path):
    # The host raised its own cap for uploads; our API is not thereby unbounded.
    client = _host_app(tmp_path, host_limit=100 * MIB).test_client()

    resp = client.post("/api/v1/login", data=b"x" * (5 * MIB), content_type="application/json")

    assert resp.status_code == 413
    assert resp.get_json()["error"] == "payload_too_large"


def test_max_content_length_is_ours_to_configure_per_instance(tmp_path):
    client = _host_app(tmp_path, max_content_length=50).test_client()

    resp = client.post(
        "/api/v1/login",
        json={"email": "a" * 200, "password": "x", "device_label": "d"},
    )

    assert resp.status_code == 413
    assert resp.get_json()["error"] == "payload_too_large"


def test_two_instances_each_enforce_their_own_limit(tmp_path):
    from flask import Flask

    from shoppinglist_server import create_blueprint
    from shoppinglist_server import db as db_module

    app = Flask(__name__)
    app.config["TESTING"] = True
    for name, prefix, limit in [("small", "/small/api", 200), ("big", "/big/api", 1 * MIB)]:
        db_path = str(tmp_path / f"{name}.db")
        conn = db_module.connect(db_path)
        db_module.init_db(conn)
        conn.close()
        app.register_blueprint(
            create_blueprint(
                database_path=db_path,
                invite_hmac_key=b"k",
                base_url="http://host.example.com",
                name=name,
                url_prefix=prefix,
                serve_web_client=False,
                serve_invite_landing_page=False,
                serve_android_apk=False,
                max_content_length=limit,
            )
        )
    client = app.test_client()
    body = {"email": "a@example.com", "password": "x" * 300}

    assert client.post("/small/api/login", json=body).status_code == 413
    assert client.post("/big/api/login", json=body).status_code == 401


@pytest.mark.parametrize("bad", [0, -1, 4.5, "4194304", None, True])
def test_a_body_limit_that_is_not_a_positive_integer_is_refused_at_mount_time(tmp_path, bad):
    with pytest.raises((TypeError, ValueError)):
        _host_app(tmp_path, max_content_length=bad)


def test_a_co_mounted_services_database_error_is_not_turned_into_our_503(tmp_path):
    # Our handler answers "database is locked" with 503 server_busy — for OUR database. Registered
    # app-wide it also swallowed a neighbour's, which then never saw its own error (T-250).
    import sqlite3

    client = _host_app(tmp_path).test_client()

    with pytest.raises(sqlite3.OperationalError):
        client.get("/other/locked")


def test_a_co_mounted_services_own_413_is_not_rewritten_into_our_envelope(tmp_path):
    resp = _host_app(tmp_path).test_client().post("/other/small", data=b"x" * 2048)

    assert resp.status_code == 413
    # Werkzeug's stock page, not our JSON envelope.
    assert resp.mimetype != "application/json"
    assert b"payload_too_large" not in resp.data


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


# ---- no-store on everything except the routes that chose their own caching (T-119) ----


def _authed(client):
    client.post("/api/v1/register", json={"email": "cache@example.com", "password": "password123"})
    token = client.post(
        "/api/v1/login",
        json={"email": "cache@example.com", "password": "password123", "device_label": "d"},
    ).get_json()["token"]
    return {"Authorization": f"Bearer {token}"}


def test_authenticated_json_is_never_stored_by_a_shared_cache(client):
    headers = _authed(client)

    for path in ("/api/v1/settings", "/api/v1/lists", "/api/v1/account/sessions"):
        resp = client.get(path, headers=headers)
        assert resp.status_code == 200, path
        assert resp.headers["Cache-Control"] == "no-store", path


def test_the_login_response_carrying_a_bearer_token_is_not_cacheable(client):
    client.post("/api/v1/register", json={"email": "tok@example.com", "password": "password123"})

    resp = client.post(
        "/api/v1/login",
        json={"email": "tok@example.com", "password": "password123", "device_label": "d"},
    )

    assert "token" in resp.get_json()
    assert resp.headers["Cache-Control"] == "no-store"


def test_the_invite_landing_page_carrying_a_token_in_its_url_is_not_cacheable(client):
    resp = client.get("/invite/not-a-real-token")

    assert resp.headers["Cache-Control"] == "no-store"


def test_no_store_does_not_clobber_a_route_that_chose_its_own_caching(client):
    # The content-hashed SPA assets are deliberately immutable-cacheable, and the SPA index
    # deliberately must-revalidate. Blanket no-store would silently undo both.
    index = client.get("/")
    assert index.status_code == 200
    assert "no-store" not in index.headers["Cache-Control"]
    assert "no-cache" in index.headers["Cache-Control"]

    asset_url = None
    for line in index.get_data(as_text=True).splitlines():
        if "/assets/" in line and ".js" in line:
            asset_url = line.split('src="', 1)[1].split('"', 1)[0]
            break
    assert asset_url, "no hashed asset found in the served index.html"

    asset = client.get(asset_url)
    assert asset.status_code == 200
    assert "max-age=31536000" in asset.headers["Cache-Control"]
    assert "no-store" not in asset.headers["Cache-Control"]


# ---- write contention answers 503, not 500 (T-114) ---------------------------


def test_a_locked_database_answers_503_with_retry_after_not_500(app, client, monkeypatch):
    import sqlite3

    from shoppinglist_server.routes import auth as auth_routes

    def locked(*args, **kwargs):
        raise sqlite3.OperationalError("database is locked")

    # The route module binds `register` at import time, so patch it there, not on the source module.
    monkeypatch.setattr(auth_routes, "auth_register", locked)

    resp = client.post(
        "/api/v1/register", json={"email": "x@example.com", "password": "password123"}
    )

    assert resp.status_code == 503
    assert resp.get_json()["error"] == "server_busy"
    assert resp.headers["Retry-After"] == "2"


def test_a_genuine_operational_error_is_not_disguised_as_congestion(app, client, monkeypatch):
    """Only contention is remapped — a real fault must still fail loudly rather than telling the
    client to retry something that will never succeed."""
    import sqlite3

    from shoppinglist_server.routes import auth as auth_routes

    def broken(*args, **kwargs):
        raise sqlite3.OperationalError("no such table: accounts")

    monkeypatch.setattr(auth_routes, "auth_register", broken)
    # PROPAGATE_EXCEPTIONS follows TESTING when unset; leave it on and the re-raise escapes the
    # test client instead of becoming the 500 a real deployment would return.
    app.config["TESTING"] = False

    resp = client.post(
        "/api/v1/register", json={"email": "x@example.com", "password": "password123"}
    )

    assert resp.status_code == 500


# ---- nothing else of ours is visible to, or shared with, the host (T-250) ------------------------


def _hosted_with_landing(tmp_path, template_dir=None):
    """A host app with its OWN `invite.html` (a generic name a host may well use) and our landing
    page switched on, so both templates are in play at once."""
    from flask import Flask, g, render_template

    from shoppinglist_server import create_blueprint
    from shoppinglist_server import db as db_module

    db_path = str(tmp_path / "landing.db")
    conn = db_module.connect(db_path)
    db_module.init_db(conn)
    conn.close()

    app = Flask(__name__, template_folder=str(template_dir) if template_dir else None)
    app.config["TESTING"] = True
    seen = []

    @app.before_request
    def host_auth():
        # The host's own authentication, on the same `flask.g` our request runs against.
        g.account = "host-user"

    @app.after_request
    def host_audit(response):
        seen.append(getattr(g, "account", "<gone>"))
        return response

    @app.route("/host/invite")
    def host_invite():
        return render_template("invite.html")

    app.register_blueprint(
        create_blueprint(
            database_path=db_path,
            invite_hmac_key=b"k",
            base_url="http://host.example.com",
            serve_web_client=False,
            serve_android_apk=False,
        )
    )
    return app, seen


def test_our_landing_template_does_not_collide_with_a_hosts_own_invite_html(tmp_path):
    templates = tmp_path / "host_templates"
    templates.mkdir()
    (templates / "invite.html").write_text("HOST INVITE PAGE")
    app, _ = _hosted_with_landing(tmp_path, templates)
    client = app.test_client()

    # The host's page is the host's, and our landing page is ours: an unknown token is our
    # "not found" page, not the host's template.
    assert client.get("/host/invite").data == b"HOST INVITE PAGE"
    ours = client.get("/invite/not-a-token")
    assert ours.status_code == 404
    assert b"HOST INVITE PAGE" not in ours.data
    assert b"Invite not found" in ours.data


def test_our_template_is_not_reachable_through_a_generic_name(tmp_path):
    from jinja2 import TemplateNotFound

    app, _ = _hosted_with_landing(tmp_path)  # the host has no invite.html of its own

    # Before the fix a host's `render_template("invite.html")` quietly rendered OUR page.
    with pytest.raises(TemplateNotFound):
        app.test_client().get("/host/invite")


def test_our_authentication_does_not_overwrite_the_hosts_flask_g(tmp_path):
    app, seen = _hosted_with_landing(tmp_path)
    client = app.test_client()
    from shoppinglist_server.protocol import PROTOCOL_HEADER, PROTOCOL_VERSION

    headers = {PROTOCOL_HEADER: str(PROTOCOL_VERSION)}
    client.post(
        "/api/v1/register",
        json={"email": "a@example.com", "password": "password123"},
        headers=headers,
    )
    token = client.post(
        "/api/v1/login",
        json={"email": "a@example.com", "password": "password123", "device_label": "t"},
        headers=headers,
    ).get_json()["token"]
    seen.clear()

    resp = client.get("/api/v1/settings", headers={**headers, "Authorization": f"Bearer {token}"})

    assert resp.status_code == 200
    # We authenticated the request as ourselves, and the host's `g.account` is still the host's.
    assert seen == ["host-user"]


def test_a_hosts_g_account_cannot_break_our_error_path(tmp_path):
    app, _ = _hosted_with_landing(tmp_path)
    from shoppinglist_server.protocol import PROTOCOL_HEADER, PROTOCOL_VERSION

    # Unauthenticated, so our 401 handler runs while the host's `g.account` (a plain string with no
    # `.id`) is set: it used to read the host's value as ours and crash the error handler into a 500.
    resp = app.test_client().get(
        "/api/v1/settings", headers={PROTOCOL_HEADER: str(PROTOCOL_VERSION)}
    )

    assert resp.status_code == 401
    assert resp.get_json()["error"] == "missing_token"


def test_our_database_teardown_is_scoped_to_our_blueprint(tmp_path):
    app, _ = _hosted_with_landing(tmp_path)

    app_wide = [f.__name__ for f in app.teardown_request_funcs.get(None, [])]
    ours = [f.__name__ for f in app.teardown_request_funcs.get("shoppinglist_server", [])]

    assert "_close_db" not in app_wide
    assert "_close_db" in ours
