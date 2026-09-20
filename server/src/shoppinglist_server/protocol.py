"""The client/server protocol version (T-243).

Until 3.0.0 the interface had no version at all: an app installed on a phone kept talking to a
server that had moved on, and misread the answers instead of saying so. From 3.0.0 on every API
request carries `X-Client-Protocol: <integer>`, and a client older than the server is turned away
with `426 client_outdated` before anything else happens.

The policy (the full version lives in "Protocol version" in docs/wire-contract.md):

* `PROTOCOL_VERSION` is a single integer, bumped only by a change an already-installed client of
  the previous protocol cannot handle correctly. Additive changes an old client simply ignores —
  a new optional response field, an endpoint it never calls — do not bump it.
* A bump makes the release that ships it a MAJOR one, and the protocol version never exceeds the
  release's major version. `release-guard.py` enforces both at release time.
* The server is upgraded before its clients, so a client whose protocol is HIGHER than the
  server's is NOT refused: an older server cannot know what a newer client needs, and refusing
  would only replace a working setup with a dead one. Keeping the server ahead is the operator's
  job.

The same two constants exist in `web/src/api/protocol.ts` and the Android client's `Protocol.kt`;
a web test asserts all three agree.
"""

import re

PROTOCOL_VERSION = 3
PROTOCOL_HEADER = "X-Client-Protocol"

# Plain ASCII digits only. `str.isdigit()` and `\d` both accept other scripts' digits (and
# `int()` accepts "+3", "  3  " and "3_0"), which would let a client that formats its header
# wrongly pass the gate here and then be parsed differently by the next reader. Bounded, too:
# `int()` refuses a string of more than 4300 digits, so an unbounded match let a header of a
# few thousand digits crash the request (T-248). Nine digits is more protocol versions than
# there will ever be.
_DIGITS = re.compile(r"[0-9]{1,9}")


def client_is_current(header_value: str | None) -> bool:
    """Is the protocol this client declares good enough for this server?

    True only for a plain positive integer at or above `PROTOCOL_VERSION`. A missing header (every
    client built before 3.0.0), a malformed one, and any older version are all the same answer:
    that client has to be updated. "0" needs no rule of its own — it parses, and then it is below
    every real version.
    """
    if header_value is None or _DIGITS.fullmatch(header_value) is None:
        return False
    return int(header_value) >= PROTOCOL_VERSION
