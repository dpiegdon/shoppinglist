import pytest
from flask import Flask
from flask.testing import FlaskClient

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server.cli import shoppinglist_cli
from shoppinglist_server.protocol import PROTOCOL_HEADER, PROTOCOL_VERSION

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
