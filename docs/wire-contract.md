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
  `POST /register`, `POST /login`, `GET /registration-status`, and the site-root
  pages.
- **Error envelope.** Every non-2xx JSON response is
  `{"error": "<code>", "message": "<text>"}`, sometimes with extra keys (never
  shadowing those two) — e.g. `row_id`/`field` on a `/sync` validation failure.
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
- `deleted` is the tombstone, as an LWW boolean field.
- `last_touched_by` is an **account** id, sits *outside* `fields`, and is
  server-maintained — clients never write it. It is set whenever any field-level
  write wins for the item, and is `null` for rows that predate their first
  post-migration edit. Clients use it to attribute changes to a collaborator.

Optional fields may be **absent from a request** (send clocks only for fields you
changed); **responses always carry all 8 fields**.

## List object

```json
{
  "id": "list-uuid", "created_at": 1751970000000,
  "fields": {
    "name":           {"value": "Groceries", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
    "category_order": {"value": ["groceries", "freezer"], "...": "..."},
    "notes":          {"value": "back door code 1234", "...": "..."},
    "kind":           {"value": "shopping", "...": "..."},
    "deleted":        {"value": false, "...": "..."}
  }
}
```

- `category_order` is an array of strings (whole array = one LWW field).
- `notes` is string-or-null, max 5000 characters.
- `kind` is `"shopping"` or `"checklist"`, default `"shopping"`. It is purely a
  **client-side display toggle**: a checklist hides the shopping-only item fields
  (`stores`, `price`, `quantity`), but the item schema is identical and no server
  logic depends on it, so a list can flip `kind` at any time with no migration.

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

`POST /register` returns `403` when registration is disabled for the instance.

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

`GET /lists` is a convenience summary; the full list state (including `notes` and
`kind`) comes through `POST /sync`.

`GET /lists/{id}/members` returns a uniform `403 not_a_member` whether or not the
list exists, so a non-member cannot probe for existence. `initials` is resolved
server-side so clients rendering the last-touched-by badge need no second lookup.

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
- A cursor below the server's `gc_horizon` returns `410 full_resync_required`.
  Pushed changes in that same request are still applied first.
- **A push carries at most 250 rows** (`lists` + `items` counted together).
  Beyond that the whole batch is refused with `422 too_many_changes`, carrying
  `max_changes` and — deliberately — **no** `row_id`: no single row is at fault,
  so a client must split the push rather than quarantine anything. Clients drain
  a larger backlog over consecutive requests, lists first (a list must never
  arrive in a later request than items referencing it). The cap exists because
  applying a batch holds SQLite's single write lock for its whole duration.
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
| `GET /admin/users` | — | `200 {"users": [{"id", "email", "created_at", "session_count", "is_admin"}]}` |
| `GET /admin/server-settings` | — | `200 {"allow_registration"}` |
| `PUT /admin/server-settings` | `{"allow_registration"}` | `200 {"allow_registration"}` |
| `POST /admin/users/{account_id}/reset-password` | `{"password"}` | `200 {"password"}` |
| `DELETE /admin/users/{account_id}` | `{"password"}` | `204` |

The `PUT` is a **runtime override** that resets to the config default on restart.
The reset-password response carries the newly generated password, shown once to
the admin and relayed out of band — the same trust model as invite tokens.
Deleting yourself returns `403 cannot_delete_self`; deleting another admin
returns `403 cannot_delete_admin` (remove them from the config instead).

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
