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
