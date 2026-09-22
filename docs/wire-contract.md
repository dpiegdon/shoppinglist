# Wire Contract

The client/server interface: every endpoint's request and response JSON, the
field-clock shape, and the invite-token format. **This is the authoritative
description** — the server implements it, and both clients (`web/`, `android/`)
consume it. Change it here and in both clients together.

Verified against the implementation in `server/src/shoppinglist_server/`.

## Conventions

- **Base path.** Everything in the API tables below is relative to the API root,
  which defaults to `<base_url path>/api/v1` (override with `url_prefix`). The
  site-root routes at the bottom deliberately sit *outside* it.
- **Auth.** `Authorization: Bearer <token>` on every endpoint except
  `POST /register`, `POST /login`, `GET /registration-status`,
  `GET /app-version`, and the site-root pages.
- **Protocol.** `X-Client-Protocol: <integer>` on **every** API request,
  including `POST /register` and `POST /login`. A client older than the server
  is refused with `426 client_outdated` before anything else happens — see
  "Protocol version". The site-root pages and `GET /app-version` do not need it.
- **Error envelope.** Every non-2xx JSON response is
  `{"error": "<code>", "message": "<text>"}`, sometimes with extra keys (never
  shadowing those two) — e.g. `row_id`/`field` on a `/sync` validation failure.
  `message` is English; clients translate by `error`. Every code is listed
  under "Error codes" at the end.
- **Timestamps** are integer milliseconds since the Unix epoch.
- **Caching.** Every response carries `Cache-Control: no-store` unless the route
  chose otherwise (the hashed `/assets/*` bundle and the APK are cacheable, the
  SPA index is `no-cache`). API responses must never enter a shared cache: some
  carry personal data, and `POST /login` and the admin password reset carry a
  credential in the body.
- **Congestion.** Any endpoint may answer `503 server_busy` with `Retry-After`
  when the database is momentarily locked by another writer. It is always safe to
  retry — nothing was applied.
- **Input caps.** `email` ≤ 254 bytes and may not contain `:`; `password` ≤ 320
  bytes (≥ 8); `device_label` ≤ 128 bytes. Over-cap values are `422`.

## Protocol version

The interface has a single integer version, **3**. Both clients send it on every
API request as `X-Client-Protocol`, and the server compares it with its own
`PROTOCOL_VERSION` (`server/src/shoppinglist_server/protocol.py`,
`web/src/api/protocol.ts`, the Android client's `Protocol.kt` — all three carry
the same number).

| The client sends | The server answers |
|---|---|
| nothing (every client built before 3.0.0) | `426 client_outdated` |
| anything but a plain positive integer (`abc`, `3.0`, `-1`, `0`, an empty value) | `426 client_outdated` |
| a number below the server's | `426 client_outdated` |
| the server's number, or a higher one | the request is served normally |

The refusal is the standard envelope plus the server's version:

```json
{"error": "client_outdated", "message": "...", "protocol": 3}
```

It is decided before authentication and before the database is opened: nothing is
read, written, swept or audited, and a valid bearer token neither helps nor is
harmed. The only exemptions are `GET /app-version` — how an outdated app finds
the update that fixes this — and the site-root routes, which a browser opens
with no protocol to declare.

A client **newer** than the server is deliberately not refused: an older server
cannot know what a newer client needs. Upgrade the server before its clients.

**When the number changes.** A change to this contract that an already-installed
client of the previous protocol cannot handle correctly bumps `PROTOCOL_VERSION`,
and the release that ships it is a new **MAJOR** version. Additive changes an old
client simply ignores — a new optional response field, an endpoint it never calls
— do not bump it. The protocol version never exceeds the release's major version,
and a major release with no wire change leaves the protocol alone. `release.sh`
refuses a release that breaks either rule.

| Protocol | Introduced in | What an older client cannot handle |
|---|---|---|
| 3 | 3.0.0 | Ledger entry types. |

## Field clock

Every syncable field value is wrapped with the timestamp and device that last
wrote it. Field-level last-write-wins compares `(updated_at, updated_by)` per
field, so one row can end up with a locally-newer `name` and a remote-newer
`status` after a single sync.

```json
{"value": <field value>, "updated_at": 1751970000000, "updated_by": "<device-uuid>"}
```

`updated_by` is a **device** UUID. It is not an account id — see `last_touched_by`
on items for the account-scoped equivalent.

## Item object

Appears in `POST /sync` payloads; the server always returns full row state.

```json
{
  "id": "item-uuid", "list_id": "list-uuid", "created_at": 1751970000000,
  "fields": {
    "name":     {"value": "Milk", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
    "category": {"value": "groceries", "...": "..."},
    "stores":   {"value": ["Rewe", "Aldi"], "...": "..."},
    "quantity": {"value": "2l", "...": "..."},
    "price":    {"value": {"amount": "1.99", "currency": "EUR"}, "...": "..."},
    "note":     {"value": "the ripe ones", "...": "..."},
    "status":   {"value": "todo", "...": "..."},
    "expense":  {"value": null, "...": "..."},
    "deleted":  {"value": false, "...": "..."}
  },
  "last_touched_by": "account-uuid"
}
```

- `category` / `quantity` / `note` are string-or-null.
- `stores` is an array of strings — the **whole array** is one LWW field.
- `price` is `null` or `{"amount": "<decimal-string>", "currency": "<ISO-4217>"|null}`,
  also one LWW field.
- `status` is `"backlog"`, `"todo"`, or `"checked"`.
- `expense` is `null` except on an `expenses` list, where it is required and
  carries the whole money tuple as **one** LWW field — see "Expenses" below.
- `deleted` is the tombstone, as an LWW boolean field.
- `last_touched_by` is an **account** id, sits *outside* `fields`, and is
  server-maintained — clients never write it. It is set whenever any field-level
  write wins for the item, and is `null` for rows that predate their first
  post-migration edit. Clients use it to attribute changes to a collaborator.

Optional fields may be **absent from a request** (send clocks only for fields you
changed); **responses always carry all 9 fields**.

### Expenses

A list whose `kind` is `expenses` is a **ledger**: every item is one entry in it.
Its title is `name`, which is **not unique** there — several "Dinner at Luigi's"
are several dinners, and the server's same-name merge never runs on such a list.
`note` stays usable; `category`, `stores`, `quantity`, `price` and `status` carry
no meaning.

```json
"expense": {"value": {
  "type":      "expense",
  "paid_by":   {"<account-id>": "40.00", "<account-id>": "24.00"},
  "equal_by":  false,
  "paid_for":  {"<account-id>": "21.34", "<account-id>": "21.33", "<account-id>": "21.33"},
  "equal_for": true,
  "date":      "2026-09-17"
}, "...": "..."}
```

An entry is exactly one of three types, and **every amount stays positive**
whichever it is: the sign lives in the type, never on the wire.

| `type` | meaning | the two maps | effect on account *a*'s balance |
|---|---|---|---|
| `expense` | someone paid for the group | paid by / paid for | `paid_by[a] - paid_for[a]` |
| `income` | the group received money (refund, deposit, sale) | received by / credited to | `-(paid_by[a] - paid_for[a])` |
| `transfer` | one member pays another directly (a settlement) | sender / recipient | `paid_by[a] - paid_for[a]` |

- `type` is one of those three words, lower case. **Absent means `"expense"`** —
  every entry written before the type existed is one — but an explicit `null`,
  another word or another case is refused rather than coerced. The server stores
  the type normalised and fills in `"type": "expense"` when serving a row written
  without one, so a reader always sees it.
- A `transfer` names **exactly one** account in `paid_by` and exactly one in
  `paid_for`, and the two are **different** accounts. (Their amounts match by the
  equal-sum rule below.)
- Both maps are non-empty, and each holds at most **200** participants — far
  beyond any real list, so the cap only blocks bloat. Every amount is a
  **positive** decimal string in the `price` amount format
  (`[0-9]+(\.[0-9]{1,2})?`) — never zero: a participant with no share is absent
  from the map.
- The two maps **sum to the same value**, compared in whole cents. There is no
  stored total; it is the sum of either map.
- Every key is an account id that is a current member of the list, or is already
  present in the item's stored entry (so an entry involving someone who has
  since left stays editable). That check applies only to a write that would win
  last-write-wins; a stale write is discarded without error.
- `equal_by` / `equal_for` record that the map was an equal split, so a client
  reopening the entry redistributes on a changed total. The server stores them
  and does not cross-check them against the amounts.
- `date` is a calendar date, `YYYY-MM-DD`, with no time or zone.
- Unknown keys inside the object are dropped, not stored.

The whole object is **one** LWW field, so the type travels with the amounts it
signs and a client never sees half of a changed entry.

Any violation is `422 invalid_expense` with `row_id` and `field`. An `expense`
value on any other kind of list is refused the same way, whatever its type;
`null` there is fine. Creating an item on an `expenses` list without one, or
nulling it later, is refused too.

## List object

```json
{
  "id": "list-uuid", "created_at": 1751970000000,
  "fields": {
    "name":           {"value": "Groceries", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
    "category_order": {"value": ["groceries", "freezer"], "...": "..."},
    "notes":          {"value": "back door code 1234", "...": "..."},
    "kind":           {"value": "shopping", "...": "..."},
    "currency":       {"value": null, "...": "..."},
    "deleted":        {"value": false, "...": "..."}
  },
  "members": [{"account_id": "account-uuid", "email": "a@example.com", "initials": "AL"}],
  "close_votes": [],
  "closed_at": null
}
```

- `category_order` is an array of strings (whole array = one LWW field).
- `notes` is string-or-null, max 5000 characters.
- `kind` is `"shopping"`, `"checklist"` or `"expenses"`, default `"shopping"`.
  Between shopping and checklist it is purely a **client-side display toggle**: a
  checklist hides the shopping-only item fields (`stores`, `price`, `quantity`),
  but the item schema is identical and no server logic depends on it, so a list
  can flip between those two at any time with no migration.
  `expenses` is different: its items have another shape (see "Expenses" above),
  so the kind is **fixed for the list's whole life** in both directions. Any
  write that would change a kind to or from `expenses` is `422 invalid_field`
  with `row_id` and `field`, whatever its clock.
- `currency` is a free-text label of at most 32 characters — `"EUR"`, `"€"`,
  `"pizza slices"` — deliberately not an ISO code. It is **required and
  non-blank** when creating an `expenses` list and can never be blanked there;
  either way, and over the length cap on any kind, that is
  `422 invalid_list_currency` with `row_id` and `field`. The code is its own, not
  the `invalid_currency` of `PATCH /settings`: that one asks for a 3-letter
  ISO-4217 code, this one does not.
  On an `expenses` list the label is also **fixed for the list's whole life**,
  like the `kind`: every amount already recorded is in its units, so a change
  would silently relabel the ledger. Any write carrying a value different from
  the stored one is `422 invalid_field` with `row_id` and `field`, whatever its
  clock; a write carrying the same value is not a change and is accepted. On
  other kinds the field is permitted, unrendered and freely changed.
- `members`, `close_votes` and `closed_at` sit **outside** `fields` and are
  server-maintained, like an item's `last_touched_by`: clients never write them,
  and a client that sends them has them ignored.
- `members` is the list's current roster, oldest membership first and
  alphabetical within one millisecond. `initials` is already resolved
  (override-or-derived), so no second lookup is needed. Joining, leaving,
  deleting an account, and changing an email or initials each bump the list's
  `change_seq`, so a roster change reaches every device through the ordinary
  incremental sync even though no field value moved. This is what gives both
  clients an offline roster without a cache of their own.
- `close_votes` is the account ids that have agreed to close this expenses list,
  and `closed_at` is when it closed, or `null` while it is open. See "Closing an
  expenses list" below.

## Endpoints

### Auth

| Endpoint | Request body | Success response |
|---|---|---|
| `POST /register` | `{"email", "password"}` | `201 {"account_id"}` |
| `GET /registration-status` | — | `200 {"allow_registration"}` |
| `POST /login` | `{"email", "password", "device_label", "platform"?}` | `200 {"token", "account_id", "email", "is_admin"}` |
| `POST /logout` | — | `204` |

`platform` is optional and selects the session's inactivity window; absent for
older clients, which fall back to the long default. `is_admin` is derived from
the instance's static `admin_emails` config and is never stored.

`POST /register` returns `403 registration_disabled` when registration is
disabled for the instance.

### Account

| Endpoint | Request body | Success response |
|---|---|---|
| `POST /account/change-password` | `{"current_password", "new_password"}` | `204` |
| `POST /account/change-email` | `{"password", "new_email"}` | `204` |
| `GET /account/sessions` | — | `200 {"sessions": [{"id", "device_label", "created_at", "last_seen_at", "current"}]}` |
| `DELETE /account/sessions/{id}` | — | `204` |
| `DELETE /account` | `{"password"}` | `204` |
| `GET /settings` | — | `200 {"default_currency", "initials"}` |
| `PATCH /settings` | `{"default_currency"?, "initials"?}` | `200 {"default_currency", "initials"}` |

`GET /account/sessions` omits idle-expired sessions rather than listing devices
the account can no longer use.

Changing an email, or setting or clearing `initials`, also bumps every list the
account belongs to, because both appear in that list's `members` roster.

`PATCH /settings` is a patch, not a put: an **absent** key means "leave
unchanged", while an explicit `"initials": null` clears the override back to the
value derived from the email. `initials` in a response is always the resolved
default-or-override.

Changing a password revokes all of the account's **other** sessions, keeping only
the one that made the change.

### Lists, members, invites

| Endpoint | Request body | Success response |
|---|---|---|
| `GET /lists` | — | `200 {"lists": [{"id", "name", "category_order"}]}` |
| `GET /lists/{id}/members` | — | `200 {"members": [{"account_id", "email", "initials", "joined_at"}], "invites": [{"id", "invited_email", "expires_at"}]}` |
| `POST /lists/{id}/leave` | — | `204` |
| `POST /lists/{id}/invites` | `{"invited_email"}` | `201 {"invite_id", "token", "url", "expires_at"}` |
| `DELETE /invites/{id}` | — | `204` |
| `POST /invites/redeem` | `{"token"}` | `200 {"list_id"}` |
| `GET /invites/pending` | — | `200 {"invites": [{"id", "list_id", "list_name", "list_kind", "invited_by_initials", "expires_at", "token"}]}` |

`GET /lists` is a convenience summary; the full list state (including `notes` and
`kind`) comes through `POST /sync`.

`GET /lists/{id}/members` returns a uniform `403 not_a_member` whether or not the
list exists, so a non-member cannot probe for existence. `initials` is resolved
server-side so clients rendering the last-touched-by badge need no second lookup.

`GET /invites/pending` is the caller's inbox: every invite addressed to the
account's email (compared case-insensitively) that `POST /invites/redeem` would
still accept — not used, revoked or expired, on a list that exists and is not
closed — and whose list the caller is not already on. Each entry carries the
invite's own `token`, so joining from the overview is an ordinary redeem; the
token admits only this address, which the caller already holds. Oldest first.
An invite is only listed if the account already held the address when the invite
was minted; email addresses are not verified, so an invite to an address that had
no account yet is never offered to whoever registers or renames onto it
afterwards. They still join through the invite link, which `POST /invites/redeem`
accepts unchanged.

### Sync

`POST /sync` — request, then response:

```json
{"cursor": 123, "device_id": "dev-uuid",
 "full_lists": ["list-uuid"],
 "changes": {"lists": [<List object>...], "items": [<Item object>...]}}
```
```json
{"cursor": 456,
 "changes": {"lists": [<List object>...], "items": [<Item object>...]}}
```

`full_lists` and `changes` are both optional. The response contains every row
(full state) with `change_seq >` the request cursor that the caller may see, plus
all live rows of any `full_lists`, plus the new cursor.

- Cursor `0` means initial full sync.
- The returned cursor never covers a row the response left out, but it may lag a
  row the response carried: a write committed elsewhere while the request was
  being served can be delivered and still be above the new cursor, in which case
  the next pull delivers it again. Applying a row twice is a no-op under
  last-write-wins, so clients must tolerate that rather than assume each row
  arrives exactly once.
- A cursor below the server's `gc_horizon` returns `410 full_resync_required`.
  Pushed changes in that same request are still applied first.
- **A push carries at most 250 rows** (`lists` + `items` counted together).
  Beyond that the whole batch is refused with `422 too_many_changes`, carrying
  `max_changes` and — deliberately — **no** `row_id`: no single row is at fault,
  so a client must split the push rather than quarantine anything. Clients drain
  a larger backlog over consecutive requests, lists first (a list must never
  arrive in a later request than items referencing it). The cap exists because
  applying a batch holds SQLite's single write lock for its whole duration.
- **`full_lists` names at most 250 lists**, and is deduplicated before that cap is
  applied — a repeated id is collapsed, and the response carries one snapshot of
  each list either way. Over the cap the request is refused with
  `422 invalid_full_lists`. Each entry costs the server a full snapshot of that
  list, so the pull side is bounded just as a push is. An entry naming a list the
  caller is not a member of returns `403 not_a_member`; pushed changes in that
  same request are still applied first, as with the stale cursor above.
- **A row naming a list the caller cannot write to** gets
  `422 unknown_list` + `row_id` — the *same* answer whether the list does not
  exist or exists but belongs to someone else, so a non-member cannot probe which
  list ids are in use. It is a `422` rather than a `403` so clients quarantine
  just that row (see below) instead of wedging the whole push queue.
- A `422` naming a `row_id` means *that row* is unacceptable: quarantine it,
  keep syncing the rest, and retry it once the user edits it.

### Admin

Admin identity comes from the instance's static `admin_emails` config and cannot
be changed at runtime, so these endpoints can't be used to escalate privilege.
The destructive two re-verify the calling admin's **own** password (step-up).

| Endpoint | Request body | Success response |
|---|---|---|
| `GET /admin/users` | — | `200 {"users": [{"id", "email", "created_at", "session_count", "is_admin"}]}`, by email, case-insensitively |
| `GET /admin/server-settings` | — | `200 {"allow_registration"}` |
| `PUT /admin/server-settings` | `{"allow_registration"}` | `200 {"allow_registration"}` |
| `POST /admin/users/{account_id}/reset-password` | `{"password"}` | `200 {"password"}` |
| `DELETE /admin/users/{account_id}` | `{"password"}` | `204` |

`GET /admin/users` is ordered server-side so both clients agree without sorting
of their own; neither re-orders what it is given. Clients fetch it on request
rather than on opening the console, which is why the registration settings sit
on endpoints of their own.

The `PUT` is a **runtime override** that resets to the config default on restart.
The reset-password response carries the newly generated password, shown once to
the admin and relayed out of band — the same trust model as invite tokens.
Deleting yourself returns `403 cannot_delete_self`; deleting another admin
returns `403 cannot_delete_admin` (remove them from the config instead).

### App package

| Endpoint | Request body | Success response |
|---|---|---|
| `GET /app-version` | — | `200 {"version", "download_url", "protocol"}` |

Unauthenticated, like `/registration-status`: checking for an update is not an
account operation, and the download it points at is public anyway. The Android
client polls it on foreground (rate-limited) and whenever its settings screen
opens, and offers an update when `version` is newer than its own build.

`version` is the **server package's** version, not a value parsed out of the
APK. One built wheel is a single deployable artifact whose parts share one
version number, so the two are the same thing by construction.

`download_url` is **absolute**, built from the instance's `base_url`, so it
survives a prefix mount and can be handed straight to an Android intent. It
points at the site-root `GET /shoppinglist.apk` below.

`protocol` is the server's `PROTOCOL_VERSION`. This endpoint is the one an
outdated client can still reach (see "Protocol version"), so it is where a client
refused with `426` reads what it has to catch up to. It is also unauthenticated
and ungated for exactly that reason.

`404 no_app_package` when this instance serves no APK — because
`serve_android_apk` is off, no APK is packaged, or the server is running from a
source checkout with no installed package version to report. That is also what
every server released before this endpoint existed answers, so a client can
treat "no update information" as one case rather than two.

### Closing an expenses list

| Endpoint | Request body | Success response |
|---|---|---|
| `POST /lists/{id}/close-votes` | — | `200 {"close_votes", "closed_at"}` |
| `DELETE /lists/{id}/close-votes` | — | `200 {"close_votes", "closed_at"}` |

Any member may agree to close at any time, and withdraw while the list is still
open. The list closes the moment the **last current member's** vote lands, in the
same transaction, so a client never sees "everyone agreed but it is still open".
Someone who joins while votes are pending raises the bar; someone who leaves
takes their vote with them, and their departure can itself be what completes the
vote. Both endpoints answer `403 not_a_member` for a list the caller is not on,
whether or not it exists, and `409 not_an_expenses_list` for any other kind.

A **closed** list is a read-only archive:

| Attempt | Answer |
|---|---|
| Any item or list-field write via `/sync` | `422 list_closed` with `row_id` |
| Another close vote, or withdrawing one | `409 list_closed` |
| Minting an invite, or redeeming one minted earlier | `409 list_closed` |
| Leaving | allowed — this is the only way a list is finally let go |

Leaving an **open** expenses list is `409 list_open`: walking away from an
unsettled shared ledger is what closing exists to prevent. Deleting an account is
never refused, whatever lists it is on; the departed id stays in the expenses it
was part of and is rendered as a former member.

An expenses list can never be deleted. The `deleted` tombstone on one is
`422 cannot_delete_expense_list`; when its last member leaves it is orphaned and
tombstoned server-side, exactly as any other list is.

### The freeze

While an expenses list is open, a **frozen** participant's numbers may not move.
Frozen means: has voted to close, or is no longer a member.

> Any write that would change a frozen participant's total paid or total owed on
> an entry is `422 participant_frozen`, carrying `row_id`, `field` and the
> `account_id` in question.

Amounts are compared with the entry type's sign applied (income counts against
an expense; a transfer counts like an expense), and **changing an entry's `type`
counts as changing every account involved in it**, even when the amounts stay
identical: turning an expense into a transfer is refused when any account in it
is frozen, and allowed when none is.

A new row counts as a change from zero and a deletion as a change to zero, so
neither is a way around it — so an entry naming a voter or a former member
cannot be deleted by anyone until the vote is withdrawn. Everything else about
such an entry stays editable by non-voters — its title, its note, and the other
participants' shares — because the rule is about money, not about the row.

The check is applied only to the value that would actually win last-write-wins.
A stale offline write that loses is discarded without error, so a device that was
offline while someone voted does not end up quarantining an innocent edit.

**A voter changes nothing.** The freeze protects a voter's numbers from
everyone else; the other half is that a member who has voted to close may not
change the list at all while it is open — no new expense, not even one between
two other people, no edit or deletion of any expense, and no change to the
list's own fields (name, notes).

> Any expense-list write from a member with a close vote on the list that would
> change something — a new row, or a field that would win last-write-wins — is
> `422 voted_to_close`, carrying `row_id` (the item's, or the list's own id).

422 with the row, as for the freeze, so a device that queued the change offline
before its owner voted parks the row instead of wedging its push queue. As with
the freeze, a stale write that loses is discarded without error rather than
refused. Withdrawing the vote lifts the rule. The check comes before the freeze
check, so a voter hears the rule that applies to them. Voting itself, withdrawing
and leaving go through their own endpoints and are not affected.

### Site-root routes (outside the API prefix)

These live at the mount root rather than under `/api/v1`, because the invite
share URL has no API segment and the web client must boot from the root.

| Route | Purpose |
|---|---|
| `GET /invite/{token}` | HTML invite landing page |
| `GET /shoppinglist.apk` | Android release APK download |
| `GET /` and `GET /<path>` | Embedded web client (SPA fallback) |
| `GET /assets/*` | Hashed web-client bundle, long cache lifetime |
| `GET /favicon.svg` | Web client favicon |
| `GET /<file>` | Any other file at the root of the built web client (what Vite copies from `web/public/`), cacheable like `/assets/*`. A name that is no such file is the SPA fallback. |

`/api/v1/*`, `/invite/<token>`, and `/shoppinglist.apk` all rank above the SPA
catch-all — Werkzeug sorts routes by rule specificity, not registration order.

## Invite token format

```
payload = base64url("<invite_id>:<list_id>:<email>:<expires_at_ms>")
token   = payload + "." + base64url(HMAC_SHA256(INVITE_HMAC_KEY, payload))
```

Share URL: `https://<server>/invite/<token>`. Tokens are **stateless and signed**,
so a link is checkable without server-side invite state. Invites expire 7 days
after minting.

The payload is colon-delimited, so **no component may contain a colon**: neither
the invited email (`422 invalid_email`) nor the list id (`422 invalid_list_id`,
reachable because list ids are client-minted strings). Registration rejects
colon-bearing addresses for the same reason. Redemption grants membership in the
list recorded on the **invite row**, not the one carried by the token — the two
agree, but only the row is authoritative by construction.

## Error codes

Every `error` value the server sends. On `/sync`, a code for one row carries
`row_id` (and `field`, when one field is at fault) whenever the row has a usable
id — the signal to quarantine that row and keep syncing the rest.

| Status | Code | When |
|---|---|---|
| 400 | `invalid_token` | An invite token is malformed or names no invite (`POST /invites/redeem`). |
| 401 | `missing_token` | No bearer token. |
| 401 | `invalid_token` | The bearer token is unknown or revoked. |
| 401 | `session_expired` | The session went unused past its inactivity window. |
| 401 | `invalid_credentials` | Login with a wrong email or password — one answer for both. |
| 403 | `invalid_credentials` | A re-entered password is wrong (account and admin operations that ask for one). |
| 403 | `registration_disabled` | `POST /register` on an instance with registration off. |
| 403 | `not_a_member` | A list the caller is not on, whether or not it exists. |
| 403 | `not_admin` | An admin endpoint, called by someone who is not. |
| 403 | `cannot_delete_self`, `cannot_delete_admin` | See "Admin". |
| 404 | `account_not_found`, `session_not_found`, `invite_not_found` | The id names nothing. |
| 404 | `no_app_package` | See "App package". |
| 409 | `email_taken` | Registering or changing to an address already in use. |
| 409 | `invite_email_mismatch`, `invite_expired`, `invite_revoked`, `invite_used` | Redeeming an invite that is for someone else, too old, withdrawn, or already used. |
| 409 | `list_closed`, `list_open`, `not_an_expenses_list` | See "Closing an expenses list". |
| 410 | `full_resync_required` | See "Sync". |
| 413 | `payload_too_large` | The request body is over the size cap (4 MB by default, `max_content_length`). |
| 422 | `invalid_email`, `invalid_password`, `invalid_device_label`, `invalid_initials`, `invalid_list_id`, `invalid_request` | A request field is out of bounds (see "Input caps"). `invalid_request` also answers a body that parses as JSON but is not an object (`[1]`, `"abc"`, `5`, `true`) — on any route that takes one. |
| 422 | `invalid_currency` | `PATCH /settings` `default_currency` is not a 3-letter uppercase ISO-4217 code. Nothing on `/sync` uses this code. |
| 422 | `invalid_cursor`, `invalid_device_id`, `invalid_full_lists`, `invalid_changes` | A `/sync` request is malformed as a whole. No `row_id`. `invalid_full_lists` also answers more than 250 distinct entries (see "Sync"). |
| 422 | `too_many_changes` | See "Sync". No `row_id`. |
| 422 | `invalid_row`, `missing_list_id`, `unknown_list` | A pushed row has no usable id, `created_at` or `list_id`, or names a list the caller cannot write to. |
| 422 | `invalid_field`, `invalid_name`, `invalid_notes`, `invalid_status`, `invalid_price`, `invalid_expense`, `invalid_list_currency` | A pushed field value breaks its rule (see "Item object", "List object"). A list's `currency` is `invalid_list_currency` — free text, non-blank on an `expenses` list, at most 32 characters. `invalid_expense` covers every rule on a ledger entry: its `type` (one of `expense`, `income`, `transfer`, or absent for `expense`), the transfer's one sender and one different recipient, the positive amounts, the two maps summing alike, the date, the participants, and an entry on a list that is not a ledger. |
| 422 | `list_closed`, `cannot_delete_expense_list`, `participant_frozen`, `voted_to_close` | A pushed row breaks an expenses-list rule (see "Closing an expenses list"). |
| 426 | `client_outdated` | The request declared no `X-Client-Protocol`, a malformed one, or a version below the server's. Carries the server's `protocol`. See "Protocol version". |
| 503 | `server_busy` | See "Conventions". Always safe to retry. |
