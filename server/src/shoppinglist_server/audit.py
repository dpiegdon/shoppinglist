"""Security-relevant event log (T-121).

Emits structured records through the stdlib `logging` module on a dedicated logger, so a host app
can route them to a file, journald or syslog without this blueprint imposing a destination. A
blueprint is a guest in the host app: it emits, it never configures handlers.

Two deliberate omissions, both load-bearing:

* **No email addresses.** Accounts are identified by their opaque id. Logs are typically retained
  longer and guarded less carefully than the database, and everything this server stores is
  personal data, so keeping addresses out of the log is the whole point of the indirection.
* **No tokens or passwords**, not even truncated. When a session must be named, the `auth_tokens`
  row id identifies it without being usable as a credential.

`_REDACTED_KEYS` enforces both against caller mistakes rather than trusting every call site.

The `ip` field is only as trustworthy as the deployment: behind the mandatory reverse proxy it is
the proxy's address unless the host app wires up `werkzeug.middleware.proxy_fix.ProxyFix`. See
"Audit log" in server/README.md.
"""

import logging

from flask import has_request_context, request

LOGGER_NAME = "shoppinglist_server.audit"

logger = logging.getLogger(LOGGER_NAME)

# Values that must never reach the log even if a call site passes them by mistake. Redacted rather
# than raising: a logging call must not be able to break a request it is only observing.
_REDACTED_KEYS = frozenset(
    {
        "email",
        "invited_email",
        "new_email",
        "password",
        "new_password",
        "current_password",
        "password_hash",
        "token",
        "token_hash",
    }
)


def record(event: str, *, account_id: str | None = None, outcome: str = "ok", **details) -> None:
    """Emit one audit record. Never raises: observation must not break the observed request."""
    try:
        fields = [f"event={event}", f"outcome={outcome}"]
        if account_id:
            fields.append(f"account_id={account_id}")
        if has_request_context():
            fields.append(f"ip={request.remote_addr}")
        for key in sorted(details):
            value = "<redacted>" if key in _REDACTED_KEYS else details[key]
            fields.append(f"{key}={value}")
        logger.info(" ".join(fields))
    except Exception:  # pragma: no cover - defensive; auditing must never break a request
        logger.exception("audit.record failed for event=%s", event)
