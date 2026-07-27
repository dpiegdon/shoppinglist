"""The dev harness app.py refuses to run on a publicly-known invite signing key (T-122).

This used to default to the literal key committed in this (public) repository, so forgetting the
environment variable produced a server that worked perfectly and was wholly insecure: anyone who
had read the repo could mint valid invite tokens for arbitrary lists and join them.
"""

import importlib.util
import pathlib

import pytest

APP_PY = pathlib.Path(__file__).resolve().parent.parent / "app.py"


def _load_app_module():
    """Fresh import of app.py each time — it builds the app at module scope."""
    spec = importlib.util.spec_from_file_location("_dev_app_under_test", APP_PY)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch, tmp_path):
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "dev.db"))
    monkeypatch.setenv("BASE_URL", "http://localhost:5000")
    monkeypatch.delenv("INVITE_HMAC_KEY", raising=False)
    monkeypatch.delenv("SECRET_KEY", raising=False)


def test_refuses_to_start_with_no_invite_hmac_key(monkeypatch):
    with pytest.raises(RuntimeError) as excinfo:
        _load_app_module()

    assert "INVITE_HMAC_KEY" in str(excinfo.value)


def test_refuses_to_start_on_the_published_dev_key(monkeypatch):
    monkeypatch.setenv("INVITE_HMAC_KEY", "dev-invite-hmac-key")

    with pytest.raises(RuntimeError) as excinfo:
        _load_app_module()

    assert "dev default" in str(excinfo.value)


def test_starts_with_any_operator_supplied_key(monkeypatch):
    # The README's documented dev command passes INVITE_HMAC_KEY=dev-key, so the guard must not
    # break the documented local workflow — only the unset and published-default cases.
    monkeypatch.setenv("INVITE_HMAC_KEY", "dev-key")

    module = _load_app_module()

    assert module.app is not None


def test_the_error_tells_the_operator_how_to_generate_one(monkeypatch):
    with pytest.raises(RuntimeError) as excinfo:
        _load_app_module()

    message = str(excinfo.value)
    assert "secrets.token_hex" in message
    assert "README" in message
