"""One way to read a JSON request body, shared by every body-taking route (T-236).

Each route used to inline `request.get_json(force=True, silent=True) or {}` and then
call `.get` on the result. A body that is valid JSON but not an object — `[1]`,
`"abc"`, `5`, `true` — survives the `or {}` (it is truthy) and has no `.get`, so the
route died with an `AttributeError`: an unhandled 500 on, among others, the
unauthenticated `POST /register` and `POST /login`. The wire contract promises the
JSON error envelope on every failure, so such a body has to be a plain 422.
"""

from flask import request

from .errors import ApiError


def json_body() -> dict:
    """The request's JSON body as a dict.

    A missing, empty, unparseable or literal-`null` body is `{}`, exactly as the
    inlined expression produced: the route then sees its fields as absent and answers
    with whatever it already answers for a missing field. Anything else that parses to
    a non-object is a malformed request: `422 invalid_request`.
    """
    data = request.get_json(force=True, silent=True)
    if data is None:
        return {}
    if not isinstance(data, dict):
        raise ApiError(422, "invalid_request", "The request body must be a JSON object.")
    return data
