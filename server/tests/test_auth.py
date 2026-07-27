import hashlib

import pytest

from shoppinglist_server import auth
from shoppinglist_server.errors import ApiError

EMAIL = "alice@example.com"
PASSWORD = "correct horse battery staple"
DEVICE = "test-device"


# ---- service layer -------------------------------------------------------


def test_register_creates_account_and_default_settings(db_conn):
    account_id = auth.register(db_conn, EMAIL, PASSWORD)

    account_row = db_conn.execute(
        "SELECT id, email, password_hash FROM accounts WHERE id = ?", (account_id,)
    ).fetchone()
    assert account_row is not None
    assert account_row["email"] == EMAIL
    assert account_row["password_hash"] != PASSWORD  # never stored plaintext

    settings_row = db_conn.execute(
        "SELECT default_currency FROM account_settings WHERE account_id = ?",
        (account_id,),
    ).fetchone()
    assert settings_row["default_currency"] == "EUR"


def test_register_duplicate_email_case_insensitive_raises_409(db_conn):
    auth.register(db_conn, EMAIL, PASSWORD)

    with pytest.raises(ApiError) as excinfo:
        auth.register(db_conn, "ALICE@example.com", PASSWORD)
    assert excinfo.value.status == 409


def test_login_returns_token_and_account_id(db_conn):
    account_id = auth.register(db_conn, EMAIL, PASSWORD)

    token, logged_in_account_id = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    assert logged_in_account_id == account_id
    assert isinstance(token, str)
    assert len(token) >= 32


def test_login_wrong_password_raises_401(db_conn):
    auth.register(db_conn, EMAIL, PASSWORD)

    with pytest.raises(ApiError) as excinfo:
        auth.login(db_conn, EMAIL, "wrong password", DEVICE)
    assert excinfo.value.status == 401


def test_login_unknown_email_raises_401(db_conn):
    with pytest.raises(ApiError) as excinfo:
        auth.login(db_conn, "nobody@example.com", PASSWORD, DEVICE)
    assert excinfo.value.status == 401


def test_token_is_stored_as_hash_not_plaintext(db_conn):
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    row = db_conn.execute("SELECT token_hash FROM auth_tokens").fetchone()
    assert row["token_hash"] != token
    assert row["token_hash"] == hashlib.sha256(token.encode("utf-8")).hexdigest()


def test_require_account_missing_header_raises_401(app, db_conn):
    with app.test_request_context("/", headers={}):
        from flask import request

        with pytest.raises(ApiError) as excinfo:
            auth.require_account(db_conn, request)
    assert excinfo.value.status == 401


def test_require_account_invalid_token_raises_401(app, db_conn):
    with app.test_request_context("/", headers={"Authorization": "Bearer not-a-real-token"}):
        from flask import request

        with pytest.raises(ApiError) as excinfo:
            auth.require_account(db_conn, request)
    assert excinfo.value.status == 401


def test_require_account_valid_token_returns_account(app, db_conn):
    account_id = auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    with app.test_request_context("/", headers={"Authorization": f"Bearer {token}"}):
        from flask import request

        account = auth.require_account(db_conn, request)

    assert account.id == account_id
    assert account.email == EMAIL


def _last_seen_at(db_conn, token):
    row = db_conn.execute(
        "SELECT last_seen_at FROM auth_tokens WHERE token_hash = ?",
        (auth.hash_token(token),),
    ).fetchone()
    return row["last_seen_at"]


def test_require_account_first_request_after_login_keeps_sane_last_seen_at(
    app, db_conn, monkeypatch
):
    login_time = 1_000_000
    monkeypatch.setattr(auth, "now_ms", lambda: login_time)
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    with app.test_request_context("/", headers={"Authorization": f"Bearer {token}"}):
        from flask import request

        auth.require_account(db_conn, request)

    # Login already stamped a fresh last_seen_at; the very next request (still
    # within the staleness window) should leave it exactly as login set it —
    # sane, not null, not clobbered.
    assert _last_seen_at(db_conn, token) == login_time


def test_require_account_within_threshold_does_not_update_last_seen_at(
    app, db_conn, monkeypatch
):
    login_time = 1_000_000
    monkeypatch.setattr(auth, "now_ms", lambda: login_time)
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    just_under_threshold = login_time + auth.LAST_SEEN_REFRESH_MS - 1
    monkeypatch.setattr(auth, "now_ms", lambda: just_under_threshold)
    with app.test_request_context("/", headers={"Authorization": f"Bearer {token}"}):
        from flask import request

        auth.require_account(db_conn, request)

    assert _last_seen_at(db_conn, token) == login_time


def test_require_account_after_threshold_updates_last_seen_at(app, db_conn, monkeypatch):
    login_time = 1_000_000
    monkeypatch.setattr(auth, "now_ms", lambda: login_time)
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE)

    past_threshold = login_time + auth.LAST_SEEN_REFRESH_MS + 1
    monkeypatch.setattr(auth, "now_ms", lambda: past_threshold)
    with app.test_request_context("/", headers={"Authorization": f"Bearer {token}"}):
        from flask import request

        auth.require_account(db_conn, request)

    assert _last_seen_at(db_conn, token) == past_threshold


# ---- HTTP layer ------------------------------------------------------------


def test_register_login_logout_http_flow(client):
    resp = client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    assert resp.status_code == 201
    account_id = resp.get_json()["account_id"]

    resp = client.post(
        "/api/v1/login",
        json={"email": EMAIL, "password": PASSWORD, "device_label": DEVICE},
    )
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["account_id"] == account_id
    assert body["email"] == EMAIL
    token = body["token"]

    # Happy-path authed request.
    resp = client.post("/api/v1/logout", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 204

    # Revoked token no longer authenticates.
    resp = client.post("/api/v1/logout", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 401


def test_register_duplicate_email_http_409(client):
    client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    resp = client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    assert resp.status_code == 409
    assert resp.get_json()["error"]


def test_login_wrong_password_http_401(client):
    client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    resp = client.post(
        "/api/v1/login",
        json={"email": EMAIL, "password": "wrong password", "device_label": DEVICE},
    )
    assert resp.status_code == 401


def _closed_registration_app(tmp_path):
    from flask import Flask

    from shoppinglist_server import create_blueprint
    from shoppinglist_server import db as db_module

    database_path = str(tmp_path / "closed.db")
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
            allow_registration=False,
        )
    )
    return app


def test_allow_registration_false_rejects_register_with_403(tmp_path):
    client = _closed_registration_app(tmp_path).test_client()

    resp = client.post(
        "/api/v1/register", json={"email": "new@example.com", "password": "password123"}
    )

    assert resp.status_code == 403
    body = resp.get_json()
    assert body["error"] == "registration_disabled"


def test_allow_registration_false_still_allows_login(tmp_path, monkeypatch):
    app = _closed_registration_app(tmp_path)
    client = app.test_client()

    # Seed an account directly (registration is closed), then log in normally.
    from shoppinglist_server import auth as auth_module
    from shoppinglist_server import get_config_by_name
    from shoppinglist_server import db as db_module

    config = get_config_by_name(app)
    conn = db_module.connect(config["database_path"])
    auth_module.register(conn, "existing@example.com", "password123")
    conn.close()

    resp = client.post(
        "/api/v1/login",
        json={"email": "existing@example.com", "password": "password123", "device_label": "dev"},
    )
    assert resp.status_code == 200


def test_allow_registration_false_is_advertised_to_the_web_client(tmp_path):
    client = _closed_registration_app(tmp_path).test_client()

    body = client.get("/").get_data(as_text=True)

    # Carried as a meta tag, not an inline script: the security CSP blocks inline
    # scripts in a real browser (see routes/webapp.py).
    assert '<meta name="app-allow-registration" content="false">' in body


# ---- session idle expiry (T-104) -----------------------------------------

DAY_MS = 24 * 60 * 60 * 1000


def _ttl_of(conn, token):
    return conn.execute(
        "SELECT idle_ttl_ms FROM auth_tokens WHERE token_hash = ?",
        (auth.hash_token(token),),
    ).fetchone()["idle_ttl_ms"]


def _age_session(conn, token, ms):
    """Backdate a session's last_seen_at to simulate `ms` of inactivity."""
    conn.execute(
        "UPDATE auth_tokens SET last_seen_at = last_seen_at - ? WHERE token_hash = ?",
        (ms, auth.hash_token(token)),
    )
    conn.commit()


@pytest.mark.parametrize(
    "platform,expected",
    [
        ("web", auth.WEB_IDLE_TTL_MS),
        ("android", auth.ANDROID_IDLE_TTL_MS),
        ("WEB", auth.WEB_IDLE_TTL_MS),  # case-insensitive
        (" android ", auth.ANDROID_IDLE_TTL_MS),  # whitespace-tolerant
        (None, auth.DEFAULT_IDLE_TTL_MS),  # pre-T-104 client
        ("ios", auth.DEFAULT_IDLE_TTL_MS),  # unknown platform, no hard failure
        (123, auth.DEFAULT_IDLE_TTL_MS),  # non-string junk
    ],
)
def test_login_resolves_the_idle_window_from_the_declared_platform(
    db_conn, platform, expected
):
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE, platform)

    assert _ttl_of(db_conn, token) == expected


def test_the_ttl_is_server_resolved_never_client_supplied(db_conn):
    """A client picks a bucket, not a duration: a numeric 'platform' that looks
    like a 10-year TTL must not become one."""
    auth.register(db_conn, EMAIL, PASSWORD)
    token, _ = auth.login(db_conn, EMAIL, PASSWORD, DEVICE, 10 * 365 * DAY_MS)

    assert _ttl_of(db_conn, token) == auth.DEFAULT_IDLE_TTL_MS


def test_web_session_survives_6_days_of_inactivity(client, app):
    conn, token = _account_with_session(client, app, "web")

    _age_session(conn, token, 6 * DAY_MS)

    assert client.get("/api/v1/lists", headers=_bearer(token)).status_code == 200


def test_web_session_expires_after_8_days_of_inactivity(client, app):
    conn, token = _account_with_session(client, app, "web")

    _age_session(conn, token, 8 * DAY_MS)

    resp = client.get("/api/v1/lists", headers=_bearer(token))
    assert resp.status_code == 401
    assert resp.get_json()["error"] == "session_expired"


def test_android_session_survives_the_web_window(client, app):
    """The whole point of the split: 30 idle days kills a web session but not
    an Android one."""
    conn, token = _account_with_session(client, app, "android")

    _age_session(conn, token, 30 * DAY_MS)

    assert client.get("/api/v1/lists", headers=_bearer(token)).status_code == 200


def test_android_session_expires_after_63_days_of_inactivity(client, app):
    conn, token = _account_with_session(client, app, "android")

    _age_session(conn, token, 63 * DAY_MS)

    resp = client.get("/api/v1/lists", headers=_bearer(token))
    assert resp.status_code == 401
    assert resp.get_json()["error"] == "session_expired"


def test_activity_slides_the_window_forward(client, app):
    """Inactivity, not age: a session used every 6 days stays alive well past
    the 7-day window."""
    conn, token = _account_with_session(client, app, "web")

    for _ in range(4):
        _age_session(conn, token, 6 * DAY_MS)
        assert client.get("/api/v1/lists", headers=_bearer(token)).status_code == 200

    # 24 days of wall-clock later, still authenticated.
    assert client.get("/api/v1/lists", headers=_bearer(token)).status_code == 200


def test_an_expired_session_is_deleted_on_sight_not_left_for_gc(client, app):
    conn, token = _account_with_session(client, app, "web")

    _age_session(conn, token, 8 * DAY_MS)
    client.get("/api/v1/lists", headers=_bearer(token))

    assert conn.execute("SELECT COUNT(*) AS n FROM auth_tokens").fetchone()["n"] == 0


def test_expired_sessions_are_hidden_from_the_sessions_list(client, app):
    """A dead session must not show up as a revokable phantom device."""
    conn, live = _account_with_session(client, app, "android")
    stale = client.post(
        "/api/v1/login",
        json={
            "email": EMAIL,
            "password": PASSWORD,
            "device_label": "old browser",
            "platform": "web",
        },
    ).get_json()["token"]

    _age_session(conn, stale, 8 * DAY_MS)

    sessions = client.get("/api/v1/account/sessions", headers=_bearer(live)).get_json()[
        "sessions"
    ]
    assert [s["device_label"] for s in sessions] == [DEVICE]


def _bearer(token):
    return {"Authorization": f"Bearer {token}"}


def _account_with_session(client, app, platform):
    """Register + log in over HTTP, returning a direct DB connection (for
    backdating) alongside the token."""
    from shoppinglist_server import db as db_module
    from shoppinglist_server import get_config_by_name

    client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    token = client.post(
        "/api/v1/login",
        json={
            "email": EMAIL,
            "password": PASSWORD,
            "device_label": DEVICE,
            "platform": platform,
        },
    ).get_json()["token"]
    conn = db_module.connect(get_config_by_name(app)["database_path"])
    return conn, token


# ---- credential input validation (T-116 type confusion, T-118 length caps) ----


@pytest.mark.parametrize("bad", [{}, [], 5, 5.5, True, None])
def test_register_rejects_non_string_email_with_422_not_500(client, bad):
    # EMAIL_RE.match() raises TypeError on a non-string, which used to surface as an
    # unauthenticated 500. A falsy non-string was caught by the old `not email` guard;
    # a truthy one (5, 5.5, True) was not.
    resp = client.post("/api/v1/register", json={"email": bad, "password": "password123"})

    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_email"


@pytest.mark.parametrize("bad", [{}, [], 5, True])
def test_register_rejects_non_string_password_with_422_not_500(client, bad):
    resp = client.post("/api/v1/register", json={"email": "typed@example.com", "password": bad})

    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_password"


@pytest.mark.parametrize("field", ["email", "password", "device_label"])
@pytest.mark.parametrize("bad", [{}, []])
def test_login_never_500s_on_a_wrongly_typed_field(client, field, bad):
    body = {"email": EMAIL, "password": PASSWORD, "device_label": DEVICE}
    body[field] = bad

    resp = client.post("/api/v1/login", json=body)

    assert resp.status_code < 500


def test_login_answers_a_malformed_email_exactly_like_a_wrong_one(client):
    # 401, not 422: a type-checked rejection would be a response that a correctly-shaped
    # guess never receives, which is itself a signal. Keep the uniform 401.
    malformed = client.post("/api/v1/login", json={"email": {}, "password": "x"})
    wrong = client.post("/api/v1/login", json={"email": "nobody@example.com", "password": "x"})

    assert malformed.status_code == wrong.status_code == 401
    assert malformed.get_json() == wrong.get_json()


def test_email_at_the_cap_is_accepted_and_one_over_is_rejected(client):
    local = "a" * (auth.MAX_EMAIL_LENGTH - len("@example.com"))
    at_cap = f"{local}@example.com"
    assert len(at_cap) == auth.MAX_EMAIL_LENGTH

    assert client.post(
        "/api/v1/register", json={"email": at_cap, "password": "password123"}
    ).status_code == 201

    over = f"a{at_cap}"
    resp = client.post("/api/v1/register", json={"email": over, "password": "password123"})
    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_email"


def test_the_email_cap_never_exceeds_what_an_invite_can_carry(db_conn):
    """An account that mint() could never invite would be a silent dead end (T-118)."""
    from shoppinglist_server.invites import MAX_INVITED_EMAIL_LENGTH

    assert auth.MAX_EMAIL_LENGTH <= MAX_INVITED_EMAIL_LENGTH


def test_password_at_the_cap_is_accepted_and_one_over_is_rejected(client):
    at_cap = "p" * auth.MAX_PASSWORD_LENGTH

    assert client.post(
        "/api/v1/register", json={"email": "atcap@example.com", "password": at_cap}
    ).status_code == 201

    resp = client.post(
        "/api/v1/register", json={"email": "over@example.com", "password": at_cap + "p"}
    )
    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_password"


def test_overlong_device_label_is_rejected(client):
    client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})

    resp = client.post(
        "/api/v1/login",
        json={
            "email": EMAIL,
            "password": PASSWORD,
            "device_label": "d" * (auth.MAX_DEVICE_LABEL_LENGTH + 1),
        },
    )

    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_device_label"


def test_email_with_a_colon_is_rejected_so_it_can_still_be_invited(client):
    # EMAIL_RE itself permits ':' (it only excludes '@' and whitespace), but invites.mint()
    # rejects it — such an account could never be invited to any list.
    resp = client.post(
        "/api/v1/register", json={"email": "we:rd@example.com", "password": "password123"}
    )

    assert resp.status_code == 422
    assert resp.get_json()["error"] == "invalid_email"


def test_change_email_inherits_every_registration_email_rule(client):
    client.post("/api/v1/register", json={"email": EMAIL, "password": PASSWORD})
    token = client.post(
        "/api/v1/login", json={"email": EMAIL, "password": PASSWORD, "device_label": DEVICE}
    ).get_json()["token"]
    headers = {"Authorization": f"Bearer {token}"}

    for bad in ("co:lon@example.com", "a" * 300 + "@example.com"):
        resp = client.post(
            "/api/v1/account/change-email",
            json={"password": PASSWORD, "new_email": bad},
            headers=headers,
        )
        assert resp.status_code == 422, bad
        assert resp.get_json()["error"] == "invalid_email"


# ---- login timing does not enumerate accounts (T-115) ------------------------


def test_login_does_the_same_password_hashing_work_whether_or_not_the_account_exists(
    db_conn, monkeypatch
):
    """The scrypt verify used to run only when a row was found, and the resulting ~52x timing gap
    enumerated accounts. Asserting call COUNT rather than wall-clock: a timing assertion on a
    loaded CI box is flaky, while "both paths verify exactly once" is the property that closes it.
    """
    auth.register(db_conn, EMAIL, PASSWORD)

    calls = []
    real_check = auth.check_password_hash

    def counting_check(pwhash, password):
        calls.append(pwhash)
        return real_check(pwhash, password)

    monkeypatch.setattr(auth, "check_password_hash", counting_check)

    with pytest.raises(ApiError):
        auth.login(db_conn, EMAIL, "wrong-password", DEVICE)
    known_calls = len(calls)

    calls.clear()
    with pytest.raises(ApiError):
        auth.login(db_conn, "no-such-account@example.com", "wrong-password", DEVICE)
    unknown_calls = len(calls)

    assert known_calls == unknown_calls == 1
    # And the unknown-address path must burn a REAL hash, not a cheap sentinel.
    assert calls[0] == auth._TIMING_EQUALIZER_HASH
    assert calls[0].startswith("scrypt:")
