"""One way to read a JSON request body, shared by every body-taking route (T-236).

Each route used to inline `request.get_json(force=True, silent=True) or {}` and then
call `.get` on the result. A body that is valid JSON but not an object — `[1]`,
`"abc"`, `5`, `true` — survives the `or {}` (it is truthy) and has no `.get`, so the
route died with an `AttributeError`: an unhandled 500 on, among others, the
unauthenticated `POST /register` and `POST /login`. The wire contract promises the
JSON error envelope on every failure, so such a body has to be a plain 422.

Two more bodies parse but cannot be used (T-316). JSON nested deeper than the parser's
recursion limit raises `RecursionError`, which is not a `ValueError` and so escaped
`silent=True`. And JSON may spell a lone UTF-16 surrogate (`"\\ud800"`): Python keeps it
in the string, and the first attempt to encode it — SQLite binding it, hashing a
password — fails. Both are `422 invalid_request` here, before any route sees them.
"""

import re

from flask import request

from .errors import ApiError

_LONE_SURROGATE = re.compile("[\ud800-\udfff]")


def has_lone_surrogate(value) -> bool:
    """Whether any string in `value` — a decoded JSON value, keys included — holds an unpaired
    surrogate. A surrogate pair in JSON (`"\\ud83d\\ude00"`) decodes to one astral character, so
    any surrogate code point left in a Python string is unpaired. Iterative, not recursive: a
    body only just shallow enough for the parser must not overflow the stack here."""
    stack = [value]
    while stack:
        current = stack.pop()
        if isinstance(current, str):
            if _LONE_SURROGATE.search(current):
                return True
        elif isinstance(current, dict):
            stack.extend(current.keys())
            stack.extend(current.values())
        elif isinstance(current, list):
            stack.extend(current)
    return False


def json_body(*, strings_checked_by_route: bool = False) -> dict:
    """The request's JSON body as a dict.

    A missing, empty, unparseable or literal-`null` body is `{}`, exactly as the
    inlined expression produced: the route then sees its fields as absent and answers
    with whatever it already answers for a missing field. Anything else that parses to
    a non-object is a malformed request: `422 invalid_request`, and so is a body nested
    too deeply to parse or one holding a lone surrogate in any string.

    `strings_checked_by_route=True` skips the surrogate check for a route that checks each
    string itself and answers more precisely: `/sync` names the offending row and field, so
    the client quarantines that row instead of retrying the whole push forever.
    """
    try:
        data = request.get_json(force=True, silent=True)
    except RecursionError:
        raise ApiError(422, "invalid_request", "The request body is nested too deeply.") from None
    if data is None:
        return {}
    if not isinstance(data, dict):
        raise ApiError(422, "invalid_request", "The request body must be a JSON object.")
    if not strings_checked_by_route and has_lone_surrogate(data):
        raise ApiError(
            422, "invalid_request", "The request body contains text that is not valid Unicode."
        )
    return data
