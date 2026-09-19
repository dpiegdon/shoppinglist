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

Records are `key=value` pairs on one line, and some values come from the client (the `platform` a
login declares, the `path` a denied request asked for). Every field therefore goes through
`_sanitize`, which escapes control characters and caps the length, so no value can smuggle a
newline into the log and forge a second record, or bloat it with a megabyte of text.

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


# A field value is one whitespace-separated token on one line, so anything that could end the line
# or the token has to be neutered before it is written. Control characters become escapes (a
# literal backslash is escaped too, so the escapes are unambiguous) and the result is capped: a
# client can choose some of these values, and an audit log nobody can trust — or that one request
# can fill — is worse than none.
_ESCAPES = {"\\": "\\\\", "\n": "\\n", "\r": "\\r", "\t": "\\t"}
_MAX_VALUE_CHARS = 200
_TRUNCATION_MARKER = "...[truncated]"


def _sanitize(value) -> str:
    """Render one field value as a single-line token of at most `_MAX_VALUE_CHARS` characters."""
    text = value if isinstance(value, str) else repr(value)
    out = []
    for char in text:
        escape = _ESCAPES.get(char)
        if escape is not None:
            out.append(escape)
        elif char < "\x20" or "\x7f" <= char <= "\x9f":
            out.append(f"\\x{ord(char):02x}")
        else:
            out.append(char)
    text = "".join(out)
    if len(text) > _MAX_VALUE_CHARS:
        text = text[: _MAX_VALUE_CHARS - len(_TRUNCATION_MARKER)] + _TRUNCATION_MARKER
    return text


def record(event: str, *, account_id: str | None = None, outcome: str = "ok", **details) -> None:
    """Emit one audit record. Never raises: observation must not break the observed request."""
    try:
        fields = [f"event={_sanitize(event)}", f"outcome={_sanitize(outcome)}"]
        if account_id:
            fields.append(f"account_id={_sanitize(account_id)}")
        if has_request_context():
            fields.append(f"ip={_sanitize(request.remote_addr)}")
        for key in sorted(details):
            value = "<redacted>" if key in _REDACTED_KEYS else _sanitize(details[key])
            fields.append(f"{key}={value}")
        logger.info(" ".join(fields))
    except Exception:  # pragma: no cover - defensive; auditing must never break a request
        logger.exception("audit.record failed for event=%s", event)
