"""Generated request bodies against every body-taking endpoint (T-326).

The lone-surrogate 500 of T-316 was found by hand. This module looks for the whole class: for
each endpoint it sends arbitrary JSON (any type and nesting, text with surrogates, control and
format characters, strings over every cap, numbers at and past the int64 bounds, NaN), raw bytes
that are not JSON at all, and realistic bodies with exactly one value replaced, removed or added
anywhere in them. Whatever it sends, the server must hold to what docs/wire-contract.md promises
for every answer:

- never a 5xx;
- every non-2xx is the `{"error", "message"}` envelope;
- `Cache-Control: no-store`;
- on `/sync`, a 422 names `row_id` — one of the pushed rows' ids — whenever a row was at fault,
  and no `row_id` for a fault of the request as a whole.

Every request runs against the same small world (two accounts, one an admin; a shopping list with
an item, an expenses list with an entry, a pending invite), restored from a snapshot before each
example, so one example's successful delete or password change never leaks into the next.

Examples per endpoint come from the Hypothesis profile (see conftest.py): modest in the gate,
many more with `HYPOTHESIS_PROFILE=thorough`.
"""

import copy
import json
import sqlite3

import pytest
from flask import Flask
from hypothesis import given, settings
from hypothesis import strategies as st

from shoppinglist_server import create_blueprint
from shoppinglist_server import db as db_module

PASSWORD = "correct horse battery"
USER_EMAIL = "user@example.com"
ADMIN_EMAIL = "admin@example.com"
LIST_ID = "list-shopping"
EXPENSES_LIST_ID = "list-expenses"
ITEM_ID = "item-milk"
ENTRY_ID = "entry-dinner"

# ---- the world -------------------------------------------------------------------------------


class World:
    """The app, the ids and tokens in it, and a snapshot to restore before every example."""

    def __init__(self, tmp_path):
        self.db_path = str(tmp_path / "fuzz.db")
        conn = db_module.connect(self.db_path)
        db_module.init_db(conn)
        conn.close()
        app = Flask(__name__)
        app.config["TESTING"] = True
        app.register_blueprint(
            create_blueprint(
                database_path=self.db_path,
                invite_hmac_key=b"fuzz-hmac-key",
                base_url="http://testserver",
                serve_web_client=False,
                serve_invite_landing_page=False,
                serve_android_apk=False,
                admin_emails=[ADMIN_EMAIL],
            )
        )
        self.client = app.test_client()
        self.user_id = self._register(USER_EMAIL)
        self.admin_id = self._register(ADMIN_EMAIL)
        self.user_token = self._login(USER_EMAIL)
        self.admin_token = self._login(ADMIN_EMAIL)
        self.base_sync = {
            "cursor": 0,
            "device_id": "dev-fuzz",
            "full_lists": [LIST_ID],
            "changes": {
                "lists": [
                    {
                        "id": LIST_ID,
                        "created_at": 1_000,
                        "fields": {
                            "name": _clock("Groceries"),
                            "category_order": _clock(["dairy", "bakery"]),
                            "notes": _clock("back door"),
                            "kind": _clock("shopping"),
                            "currency": _clock(None),
                            "deleted": _clock(False),
                        },
                    },
                    {
                        "id": EXPENSES_LIST_ID,
                        "created_at": 1_000,
                        "fields": {
                            "name": _clock("Trip"),
                            "kind": _clock("expenses"),
                            "currency": _clock("EUR"),
                        },
                    },
                ],
                "items": [
                    {
                        "id": ITEM_ID,
                        "list_id": LIST_ID,
                        "created_at": 1_000,
                        "fields": {
                            "name": _clock("Milk"),
                            "category": _clock("dairy"),
                            "stores": _clock(["Rewe", "Aldi"]),
                            "quantity": _clock("2l"),
                            "price": _clock({"amount": "1.99", "currency": "EUR"}),
                            "note": _clock("the ripe ones"),
                            "due": _clock("2026-10-01"),
                            "status": _clock("todo"),
                            "expense": _clock(None),
                            "deleted": _clock(False),
                        },
                    },
                    {
                        "id": ENTRY_ID,
                        "list_id": EXPENSES_LIST_ID,
                        "fields": {
                            "name": _clock("Dinner"),
                            "expense": _clock(
                                {
                                    "type": "expense",
                                    "paid_by": {self.user_id: "40.00"},
                                    "equal_by": False,
                                    "paid_for": {self.user_id: "40.00"},
                                    "equal_for": True,
                                    "date": "2026-09-17",
                                }
                            ),
                        },
                    },
                ],
            },
        }
        response = self.post("/sync", self.user_token, self.base_sync)
        assert response.status_code == 200, response.get_json()
        # The fuzzed pushes re-send the same rows at a later clock, so they win and exercise the
        # write paths rather than all being discarded as stale.
        self.base_sync = _retimed(self.base_sync, 2_000)
        # A second push whose new item collides, case-insensitively, with the stored "Milk" and
        # whose rename of that one lands on "Bread": both same-name merge paths, under mutation.
        self.merge_sync = {
            "cursor": 0,
            "device_id": "dev-other",
            "changes": {
                "items": [
                    {
                        "id": "item-bread",
                        "list_id": LIST_ID,
                        "fields": {"name": _clock("Bread", 3_000)},
                    },
                    {
                        "id": "item-duplicate",
                        "list_id": LIST_ID,
                        "created_at": 500,
                        "fields": {
                            "name": _clock("MILK", 3_000, "dev-other"),
                            "status": _clock("checked", 3_000, "dev-other"),
                        },
                    },
                    {
                        "id": ITEM_ID,
                        "fields": {"name": _clock("bread", 3_001, "dev-other")},
                    },
                ]
            },
        }
        response = self.post(
            f"/lists/{LIST_ID}/invites", self.user_token, {"invited_email": ADMIN_EMAIL}
        )
        assert response.status_code == 201, response.get_json()
        self.invite_id = response.get_json()["invite_id"]
        self.invite_token = response.get_json()["token"]

        self.snapshot = sqlite3.connect(":memory:")
        source = sqlite3.connect(self.db_path)
        source.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        source.backup(self.snapshot)
        source.close()

    def _register(self, email):
        response = self.client.post("/api/v1/register", json={"email": email, "password": PASSWORD})
        assert response.status_code == 201, response.get_json()
        return response.get_json()["account_id"]

    def _login(self, email):
        response = self.client.post("/api/v1/login", json={"email": email, "password": PASSWORD})
        assert response.status_code == 200, response.get_json()
        return response.get_json()["token"]

    def restore(self):
        target = sqlite3.connect(self.db_path)
        self.snapshot.backup(target)
        target.close()

    def post(self, path, token, body):
        return self.client.post(f"/api/v1{path}", json=body, headers=_auth(token))

    def token(self, role):
        return {"user": self.user_token, "admin": self.admin_token, "anon": None}[role]


def _clock(value, ts=1_000, by="dev-fuzz"):
    return {"value": value, "updated_at": ts, "updated_by": by}


def _retimed(value, ts):
    """A copy of a sync body with every field clock moved to `ts`."""
    value = copy.deepcopy(value)
    for row in value["changes"]["lists"] + value["changes"]["items"]:
        for clock in row["fields"].values():
            clock["updated_at"] = ts
    return value


def _auth(token):
    return {"Authorization": f"Bearer {token}"} if token else {}


@pytest.fixture(scope="module")
def world(tmp_path_factory, fast_password_hashing):
    return World(tmp_path_factory.mktemp("fuzz_api"))


# ---- the endpoints ---------------------------------------------------------------------------

# (method, path, who calls it, realistic bodies to mutate). Paths and bodies are templates over
# the world: `{user_id}` and friends are filled in per world, and a body of None means the
# endpoint takes none (it still has to survive being sent one).
ENDPOINTS = [
    ("POST", "/register", "anon", [{"email": "new@example.com", "password": PASSWORD}]),
    (
        "POST",
        "/login",
        "anon",
        [
            {"email": USER_EMAIL, "password": PASSWORD},
            {
                "email": USER_EMAIL,
                "password": PASSWORD,
                "device_label": "Pixel",
                "platform": "android",
            },
        ],
    ),
    ("POST", "/logout", "user", None),
    ("PATCH", "/settings", "user", [{"default_currency": "EUR", "initials": "US"}]),
    (
        "POST",
        "/account/change-password",
        "user",
        [{"current_password": PASSWORD, "new_password": "another good password"}],
    ),
    (
        "POST",
        "/account/change-email",
        "user",
        [{"password": PASSWORD, "new_email": "renamed@example.com"}],
    ),
    ("DELETE", "/account", "user", [{"password": PASSWORD}]),
    ("POST", "/lists/{list_id}/invites", "user", [{"invited_email": "friend@example.com"}]),
    ("POST", "/lists/{expenses_list_id}/close-votes", "user", None),
    ("DELETE", "/lists/{expenses_list_id}/close-votes", "user", None),
    ("POST", "/lists/{list_id}/leave", "user", None),
    ("DELETE", "/invites/{invite_id}", "user", None),
    ("POST", "/invites/redeem", "admin", [{"token": "{invite_token}"}]),
    ("POST", "/sync", "user", ["{base_sync}", "{merge_sync}"]),
    (
        "PUT",
        "/admin/server-settings",
        "admin",
        [
            {"allow_registration": False},
            {"message": "Down for maintenance on Sunday."},
            {"allow_registration": True, "message": ""},
        ],
    ),
    ("POST", "/admin/users/{user_id}/reset-password", "admin", [{"password": PASSWORD}]),
    ("DELETE", "/admin/users/{user_id}", "admin", [{"password": PASSWORD}]),
]
ENDPOINT_IDS = [f"{method} {path}" for method, path, _, _ in ENDPOINTS]


def _fill(template, world):
    """The template with the world's ids substituted, at any depth."""
    names = {
        "user_id": world.user_id,
        "admin_id": world.admin_id,
        "list_id": LIST_ID,
        "expenses_list_id": EXPENSES_LIST_ID,
        "invite_id": world.invite_id,
        "invite_token": world.invite_token,
    }
    if template == "{base_sync}":
        return copy.deepcopy(world.base_sync)
    if template == "{merge_sync}":
        return copy.deepcopy(world.merge_sync)
    if isinstance(template, str):
        return template.format(**names)
    if isinstance(template, dict):
        return {key: _fill(value, world) for key, value in template.items()}
    if isinstance(template, list):
        return [_fill(value, world) for value in template]
    return template


# ---- what gets sent --------------------------------------------------------------------------

# Characters that have broken or could break something: controls, the NUL, format characters
# (zero-width, bidi overrides, BOM), line and paragraph separators, the colon the invite token is
# delimited by, '@' and '.', and the lone surrogates JSON can spell but Python cannot encode.
_AWKWARD = "\x00\x01\x1f\x7f\n\r\t\u200b\u200d\u202e\u2066\ufeff\u2028\u2029\u00a0:@. "
_PLAIN_CHARACTERS = st.one_of(st.characters(), st.sampled_from(_AWKWARD))
# Lone surrogates, drawn directly: st.characters() leaves category Cs out by default.
_SURROGATES = st.integers(0xD800, 0xDFFF).map(chr)
_CHARACTERS = st.one_of(_PLAIN_CHARACTERS, _SURROGATES)
# One string in four may hold a surrogate. More, and most bodies would stop at json_body's
# surrogate check before any route saw them.
# Strings just under, at and over every documented cap, in one-, two- and four-byte characters.
_CAPS = [3, 8, 16, 17, 32, 33, 127, 128, 129, 200, 201, 254, 255, 320, 321, 500, 501, 5000, 5001]
_INT64 = 2**63
_EDGE_INTS = st.sampled_from(
    [0, 1, -1, 2**31 - 1, 2**31, 2**53, _INT64 - 1, _INT64, -_INT64, -_INT64 - 1, 2**64, 10**40]
)


def _json_values(characters, units):
    """Any JSON value, its strings drawn from `characters`, its long strings repeat `units`."""
    text = st.text(characters, max_size=10)
    huge = st.builds(
        lambda unit, n: (unit * n)[:n], st.sampled_from(units), st.sampled_from(_CAPS + [70_000])
    )
    leaves = st.one_of(
        st.none(),
        st.booleans(),
        st.integers(),
        _EDGE_INTS,
        st.floats(),  # NaN and ±Infinity included: Python's json writes and reads them
        text,
        huge,
    )
    return st.recursive(
        leaves,
        lambda children: st.lists(children, max_size=4)
        | st.dictionaries(text | huge, children, max_size=4),
        max_leaves=12,
    )


_UNITS = ["a", "\u00e9", "\U0001f600", "a@b.co", " "]
_PLAIN_JSON = _json_values(_PLAIN_CHARACTERS, _UNITS)
# One value in four may hold a surrogate somewhere. More, and most bodies would stop at
# json_body's surrogate check before any route saw them.
JSON_VALUES = st.one_of(
    _PLAIN_JSON, _PLAIN_JSON, _PLAIN_JSON, _json_values(_CHARACTERS, [*_UNITS, "\ud800"])
)
_TEXT = st.one_of(
    st.text(_PLAIN_CHARACTERS, max_size=10),
    st.text(_PLAIN_CHARACTERS, max_size=10),
    st.text(_PLAIN_CHARACTERS, max_size=10),
    st.text(_CHARACTERS, max_size=10),
)
_HUGE_TEXT = st.builds(
    lambda unit, n: (unit * n)[:n],
    st.sampled_from([*_UNITS, "\ud800"]),
    st.sampled_from(_CAPS + [70_000]),
)


def _nested(depth, opener, closer):
    return (opener * depth + closer * depth).encode()


# Bodies that are not a JSON value at all, or one that is too deep to parse.
RAW_BODIES = st.one_of(
    st.binary(max_size=40),
    st.sampled_from(
        [
            b"",
            b"null",
            b"{",
            b"\xff\xfe{}",
            b'{"a": 1} trailing',
            b'"\\ud800"',
            b'{"email": "\\udfff"}',
            _nested(1_000, "[", "]"),
            _nested(100_000, "[", "]"),
            ('{"a":' * 50_000 + "1" + "}" * 50_000).encode(),
        ]
    ),
)


# Mostly escaped; the raw form reaches the same code, so it needs fewer examples.
_ASCII_ONLY = st.sampled_from([True, True, True, False])


def _encode(value, ascii_only):
    """JSON bytes for a value. With `ascii_only` a lone surrogate is escaped (`"\\ud800"`), which
    is valid JSON; without, it goes out as raw CESU-8 bytes, which are not valid UTF-8 but which
    the server's parser decodes all the same (see `_as_the_server_reads`)."""
    return json.dumps(value, ensure_ascii=ascii_only).encode("utf-8", "surrogatepass")


def _paths(value, prefix=()):
    """Every position in a nested JSON value, the root included."""
    yield prefix
    if isinstance(value, dict):
        for key, child in value.items():
            yield from _paths(child, (*prefix, key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from _paths(child, (*prefix, index))


def _known_keys(value):
    keys = set()
    for path in _paths(value):
        keys.update(key for key in path if isinstance(key, str))
    return sorted(keys)


@st.composite
def _near_string(draw, value):
    """`value` bent at an edge: an awkward character spliced in, one character swapped for one,
    padded to just under, at or over a cap, re-cased, blanked or given a trailing space. A string
    that is otherwise valid is what gets past each route's shape checks to the code behind them —
    the SQL bind, the hash, the token — which is where T-316's surrogate did its damage."""
    index = draw(st.integers(0, len(value)))
    plain = st.text(_PLAIN_CHARACTERS, min_size=1, max_size=3)
    awkward = draw(st.one_of(plain, plain, plain, st.text(_SURROGATES, min_size=1, max_size=2)))
    cap = draw(st.sampled_from(_CAPS))
    inserted = value[:index] + awkward + value[index:]
    return draw(
        st.sampled_from(
            [
                inserted,
                inserted,
                inserted,
                value[:index] + awkward + value[index + 1 :],
                (value + "x" * cap)[: max(cap, len(value) + 1)],
                value.upper(),
                "",
                " ",
                value + " ",
            ]
        )
    )


@st.composite
def _near(draw, value):
    """A value close to `value` — same type, at its edges — or, a quarter of the time, any value
    at all."""
    if draw(st.integers(0, 3)) == 0:
        return draw(JSON_VALUES)
    if isinstance(value, str):
        return draw(_near_string(value))
    if isinstance(value, bool):
        return draw(st.sampled_from([not value, 0, 1, None, str(value).lower()]))
    if isinstance(value, int):
        return draw(_EDGE_INTS | st.sampled_from([value - 1, value + 1, -value, float(value)]))
    if isinstance(value, list):
        return draw(st.sampled_from([[], value * 2, [value], None]))
    if isinstance(value, dict):
        return draw(st.sampled_from([{}, [value], None]))
    return draw(JSON_VALUES)


@st.composite
def _mutated(draw, base):
    """`base` with exactly one change, at any depth: a value replaced by one near it (or by any
    value at all), a key or entry removed, or a key or entry added. Keys added are mostly ones
    the schema knows, so they land where they matter."""
    body = copy.deepcopy(base)
    path = draw(st.sampled_from(list(_paths(body))))
    if not path:
        # The root only gains a key: every other field stays as valid as it was, so the route's
        # success path is reached too, with something extra riding along.
        body[draw(st.sampled_from(_known_keys(base)) | _TEXT)] = draw(JSON_VALUES)
        return body
    parent = body
    for key in path[:-1]:
        parent = parent[key]
    target = parent[path[-1]]
    operation = draw(st.sampled_from(["replace", "replace", "replace", "remove", "add"]))
    if operation == "remove":
        del parent[path[-1]]
    elif operation == "add" and isinstance(target, dict):
        key = draw(st.sampled_from(_known_keys(base)) | _TEXT)
        target[key] = draw(JSON_VALUES)
    elif operation == "add" and isinstance(target, list):
        target.insert(draw(st.integers(0, len(target))), draw(_near(target[0] if target else "")))
    else:
        parent[path[-1]] = draw(_near(target))
    return body


# ---- what must hold --------------------------------------------------------------------------

# /sync codes about the request as a whole: no row is at fault, so none may be named.
_SYNC_REQUEST_CODES = {
    "invalid_request",
    "invalid_cursor",
    "invalid_device_id",
    "invalid_full_lists",
    "invalid_changes",
    "too_many_changes",
}


def _rows(body):
    """The pushed rows of a /sync body, as far as it has any."""
    if not isinstance(body, dict) or not isinstance(body.get("changes"), dict):
        return []
    rows = []
    for key in ("lists", "items"):
        value = body["changes"].get(key)
        if isinstance(value, list):
            rows.extend(value)
    return rows


def _has_usable_id(row):
    if not isinstance(row, dict) or not isinstance(row.get("id"), str) or not row["id"].strip():
        return False
    return not any(0xD800 <= ord(c) <= 0xDFFF for c in row["id"])


def check_response(response, path, body):
    """Everything the contract promises about an answer, whatever was asked."""
    status = response.status_code
    assert status < 500, (status, response.get_data(as_text=True)[:500])
    assert "no-store" in response.headers.get("Cache-Control", ""), response.headers
    if 200 <= status < 300:
        return
    envelope = response.get_json(silent=True)
    assert isinstance(envelope, dict), response.get_data()[:500]
    assert isinstance(envelope.get("error"), str) and envelope["error"], envelope
    assert isinstance(envelope.get("message"), str) and envelope["message"], envelope
    if path != "/sync" or status != 422:
        return
    code = envelope["error"]
    rows = _rows(body)
    if code in _SYNC_REQUEST_CODES:
        assert "row_id" not in envelope, envelope
    elif "row_id" in envelope:
        pushed_ids = {row["id"] for row in rows if _has_usable_id(row)}
        assert envelope["row_id"] in pushed_ids, (envelope, pushed_ids)
    else:
        # The one row-level answer without a row_id: a row that has no id worth naming.
        assert code == "invalid_row", envelope
        assert any(not _has_usable_id(row) for row in rows), (envelope, rows)


def _as_the_server_reads(data):
    """The body as the server decodes it, or None where it cannot. Not the Python value it was
    encoded from: a surrogate PAIR in a Python string goes out as two escapes and comes back as
    one astral character, so the ids a check compares must be read from the wire. Read as the
    server reads it, too: `json.loads` on bytes decodes them with `surrogatepass`, so a raw
    (CESU-8) surrogate arrives as the same lone surrogate an escaped one does."""
    try:
        return json.loads(data)
    except (ValueError, RecursionError):
        return None


def _send(world, method, path_template, role, data):
    """Send `data` and return the path, the response, and the body as the server read it."""
    world.restore()
    path = _fill(path_template, world)
    response = world.client.open(
        f"/api/v1{path}",
        method=method,
        data=data,
        content_type="application/json",
        headers=_auth(world.token(role)),
    )
    return path, response, _as_the_server_reads(data)


# ---- the tests -------------------------------------------------------------------------------

# The profile's example count is split unevenly: a body with no schema behind it mostly stops at
# the first shape check, so the realistic bodies with one change — which get past that check to
# the code behind it — are where the examples pay off.
_PROFILE_EXAMPLES = settings().max_examples  # the loaded profile's count
_SHALLOW = settings(max_examples=max(1, _PROFILE_EXAMPLES // 2))


@pytest.mark.parametrize(("method", "path", "role", "bases"), ENDPOINTS, ids=ENDPOINT_IDS)
@_SHALLOW
@given(body=JSON_VALUES, ascii_only=_ASCII_ONLY)
def test_any_json_body_gets_a_contract_answer(world, method, path, role, bases, body, ascii_only):
    sent_path, response, read = _send(world, method, path, role, _encode(body, ascii_only))
    check_response(response, sent_path, read)


@pytest.mark.parametrize(("method", "path", "role", "bases"), ENDPOINTS, ids=ENDPOINT_IDS)
@_SHALLOW
@given(raw=RAW_BODIES)
def test_any_raw_body_gets_a_contract_answer(world, method, path, role, bases, raw):
    sent_path, response, read = _send(world, method, path, role, raw)
    check_response(response, sent_path, read)


_WITH_BODIES = [endpoint for endpoint in ENDPOINTS if endpoint[3] is not None]


@pytest.mark.parametrize(
    ("method", "path", "role", "bases"),
    _WITH_BODIES,
    ids=[f"{method} {path}" for method, path, _, _ in _WITH_BODIES],
)
@settings(max_examples=5 * _PROFILE_EXAMPLES)
@given(data=st.data(), ascii_only=_ASCII_ONLY)
def test_a_realistic_body_with_one_change_gets_a_contract_answer(
    world, method, path, role, bases, data, ascii_only
):
    base = _fill(data.draw(st.sampled_from(bases)), world)
    body = data.draw(_mutated(base))
    sent_path, response, read = _send(world, method, path, role, _encode(body, ascii_only))
    check_response(response, sent_path, read)


@pytest.mark.parametrize(("method", "path", "role", "bases"), ENDPOINTS, ids=ENDPOINT_IDS)
@_SHALLOW
@given(segment=st.one_of(_TEXT, _HUGE_TEXT, st.sampled_from(["..", "%2F", "%00", "a:b"])))
def test_any_path_id_gets_a_contract_answer(world, method, path, role, bases, segment):
    """The ids in a path are client-chosen text too."""
    if "{" not in path:
        return
    world.restore()
    # Werkzeug decodes the path as UTF-8 with replacement, so a surrogate cannot survive into it;
    # it is sent percent-encoded as the bytes a client would put on the wire.
    quoted = "".join(
        c if c.isascii() and (c.isalnum() or c in "-_.~") else _percent(c) for c in segment
    )
    sent_path = path
    for name in ("{user_id}", "{list_id}", "{expenses_list_id}", "{invite_id}"):
        sent_path = sent_path.replace(name, quoted)
    body = _fill(bases[0], world) if bases else None
    response = world.client.open(
        f"/api/v1{sent_path}",
        method=method,
        json=body,
        headers=_auth(world.token(role)),
    )
    check_response(response, sent_path, body)


def _percent(c):
    return "".join(f"%{byte:02X}" for byte in c.encode("utf-8", "surrogatepass"))


@pytest.mark.parametrize(("method", "path", "role", "bases"), ENDPOINTS, ids=ENDPOINT_IDS)
@_SHALLOW
@given(data=st.data())
def test_a_bent_path_gets_a_contract_answer(world, method, path, role, bases, data):
    """The path itself, bent: a slash doubled or added, a character spliced in anywhere. Every
    answer under the prefix is still ours — the doubled slash once got Werkzeug's HTML 308."""
    world.restore()
    full = f"/api/v1{_fill(path, world)}"
    index = data.draw(st.integers(1, len(full)), label="at")
    insert = data.draw(
        st.sampled_from(["/", "//", "/.", "/..", "%2F", "%00", "%0A"]) | _TEXT, label="insert"
    )
    quoted = "".join(
        c if c.isascii() and (c.isalnum() or c in "-_.~/%") else _percent(c) for c in insert
    )
    bent = full[:index] + quoted + full[index:]
    if not bent.startswith("/api/v1/"):
        return  # no longer under the prefix, so no longer ours to answer
    body = _fill(bases[0], world) if bases else None
    response = world.client.open(bent, method=method, json=body, headers=_auth(world.token(role)))
    check_response(response, bent, body)
