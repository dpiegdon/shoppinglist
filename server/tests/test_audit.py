"""Audit log (T-121).

The point of these tests is less "an event was emitted" than the two invariants that make the log
safe to keep: it must never contain an email address, and it must never contain a credential.
"""

import logging

import pytest

from shoppinglist_server import audit

PW = "password123"
EMAIL = "auditee@example.com"


@pytest.fixture
def records(caplog):
    caplog.set_level(logging.INFO, logger=audit.LOGGER_NAME)
    return caplog


def _messages(records):
    return [r.getMessage() for r in records.records if r.name == audit.LOGGER_NAME]


def _events(records):
    out = []
    for message in _messages(records):
        fields = dict(part.split("=", 1) for part in message.split(" ") if "=" in part)
        out.append(fields)
    return out


def _register_and_login(client, email=EMAIL, device="dev"):
    client.post("/api/v1/register", json={"email": email, "password": PW})
    resp = client.post(
        "/api/v1/login", json={"email": email, "password": PW, "device_label": device}
    )
    return resp.get_json()["token"], resp.get_json()["account_id"]


def _auth(token):
    return {"Authorization": f"Bearer {token}"}


# ---- the events themselves ---------------------------------------------------


def test_registration_login_and_logout_are_recorded(client, records):
    token, account_id = _register_and_login(client)
    client.post("/api/v1/logout", headers=_auth(token))

    events = [e["event"] for e in _events(records)]
    assert "account.registered" in events
    assert "auth.login" in events
    assert "auth.logout" in events
    assert all(e["account_id"] == account_id for e in _events(records) if "account_id" in e)


def test_a_failed_login_is_recorded_as_a_denial(client, records):
    client.post("/api/v1/register", json={"email": EMAIL, "password": PW})
    records.clear()

    client.post("/api/v1/login", json={"email": EMAIL, "password": "wrong", "device_label": "d"})

    denials = [e for e in _events(records) if e["event"] == "authz.denied"]
    assert len(denials) == 1
    assert denials[0]["code"] == "invalid_credentials"
    assert denials[0]["outcome"] == "denied"


def test_an_authorization_failure_is_recorded_with_the_account_that_attempted_it(client, records):
    owner_token, _ = _register_and_login(client, "owner@example.com", "devO")
    client.post(
        "/api/v1/sync",
        json={
            "cursor": 0,
            "device_id": "devO",
            "changes": {
                "lists": [{"id": "l1", "fields": {
                    "name": {"value": "L", "updated_at": 1, "updated_by": "devO"}}}]
            },
        },
        headers=_auth(owner_token),
    )
    intruder_token, intruder_id = _register_and_login(client, "intruder@example.com", "devI")
    records.clear()

    client.get("/api/v1/lists/l1/members", headers=_auth(intruder_token))

    denials = [e for e in _events(records) if e["event"] == "authz.denied"]
    assert len(denials) == 1
    assert denials[0]["code"] == "not_a_member"
    assert denials[0]["account_id"] == intruder_id


def test_account_changes_are_recorded(client, records):
    token, account_id = _register_and_login(client)
    records.clear()

    client.post(
        "/api/v1/account/change-password",
        json={"current_password": PW, "new_password": "brand-new-password"},
        headers=_auth(token),
    )
    client.post(
        "/api/v1/account/change-email",
        json={"password": "brand-new-password", "new_email": "moved@example.com"},
        headers=_auth(token),
    )

    events = [e["event"] for e in _events(records)]
    assert "account.password_changed" in events
    assert "account.email_changed" in events


def test_invite_mint_and_redeem_are_recorded(client, records):
    owner_token, _ = _register_and_login(client, "owner2@example.com", "devO")
    client.post(
        "/api/v1/sync",
        json={
            "cursor": 0,
            "device_id": "devO",
            "changes": {
                "lists": [{"id": "l2", "fields": {
                    "name": {"value": "L", "updated_at": 1, "updated_by": "devO"}}}]
            },
        },
        headers=_auth(owner_token),
    )
    guest_token, guest_id = _register_and_login(client, "guest@example.com", "devG")
    records.clear()

    minted = client.post(
        "/api/v1/lists/l2/invites",
        json={"invited_email": "guest@example.com"},
        headers=_auth(owner_token),
    ).get_json()
    client.post(
        "/api/v1/invites/redeem", json={"token": minted["token"]}, headers=_auth(guest_token)
    )

    events = {e["event"]: e for e in _events(records)}
    assert events["invite.minted"]["list_id"] == "l2"
    # The membership grant is the security-relevant half: who gained access to whose data.
    assert events["invite.redeemed"]["list_id"] == "l2"
    assert events["invite.redeemed"]["account_id"] == guest_id


# ---- the invariants that make the log safe to retain -------------------------


def test_no_email_address_ever_reaches_the_log(client, records):
    token, _ = _register_and_login(client)
    client.post("/api/v1/account/change-email",
                json={"password": PW, "new_email": "another@example.com"}, headers=_auth(token))
    client.post("/api/v1/login", json={"email": EMAIL, "password": "wrong", "device_label": "d"})

    blob = "\n".join(_messages(records))
    assert blob  # guard against the assertions below passing on an empty log
    for address in (EMAIL, "another@example.com"):
        assert address not in blob
    assert "@" not in blob


def test_no_token_or_password_ever_reaches_the_log(client, records):
    token, _ = _register_and_login(client)
    client.post(
        "/api/v1/account/change-password",
        json={"current_password": PW, "new_password": "brand-new-password"},
        headers=_auth(token),
    )

    blob = "\n".join(_messages(records))
    assert blob
    assert token not in blob
    assert PW not in blob
    assert "brand-new-password" not in blob


def test_a_caller_that_passes_a_forbidden_key_gets_it_redacted_not_logged(records):
    audit.record("test.event", account_id="acct-1", email="leak@example.com", token="s3cret")

    message = _messages(records)[0]
    assert "leak@example.com" not in message
    assert "s3cret" not in message
    assert message.count("<redacted>") == 2


def test_auditing_never_breaks_the_request_it_observes(records):
    class Explodes:
        def __repr__(self):
            raise RuntimeError("boom")

    # Must not propagate: the log is an observer, and an observer that can fail the observed
    # action is worse than no log at all.
    audit.record("test.event", weird=Explodes())
