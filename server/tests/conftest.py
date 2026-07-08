import pytest
from flask import Flask

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module
from shoppinglist_server.cli import shoppinglist_cli


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
