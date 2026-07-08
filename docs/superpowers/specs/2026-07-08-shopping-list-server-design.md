# Shopping List — Server & API Design

**Spec #1 of 3** · Date: 2026-07-08 · Status: approved for planning

This document specifies the **server and its API contract only**. It is the
foundation both clients depend on. The Android app and the optional web client
each get their own spec → plan → build cycle afterward.

---

## 1. Goals & scope

Build a client/server shopping-list system.

- **Server:** a Flask **blueprint** with an **SQLite** backend.
- **Clients (later specs):** a native Android app (offline-first), and
  optionally a web app (online-only).

Accounts (email + password) own zero or more shopping **lists**. Lists can be
**shared** by inviting another account. Access is flat: an account either has
access to a list (created it or was invited) or it does not. There is no
ownership hierarchy or role model.

A shopping list is a **registry of every item ever added to it**. Items are
identified to the user by **Name** (unique within a list) and carry a boolean
**TODO** flag meaning "currently on the list". All other fields are optional.

### Decomposition & sequencing

1. **Spec #1 (this doc):** server, SQLite schema, auth, lists, items, sharing,
   and the **sync protocol + API contract**.
2. **Spec #2:** native Android app (Kotlin/Jetpack Compose, Room, offline-first,
   background sync via WorkManager).
3. **Spec #3 (optional):** online-only web client.

The sync protocol is designed here, in the server, because the client contract
depends on it.

---

## 2. Server architecture

- Delivered as an installable Python package exposing a
  **`create_blueprint(config)`** factory *and* a ready-to-mount `blueprint`
  object, so it can be registered into any host Flask app at a configurable
  `url_prefix` (default `/api/v1`).
- Ships a minimal standalone **`app.py`** (Flask app factory) purely for
  local dev and tests.
- Layering, each with one clear responsibility:
  - **routes** — thin HTTP glue: parse/validate request, call a service, shape
    the JSON response. No business logic.
  - **services** — `auth`, `lists`, `sync`, `invites`. All business rules live
    here and are unit-testable without HTTP.
  - **repository** — data access; the only layer that talks SQL.
  - **SQLite** — storage.
- Backend: stdlib **`sqlite3`** with a plain-SQL schema file and thin repository
  functions. Minimal dependencies, honors "SQLite backend", trivially testable
  against a temp DB. (SQLAlchemy is a documented fallback if the ORM is later
  wanted; the repository layer is the seam that would change.)

### Configuration (host-provided via env or factory args)

| Key | Purpose |
|-----|---------|
| `SECRET_KEY` | Flask secret. |
| `INVITE_HMAC_KEY` | Server signing key for invite tokens (§5). |
| `DATABASE_PATH` | SQLite file path. |

---

## 3. Data model

All ids are UUIDs unless noted. Timestamps are UTC.

### accounts
`id`, `email` (UNIQUE, case-insensitive), `password_hash`, `created_at`.
Passwords hashed with `werkzeug.security` (scrypt).

### auth_tokens
`token_hash`, `account_id`, `device_label`, `created_at`, `last_seen_at`.
Opaque bearer tokens, one row per device login, individually revocable. Only the
hash of the token is stored.

### account_settings
`account_id` (PK/FK), `default_currency` (ISO-4217, defaults to a configurable
server default), `updated_at`. One row per account. These are the account-level
preferences that must persist and sync across devices. Currently just
`default_currency`; the table is the extension point for future synced settings.
Purely client-side preferences (e.g. **theme / dark-light**, which auto-follows
the OS) are **not** stored here — they belong to the client spec.

### lists
`id`, `name`, `created_at`, plus sync metadata (§6).
`name` is a human-facing label only; the **UUID is the list's identity** and is
what appears in invite tokens. Lists sync exactly like items: `name` is an
LWW field, and a list carries a tombstone so deletion propagates.

### memberships
`account_id`, `list_id` (composite PK). Flat access model. Creating a list
inserts a membership for the creator; redeeming an invite inserts one for the
invitee.

### items — the crux
Stable identity is **`item_id` (UUID)**, the real primary key, so renames and
independent cross-device additions merge cleanly.

User-facing fields:

| Field | Type | Notes |
|-------|------|-------|
| `name` | string | **Unique within a list.** Editable (rename). |
| `category` | string, optional | e.g. groceries, freezer, asian market, hygiene. Freeform. |
| `stores` | set of strings, optional | Stores where the item is available. Freeform. |
| `quantity` | string, optional | Free text — many possible units. |
| `price_amount` | decimal, optional | Stored as a validated decimal **string** (no float rounding). |
| `price_currency` | string, optional | ISO-4217 code. If omitted, the client displays using the account's `default_currency` (§3 account_settings). |
| `todo` | boolean | Is the item currently on the list? |

`price_amount` + `price_currency` are treated as **one logical LWW field** for
sync (§6) — they move together.

Plus per-item: `list_id`, `created_at`, tombstone fields, and per-field sync
metadata (§6).

**Category & Stores vocabulary:** freeform strings, no fixed enum. Autocomplete
is served from the distinct values already present. On Android those come from
the local mirror, so suggestions work offline.

### invites
`id`, `list_id`, `invited_email`, `expires_at`, `revoked`, `created_by`,
`used_at`. See §5.

---

## 4. Authentication & account management

Opaque bearer tokens, suited to mobile and to per-device revocation.

- `register(email, password)` → creates an account (rejects duplicate email) and
  an `account_settings` row with the server default currency.
- `login(email, password, device_label)` → verifies password, issues a random
  256-bit token, stores its hash, returns the token once.
- Clients send `Authorization: Bearer <token>` on every request.
- `logout` revokes the presented token. Multiple concurrent device logins are
  supported; revoking one does not affect others.

**Account management** (backs the client's settings dialog — the dialog UI and
client-only preferences like theme live in the client spec):

- **Change password** — verifies the current password, re-hashes the new one.
- **Sessions/devices** — list active tokens (device label, last seen) and revoke
  any one (remote logout).
- **Settings** — read/update `account_settings` (currently `default_currency`).
- **Delete account** — removes the account, its tokens, settings, and
  memberships; lists left memberless are tombstoned (as in "leave", §7).

---

## 5. Invite scheme (email-bound, fixed 7-day expiry, revocable)

- The inviter supplies the **invitee's email**. The server creates an **invite
  record** and mints a token encoding
  `list_id + invited_email + expires_at + HMAC(INVITE_HMAC_KEY, list_id | invited_email | expires_at)`.
- **Expiry is fixed at 7 days** from mint time. It is **not** configurable and
  is **never** read from client input — the server always computes
  `expires_at = now + 7 days`. It is carried inside the HMAC payload so it
  cannot be tampered with in transit.
- The token/link is delivered **out of band** by the inviter (the server sends
  no email).
- **Redemption** (`POST /invites/redeem`): a logged-in account may redeem iff
  **all** hold:
  1. the HMAC verifies,
  2. the account's email equals `invited_email` (a forwarded link is useless to
     anyone else),
  3. the invite is **not expired**,
  4. the invite is **not revoked**,
  5. the invite is **not already used**.

  On success: insert a membership and set `used_at` (single-use).
- **Revocation** (`DELETE /invites/{id}`) by a member of the list sets
  `revoked`. Re-issuing simply mints a fresh invite.

---

## 6. Sync protocol (offline-first, field-level last-write-wins)

The Android client keeps a **full local mirror** of every list it can access —
including items not currently marked TODO — which powers both offline use and
instant name autocomplete. Sync reconciles that mirror with the server.

### Cursor — "what to ship"
The server maintains a monotonic integer **`change_seq`**, bumped on every write
to any syncable row (list or item). Each syncable row stores the `change_seq` of
its last change. Clients persist their last-seen cursor. Pulling means "give me
every list and item with `change_seq > cursor`". This is **clock-independent**.
Membership changes (invite redemption, leaving a list) are not LWW content and
are handled by dedicated endpoints (§5, §7) rather than the sync delta.

### Merge — "which value wins"
Conflict resolution is **field-level Last-Write-Wins**:

- Each editable field carries a client-supplied `updated_at` (UTC ms) and
  `updated_by` (device id, used only as a deterministic tiebreak on equal
  timestamps).
- On push, for each field the server keeps the value with the **greatest**
  `updated_at`. Consequence: edits to *different* fields of the same item
  **merge**; edits to the *same* field resolve to the latest. This satisfies
  "merge changes where possible; latest edit wins".
- `stores` is a set and is treated as a **single field** (replace-wins by
  timestamp), not element-level merged.

### Deletes — tombstones
A delete sets `deleted = 1, deleted_at` on the item row rather than removing it,
so deletions propagate on the next sync. Tombstones are garbage-collected after
a retention window comfortably longer than any realistic offline period.

### Name-uniqueness merge
If two devices each add an item with the **same `name`** in the same list while
offline, sync would produce two `item_id`s sharing a name. On sync the server
**merges them into one `item_id`** (field-level LWW across both) and tombstones
the loser, preserving "Name is primary key within a list" without data loss.
The surviving `item_id` is chosen deterministically (earliest `created_at`, then
lexically-smaller `item_id`).

### Transport
- `POST /sync`: body carries the client's cursor plus its local changes since
  that cursor (lists and items with per-field timestamps, and tombstones). The
  server applies LWW + name-merge, then responds with the authoritative set of
  changes the client is missing and a **new cursor**.
- This single endpoint is the **only** write path for list and item content
  (create/rename/delete lists, and item create/edit/delete), so the online and
  offline paths share one code path. `GET /lists` (§7) is a convenience read for
  online clients that don't want to run a full sync.
- **Account settings** are not part of the item/list delta. The Android client
  fetches `GET /settings` at login and caches `default_currency` locally so
  prices render offline; it re-reads after any `PATCH /settings`.

### Known caveat
Wall-clock LWW is sensitive to device clock skew. This is accepted per the
"latest edit wins" rule and documented here; `updated_by` gives deterministic
(if arbitrary) resolution when timestamps tie.

---

## 7. API surface

All JSON, under `/api/v1`. Authenticated requests carry `Authorization: Bearer`.

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/register` | Create account. |
| POST | `/login` | Obtain a bearer token. |
| POST | `/logout` | Revoke the presented token. |
| POST | `/account/change-password` | Change password (verifies current). |
| GET | `/account/sessions` | List active tokens/devices. |
| DELETE | `/account/sessions/{id}` | Revoke a specific session (remote logout). |
| GET | `/settings` | Read account settings (incl. `default_currency`). |
| PATCH | `/settings` | Update account settings. |
| DELETE | `/account` | Delete the account and its data. |
| GET | `/lists` | Convenience read: the caller's accessible lists (online clients). |
| POST | `/lists/{id}/leave` | Remove the caller's membership; if the last member leaves, the list is tombstoned. |
| POST | `/lists/{id}/invites` | Mint an email-bound invite (7-day expiry). |
| DELETE | `/invites/{id}` | Revoke an invite. |
| POST | `/invites/redeem` | Redeem an invite token → membership. |
| POST | `/sync` | Bidirectional delta sync of list + item content across the caller's lists (the sole content write path). |

List create/rename/delete and item create/update/delete are all expressed
**through `/sync`**, not as separate REST write endpoints. Membership actions
(join via redeem, leave) and invite management are the explicit endpoints above.

---

## 8. Error handling

Consistent JSON error envelope: `{ "error": "<code>", "message": "<human text>" }`.

| Status | When |
|--------|------|
| 400 | Malformed request. |
| 401 | Missing/invalid/revoked token. |
| 403 | Authenticated but not a member of the target list. |
| 404 | Unknown resource. |
| 409 | Conflict — duplicate email on register, or invite expired/revoked/already-used/email-mismatch. |
| 422 | Validation failure (e.g. empty item name, malformed sync payload). |

---

## 9. Testing

- **pytest** against a temporary SQLite DB, following TDD.
- **Service unit tests:** password hashing/verification, change-password,
  token issue/revoke + session listing, invite HMAC mint/verify + all five
  redemption conditions + fixed 7-day expiry, field-level LWW merge (incl. the
  combined `price_amount`+`price_currency` field), same-name merge, tombstone
  propagation, cursor monotonicity, settings read/update + default-currency on
  registration, account deletion cascade.
- **API integration tests:** each endpoint incl. auth failures, membership
  enforcement (403), and a full offline→sync→merge round-trip across two
  simulated devices.

---

## 10. Out of scope / assumptions (v1)

- **No email verification and no outbound email.** Invites are delivered out of
  band by the inviter. Because invites are email-bound, this trusts the account's
  email address; email verification is the recommended future hardening.
- **No push / real-time.** Sync is client-initiated (on reconnect, on
  foreground, or manual). The server never pushes.
- **No roles or granular permissions** beyond list membership.
- **No server-side email delivery infrastructure** of any kind.
