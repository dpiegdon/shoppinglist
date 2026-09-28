import os

import pytest
from flask import Flask
from flask.testing import FlaskClient
from hypothesis import HealthCheck, settings

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server.cli import shoppinglist_cli
from shoppinglist_server.protocol import PROTOCOL_HEADER, PROTOCOL_VERSION

# Hypothesis profiles for the fuzz suites (T-326). `ci` is what the gate runs: few enough examples to
# stay within about a minute, derandomized so the gate never goes red on a draw it has not seen
# before, and without the example database, so nothing is written into the tree. `thorough` is for
# a deliberate hunt (`HYPOTHESIS_PROFILE=thorough`, see server/README.md): many more examples, a
# fresh random seed each run, and the database under server/.hypothesis/ so a failure it found is
# replayed first next time.
_FUZZ_HEALTH_CHECKS = [
    HealthCheck.too_slow,
    HealthCheck.data_too_large,
    HealthCheck.filter_too_much,
]
settings.register_profile(
    "ci",
    max_examples=40,
    deadline=None,
    derandomize=True,
    database=None,
    print_blob=True,
    suppress_health_check=_FUZZ_HEALTH_CHECKS,
)
settings.register_profile(
    "thorough",
    max_examples=2000,
    deadline=None,
    print_blob=True,
    suppress_health_check=_FUZZ_HEALTH_CHECKS,
)
settings.load_profile(os.environ.get("HYPOTHESIS_PROFILE", "ci"))

# Header name as WSGI spells it, for environ_base below.
_PROTOCOL_ENVIRON_KEY = f"HTTP_{PROTOCOL_HEADER.upper().replace('-', '_')}"


class ProtocolClient(FlaskClient):
    """A test client that declares the current protocol on every request (T-243).

    Every API request from a real client carries `X-Client-Protocol`, and without it the gate in
    `create_blueprint` answers `426` before any route runs. Rather than editing several hundred
    existing calls — none of which is about the protocol — the header is injected here, once, for
    every test client in the suite.

    `environ_base`, not an `open()` override, because it also covers the calls that hand the
    client a prepared `EnvironBuilder`/environ, and because a header passed explicitly at the call
    site still wins (werkzeug applies real headers after `environ_base`). That is what lets the
    protocol tests send `"abc"`, `"0"` or `"2"` without any special support here.

    `app.test_client(protocol=None)` sends no header at all — the pre-3.0.0 client — and
    `protocol=<n>` fixes a different version for every request from that client.
    """

    def __init__(self, *args, protocol: int | str | None = PROTOCOL_VERSION, **kwargs):
        super().__init__(*args, **kwargs)
        if protocol is not None:
            self.environ_base[_PROTOCOL_ENVIRON_KEY] = str(protocol)


@pytest.fixture(scope="session", autouse=True)
def _protocol_test_client():
    """Make ProtocolClient the default for the whole suite.

    On `Flask`, not on the `app` fixture: tests that build their own app (multi-mount, prefix
    mount, closed registration, the hardening suite) call `app.test_client()` directly, and they
    need the header just as much.
    """
    previous = Flask.test_client_class
    Flask.test_client_class = ProtocolClient
    yield
    Flask.test_client_class = previous


@pytest.fixture
def db_conn(tmp_path):
    path = tmp_path / "test.db"
    conn = db_module.connect(str(path))
    db_module.init_db(conn)
    yield conn
    conn.close()


@pytest.fixture
def app(tmp_path):
    database_path = str(tmp_path / "app_test.db")
    conn = db_module.connect(database_path)
    db_module.init_db(conn)
    conn.close()

    flask_app = Flask(__name__)
    flask_app.config["TESTING"] = True

    bp = create_blueprint(
        database_path=database_path,
        invite_hmac_key=b"test-hmac-key",
        base_url="http://testserver",
    )
    flask_app.register_blueprint(bp)
    flask_app.cli.add_command(shoppinglist_cli)
    return flask_app


@pytest.fixture
def client(app):
    return app.test_client()


@pytest.fixture
def cli_runner(app):
    return app.test_cli_runner()


@pytest.fixture(scope="module")
def fast_password_hashing():
    """Swap scrypt for a single pbkdf2 round, for a whole module (T-326).

    A fuzz run sends thousands of requests, and every one that reaches a password check (a login
    with any string password, a confirmation password, a registration) costs scrypt's ~200 ms —
    the unknown-address login included, by design (T-115). What the fuzz suites test is how the
    server answers, not how hard its hash is, so they hash cheaply. `check_password_hash` reads
    the method from the stored hash, so only the three places that make a hash need replacing.
    """
    from werkzeug.security import generate_password_hash

    from shoppinglist_server import accounts, auth

    def cheap(password, *_args, **_kwargs):
        return generate_password_hash(password, method="pbkdf2:sha256:1")

    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(auth, "generate_password_hash", cheap)
        mp.setattr(accounts, "generate_password_hash", cheap)
        mp.setattr(auth, "_TIMING_EQUALIZER_HASH", cheap("not-a-secret"))
        yield
