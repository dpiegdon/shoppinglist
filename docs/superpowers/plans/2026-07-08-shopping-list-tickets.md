# Shopping List — Implementation Tickets

> **For agentic workers:** Each ticket below is a self-contained work package for a
> fresh agent session (Opus or Sonnet — suggested model tagged per ticket). Within
> an epic, use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to run tickets in dependency order. Every ticket is
> executed **TDD**: write the failing test first, run it, implement minimally, run
> green, commit. Steps inside tickets use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A client/server shopping-list system — Flask-blueprint server with SQLite,
offline-first native Android client, and (low-priority) an online-only web client.

**Architecture:** Server = routes → services → repository → SQLite, mounted as a
blueprint; all list/item writes flow through one `/sync` endpoint implementing
field-level last-write-wins. Android = Jetpack Compose + Room full local mirror +
WorkManager sync against the same endpoint. Web = small SPA hitting the same API.

**Tech Stack:** Python 3.11+/Flask 3.x/stdlib `sqlite3`/pytest · Kotlin 2.x/Jetpack
Compose (Material 3)/Room/Retrofit/Hilt/WorkManager · Vite/React/TypeScript.

**Authoritative spec:** `docs/superpowers/specs/2026-07-08-shopping-list-server-design.md`
(server, referenced below as *Spec §N*).
**Client requirements:** `docs/superpowers/specs/client-ui-notes.md` (referenced as *Notes*).
Every ticket executor MUST read the spec/notes sections its ticket cites.

## Global Constraints

- Server: Python ≥ 3.11; runtime deps **Flask only** (Werkzeug comes with it); stdlib `sqlite3`; tests with `pytest`. Package name `shoppinglist_server`, layout `server/src/shoppinglist_server/`.
- Server API mounted at `url_prefix` default **`/api/v1`**; the invite landing page is the only non-JSON route.
- Android: Kotlin ≥ 2.0, minSdk 26, Jetpack Compose + Material 3, Room, Retrofit + kotlinx-serialization, Hilt, WorkManager, DataStore; applicationId `org.p23q.shoppinglist`; single Gradle module `app`.
- Timestamps: integer **UTC milliseconds** since epoch, everywhere.
- UUIDs: lowercase, hyphenated strings.
- Money amounts: **decimal strings** (e.g. `"1.99"`), never floats. Currency: ISO-4217 uppercase (e.g. `"EUR"`).
- Item `status` enum wire values: `"backlog"` | `"todo"` | `"checked"`.
- Invite expiry: fixed **7 days**, server-computed, never client-supplied (Spec §5).
- Tombstone retention: **90 days** (Spec §6).
- Item names unique per list, **case-insensitive** compare, casing preserved (Spec §3).
- Commit after every green test cycle; commit messages `feat:`/`fix:`/`test:`/`docs:` style.

---

## Wire Contract (shared interface — server implements, both clients consume)

Error envelope (every non-2xx JSON response): `{"error": "<code>", "message": "<text>"}`.
Status codes per Spec §8 (incl. `410` → `{"error": "full_resync_required", ...}`).
Auth: `Authorization: Bearer <token>` on everything except register/login/landing page.

**Field clock** — every syncable field value is wrapped as:

```json
{"value": <field value>, "updated_at": 1751970000000, "updated_by": "<device-uuid>"}
```

**Item object** (in sync payloads; server always returns full row state):

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
  }
}
```

Notes: `category`/`quantity`/`note` values are string-or-null; `stores` is an array
of strings (whole array = one LWW field); `price` value is `null` or
`{"amount": "<decimal-string>", "currency": "<ISO-4217>"|null}` (one LWW field);
`deleted` is the tombstone as an LWW boolean field (its `updated_at` is the
spec's `deleted_at`). Optional fields may be absent from a *request* (only changed
fields need clocks); *responses* always carry all 8 fields.

**List object:**

```json
{
  "id": "list-uuid", "created_at": 1751970000000,
  "fields": {
    "name":           {"value": "Groceries", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
    "category_order": {"value": ["groceries", "freezer"], "...": "..."},
    "deleted":        {"value": false, "...": "..."}
  }
}
```

**Endpoints** (paths relative to `/api/v1`):

| Endpoint | Request body | Success response |
|---|---|---|
| `POST /register` | `{"email", "password"}` | `201 {"account_id"}` |
| `POST /login` | `{"email", "password", "device_label"}` | `200 {"token", "account_id", "email"}` |
| `POST /logout` | — | `204` |
| `POST /account/change-password` | `{"current_password", "new_password"}` | `204` |
| `POST /account/change-email` | `{"password", "new_email"}` | `204` |
| `GET /account/sessions` | — | `200 {"sessions": [{"id", "device_label", "created_at", "last_seen_at", "current"}]}` |
| `DELETE /account/sessions/{id}` | — | `204` |
| `DELETE /account` | `{"password"}` | `204` |
| `GET /settings` | — | `200 {"default_currency"}` |
| `PATCH /settings` | `{"default_currency"}` | `200 {"default_currency"}` |
| `GET /lists` | — | `200 {"lists": [{"id", "name", "category_order"}]}` |
| `GET /lists/{id}/members` | — | `200 {"members": [{"email", "joined_at"}], "invites": [{"id", "invited_email", "expires_at"}]}` |
| `POST /lists/{id}/leave` | — | `204` |
| `POST /lists/{id}/invites` | `{"invited_email"}` | `201 {"invite_id", "token", "url", "expires_at"}` |
| `DELETE /invites/{id}` | — | `204` |
| `POST /invites/redeem` | `{"token"}` | `200 {"list_id"}` |
| `GET /invite/{token}` | — | `200` HTML landing page |
| `POST /sync` | see below | see below |

**`POST /sync`** request / response:

```json
{"cursor": 123, "device_id": "dev-uuid",
 "full_lists": ["list-uuid"],
 "changes": {"lists": [<List object>...], "items": [<Item object>...]}}
```
```json
{"cursor": 456,
 "changes": {"lists": [<List object>...], "items": [<Item object>...]}}
```

Request `full_lists`/`changes` optional. Response contains every row (full state)
with `change_seq >` request cursor that the caller may see, plus all live rows of
`full_lists`, and the new cursor. Cursor `0` = initial full sync. Cursor below
`gc_horizon` → `410 full_resync_required` (pushed changes are still applied first).

**Invite token format:** `payload = base64url("<invite_id>:<list_id>:<email>:<expires_at_ms>")`,
`token = payload + "." + base64url(HMAC_SHA256(INVITE_HMAC_KEY, payload))`.
Share URL: `https://<server>/invite/<token>`.

---

## Dependency graph

```
S1 ─┬─ S2 ─┬─ S3
    │      ├─ S5 ── S6 ─┬─ S7
    └─ S4 ─┘            └─ (S9)
         └─ S8 ─────────── S9
A1 ─┬─ A2 ─┬─ A5 ── A6 ── A7 ── A8 ── A11
    └─ A3 ─┼─ A4          └─ A9, A10
           └─ (A9 App Link needs S7 live for e2e; mock until then)
W1 ── W2 ── W3 ── W4        (all W blocked on S-epic complete)
```

Parallelization: S-epic and A1–A4 can run concurrently (Android codes against the
Wire Contract with a mock server). A5+ should wait until S5 exists to integration-test
against the real dev server.

---

# Epic S — Server (Flask blueprint) · priority P1

### S1 — Package scaffold, schema, DB layer, `init-db` · [Sonnet]

**Spec:** §2, §3. **Depends on:** —

**Files:**
- Create: `server/pyproject.toml` (name `shoppinglist-server`, `[project.scripts]` none — CLI registers via Flask), `server/src/shoppinglist_server/__init__.py`, `server/src/shoppinglist_server/schema.sql`, `server/src/shoppinglist_server/db.py`, `server/src/shoppinglist_server/errors.py`, `server/src/shoppinglist_server/cli.py` (init-db only), `server/app.py` (dev host app), `server/tests/conftest.py`, `server/tests/test_db.py`

**Interfaces produces:**
- `create_blueprint(database_path: str, invite_hmac_key: bytes, base_url: str) -> flask.Blueprint` in `__init__.py` (routes filled in by later tickets; S1 wires the skeleton + `errors` handler + per-request connection teardown).
- `db.connect(path) -> sqlite3.Connection` (row_factory=Row, foreign_keys ON, WAL), `db.init_db(conn)` (executes `schema.sql`, idempotent), `db.next_change_seq(conn) -> int` (single-row `meta` table holding `change_seq`, `gc_horizon`, `last_gc_at`; increment atomically).
- `errors.ApiError(status:int, code:str, message:str)` exception + blueprint errorhandler rendering the error envelope.
- Schema tables exactly per Spec §3: `accounts`, `auth_tokens`, `account_settings`, `lists`, `memberships`, `items`, `invites`, `meta`. Items/lists carry per-field `*_ts`/`*_by` columns for every LWW field (items: name, category, stores, quantity, price, note, status, deleted; lists: name, category_order, deleted), plus `change_seq` column, indexed. `stores`/`category_order`/`price` stored as JSON text. Unique index on `items(list_id, lower(name))` **excluded when deleted=1** (partial index).
- `tests/conftest.py` fixtures: `db_conn` (tmp-path SQLite, schema applied), `app`/`client` (Flask test app with blueprint mounted at `/api/v1`).

**Acceptance criteria:**
- [ ] `pip install -e server/[dev]` succeeds; `pytest server/tests -v` green.
- [ ] Tests: schema applies idempotently; `next_change_seq` returns 1,2,3…; duplicate item name (case-insensitive) in same list rejected by the index while a deleted duplicate is allowed; `ApiError` renders `{"error","message"}` JSON with its status.
- [ ] `flask --app server/app.py shoppinglist init-db` creates the DB file.

### S2 — Auth: register / login / logout / bearer middleware · [Sonnet]

**Spec:** §4. **Depends on:** S1

**Files:**
- Create: `server/src/shoppinglist_server/auth.py`, `server/src/shoppinglist_server/routes/auth.py`, `server/tests/test_auth.py`
- Modify: `server/src/shoppinglist_server/__init__.py` (register route module)

**Interfaces produces:**
- `auth.register(conn, email, password) -> account_id` (409 duplicate email case-insensitive; creates `account_settings` row with server default currency `"EUR"`; scrypt via `werkzeug.security.generate_password_hash`).
- `auth.login(conn, email, password, device_label) -> (token, account_id)` — 256-bit `secrets.token_urlsafe`, stores SHA-256 hash.
- `auth.require_account(conn, request) -> Account` — resolves bearer token, updates `last_seen_at`, raises `ApiError(401,…)`; exposed as decorator `@authed` used by all later route tickets, injecting `g.account`.
- Routes: `POST /register`, `POST /login`, `POST /logout` per Wire Contract.

**Acceptance criteria:**
- [ ] Tests: register→login→authed request happy path; wrong password 401; duplicate email 409; revoked token 401 after logout; token is never stored in plaintext (assert DB holds hash ≠ token).

### S3 — Account management & settings · [Sonnet]

**Spec:** §4. **Depends on:** S2

**Files:**
- Create: `server/src/shoppinglist_server/accounts.py`, `server/src/shoppinglist_server/routes/account.py`, `server/tests/test_account.py`
- Modify: `server/src/shoppinglist_server/__init__.py`, `server/src/shoppinglist_server/cli.py` (add `reset-password`)

**Interfaces produces:**
- Routes per Wire Contract: change-password (verifies current, 401 mismatch), change-email (verifies password; 409 taken), sessions list/revoke (`current: true` marks the presented token), `GET/PATCH /settings` (validate ISO-4217: 3 uppercase letters, else 422), `DELETE /account` (verifies password; cascades tokens/settings/memberships; lists left memberless → orphan handling — call `invites.orphan_check(conn, list_id)` from S6; until S6 lands, leave a documented call to a local stub that only deletes memberships).
- CLI `flask shoppinglist reset-password <email>` — prints a generated 16-char password, stores its hash.

**Acceptance criteria:**
- [ ] Tests: each endpoint happy + failure paths listed above; settings default `"EUR"` present right after register; reset-password CLI logs in with the printed password.

### S4 — Sync engine core (pure service, no HTTP) · [Opus]

**Spec:** §6 — read the whole section. **Depends on:** S1

**Files:**
- Create: `server/src/shoppinglist_server/sync.py`, `server/tests/test_sync_engine.py`

**Interfaces produces:**
- `sync.apply_changes(conn, account_id, device_id, changes: dict) -> None` — validates membership per row (403), field-level LWW upsert: for each field keep `(updated_at, updated_by)` max (tuple compare; `updated_by` is the deterministic tiebreak); unknown `list_id` in an item → 422; creating a list via sync inserts membership for the caller; every accepted row bumps `change_seq` via `db.next_change_seq`.
- `sync.name_merge(conn, list_id) -> None` — post-apply: live items sharing `lower(name)` merge into survivor (earliest `created_at`, then smaller `item_id`); per-field LWW across duplicates; loser tombstoned (`deleted=true` clock = now, `updated_by="server-merge"`).
- `sync.delta(conn, account_id, cursor: int, full_lists: list[str]) -> dict` — rows with `change_seq > cursor` restricted to caller's memberships + all live rows of `full_lists` (403 if not member); returns Wire-Contract `{"cursor", "changes"}` with full row state.
- `sync.check_cursor(conn, cursor) -> None` — raises `ApiError(410, "full_resync_required", …)` if `0 < cursor < meta.gc_horizon`.
- Validation helpers: status enum, decimal-string amount (`re: ^\d+(\.\d{1,2})?$`), name non-empty after trim → 422.

**Acceptance criteria:**
- [ ] Tests (pure, via `db_conn` fixture): different-field concurrent edits both survive; same-field latest wins; equal-ts tiebreak by `updated_by`; stores array replace-wins; price amount+currency move as one field; status transition conflict resolves latest; tombstone beats older edit and loses to newer edit (resurrect); same-name merge picks deterministic survivor and merges fields; delta excludes non-member rows; `full_lists` returns rows older than cursor; cursor 0 returns everything visible; 410 below `gc_horizon`.

### S5 — `/sync` + `GET /lists` endpoints · [Sonnet]

**Spec:** §6 transport, §7. **Depends on:** S2, S4

**Files:**
- Create: `server/src/shoppinglist_server/routes/sync.py`, `server/src/shoppinglist_server/routes/lists.py` (GET /lists only), `server/tests/test_sync_api.py`
- Modify: `server/src/shoppinglist_server/__init__.py`

**Interfaces produces:** `POST /sync` and `GET /lists` per Wire Contract: check_cursor → apply_changes → name_merge (touched lists) → delta, in one transaction; on 410 still apply pushed changes first (Spec §6).

**Acceptance criteria:**
- [ ] Integration test "two devices": device A creates list+items via /sync; device B (cursor 0) receives them; both edit offline (A: category, B: quantity + same-field name conflict); after both sync, both mirrors identical and per-field merged. Assert exact JSON field clocks round-trip. 401/403/410/422 paths covered.

### S6 — Sharing: invites, members, leave, orphans · [Opus]

**Spec:** §3 (orphans), §5, §7. **Depends on:** S5

**Files:**
- Create: `server/src/shoppinglist_server/invites.py`, `server/src/shoppinglist_server/routes/invites.py`, `server/tests/test_invites.py`
- Modify: `server/src/shoppinglist_server/__init__.py`, `server/src/shoppinglist_server/routes/lists.py` (members, leave), `server/src/shoppinglist_server/accounts.py` (replace S3's orphan stub with `invites.orphan_check`)

**Interfaces produces:**
- `invites.mint(conn, key, base_url, list_id, invited_email, created_by) -> {"invite_id","token","url","expires_at"}` — expiry **fixed now+7d**; token per Wire Contract format.
- `invites.redeem(conn, key, account, token) -> list_id` — enforces all five Spec §5 conditions (HMAC, email match case-insensitive, not expired, not revoked, not used) → 409 with codes `invite_expired|invite_revoked|invite_used|invite_email_mismatch`, 400 bad token.
- `invites.orphan_check(conn, list_id)` — zero memberships → clear items, tombstone list (Spec §3).
- Routes per Wire Contract: mint (member-only), revoke, redeem, `GET /lists/{id}/members`, `POST /lists/{id}/leave`.

**Acceptance criteria:**
- [ ] Tests: full invite lifecycle mint→redeem→membership→sync delivers list to invitee via `full_lists`; each of the five rejection conditions; forged HMAC 400; revoke; leave; last-leave orphans (items cleared, list tombstoned, other device of same user sees deletion via sync); change-email invalidates pending invite match (ties S3).

### S7 — Invite landing page · [Sonnet]

**Spec:** §5. **Depends on:** S6

**Files:**
- Create: `server/src/shoppinglist_server/routes/landing.py`, `server/src/shoppinglist_server/templates/invite.html`, `server/tests/test_landing.py`
- Modify: `server/src/shoppinglist_server/__init__.py`

**Interfaces produces:** `GET /invite/<token>` — HTML (no auth): valid token → page showing the token in a copyable `<code>` block, "open in app" link `intent://…#Intent;scheme=https;package=org.p23q.shoppinglist;end` fallback, paste instructions; invalid/expired → friendly HTML error (still 200-family? No: 404 for bad HMAC, 410 for expired — assert). No list data leaked beyond what the token itself carries.

**Acceptance criteria:**
- [ ] Tests: valid token renders page containing full token string; tampered token → 404; expired → 410; page is HTML content-type; JSON API prefix unaffected.

### S8 — Tombstone GC (opportunistic + CLI) · [Sonnet]

**Spec:** §2 CLI, §6. **Depends on:** S4

**Files:**
- Create: `server/src/shoppinglist_server/gc.py`, `server/tests/test_gc.py`
- Modify: `server/src/shoppinglist_server/cli.py` (add `gc`), `server/src/shoppinglist_server/routes/sync.py` (opportunistic hook)

**Interfaces produces:** `gc.run(conn, now_ms) -> {"items_purged","lists_purged"}` — hard-deletes rows with `deleted=1` older than 90 days (incl. orphaned tombstoned lists + their items), advances `meta.gc_horizon` to the max purged `change_seq`, sets `last_gc_at`. `gc.maybe_run(conn)` — runs iff `last_gc_at > 24h` ago; called at the top of `/sync`. CLI `flask shoppinglist gc` prints the counts.

**Acceptance criteria:**
- [ ] Tests: fresh tombstones survive; 91-day-old purged; `gc_horizon` advances and stale cursor then gets 410 (ties S4); `maybe_run` no-ops within 24h; CLI runs.

### S9 — Packaging, docs, dev server, full-system test · [Sonnet]

**Spec:** §2, §10. **Depends on:** all S

**Files:**
- Create: `server/README.md`, `server/tests/test_full_system.py`
- Modify: `server/app.py`, `server/pyproject.toml` (final metadata)

**Interfaces produces:** README documenting: mounting the blueprint into a host app (code sample), config table (Spec §2), CLI commands, deployment requirements verbatim from Spec §10 (TLS-terminating reverse proxy mandatory, rate limiting at proxy), wire-contract pointer. `app.py` runs standalone with env-var config.

**Acceptance criteria:**
- [ ] `test_full_system.py`: register 2 accounts → create list → invite → redeem → concurrent offline edits → sync → converged mirrors → leave ×2 → orphan purge via `gc.run`. Entire suite `pytest server/tests -v` green.
- [ ] `flask --app server/app.py run` serves; `curl` register/login round-trip documented in README works.

---

# Epic A — Android client (Kotlin/Compose) · priority P2

*Read Notes (whole file) + Wire Contract before any A-ticket. Package base `org.p23q.shoppinglist`.*

### A1 — Project scaffold, theme, navigation shell · [Sonnet]

**Depends on:** — (parallel with S-epic)

**Files:** Create Gradle project under `android/`: version catalogs (`gradle/libs.versions.toml`), `app/build.gradle.kts` (Compose BOM, Material3, Hilt, Room, Retrofit+kotlinx-serialization, WorkManager, DataStore, security-crypto), `app/src/main/java/org/p23q/shoppinglist/{MainActivity.kt, ShoppingListApp.kt (Hilt), ui/theme/Theme.kt, ui/Nav.kt}`, manifest.

**Interfaces produces:** `Nav.kt` routes: `login`, `overview`, `list/{listId}`, `registry/{listId}`, `listProps/{listId}`, `settings`. Material3 `dynamicColorScheme` with **dark/light following system** (Notes). Top-level scaffold with menu drawer stub (overview + account entries).

**Acceptance criteria:**
- [ ] `./gradlew assembleDebug lint test` green; app launches to a placeholder login screen; theme switches with system dark mode (screenshot both).

### A2 — Room mirror + repositories · [Opus]

**Depends on:** A1

**Files:** Create `data/db/{AppDb.kt, ListEntity.kt, ItemEntity.kt, Daos.kt}`, `data/repo/{ListsRepo.kt, ItemsRepo.kt}`, unit tests under `app/src/test/`.

**Interfaces produces:** Entities mirror the Wire Contract exactly: every LWW field stored as `value` + `updatedAt: Long` + `updatedBy: String` columns (`stores`, `categoryOrder`, `price` as JSON strings); row-level `dirty: Boolean` set on any local edit, cleared by sync push. DAO queries later tickets rely on: `itemsForListByStatus(listId, status)`, `searchRegistry(listId, nameQuery)` (case-insensitive, any status), `distinctCategories(listId)`, `dirtyRows()`. All local edits go through repo methods that stamp `updatedAt = System.currentTimeMillis()`, `updatedBy = deviceId`, `dirty = true` — **field-granular** (e.g. `ItemsRepo.setStatus(itemId, Status.CHECKED)`).

**Acceptance criteria:**
- [ ] Robolectric/Room tests: edit stamps clock + dirty; registry search matches case-insensitively across statuses; status/category queries correct; delete = tombstone field, row retained.

### A3 — API client, auth token store, server-URL setting · [Sonnet]

**Depends on:** A1

**Files:** Create `data/api/{Api.kt (Retrofit interface mirroring the Wire Contract table), Dto.kt (kotlinx-serialization DTOs incl. FieldClock<T>, SyncRequest/Response), AuthInterceptor.kt, ServerConfig.kt}`, `data/SessionStore.kt`; tests with MockWebServer.

**Interfaces produces:** `ServerConfig` (DataStore): `serverUrl`, `deviceId` (UUID minted once); Retrofit rebuilt on URL change. `SessionStore`: token in EncryptedSharedPreferences (Notes), `accountEmail`, `defaultCurrency` cache, `lastOpenedListId`, `syncCursor`. DTO JSON must byte-match the Wire Contract examples (test with fixture strings).

**Acceptance criteria:**
- [ ] MockWebServer tests: auth header injected; 401 propagates a typed `UnauthorizedException`; sync DTO round-trips the contract's Item/List JSON fixtures exactly; error envelope parsed.

### A4 — Login / register / first-run flow · [Sonnet]

**Depends on:** A3

**Files:** Create `ui/login/{LoginScreen.kt, LoginViewModel.kt}` + tests.

**Interfaces produces:** First run: server-URL field (validated https URL, Notes) + email/password + register toggle; on success stores token, fetches `GET /settings` → caches `defaultCurrency`, navigates to `lastOpenedListId ?: overview` (Notes). Logout (from menu) clears SessionStore, keeps Room data for the account? **No** — logout wipes Room DB + cursor (single-account app, avoids cross-account leakage).

**Acceptance criteria:**
- [ ] VM unit tests: happy login, bad password error surfaced, register path, first-run URL persistence, logout wipe. Compose UI smoke test renders.

### A5 — Sync engine + WorkManager · [Opus]

**Depends on:** A2, A3; integration vs. real server needs S5.

**Files:** Create `data/sync/{SyncEngine.kt, SyncWorker.kt}` + tests.

**Interfaces produces:** `SyncEngine.syncNow(fullLists: List<String> = emptyList()): SyncResult` — collect `dirtyRows()` → build SyncRequest (cursor from SessionStore) → apply response with **server-authoritative overwrite** (server already did LWW; local dirty rows edited *after* request snapshot keep dirty), store new cursor, clear pushed dirty flags transactionally. On 410: wipe mirror, resync cursor 0. On 401: surface re-login. Triggers (Notes): connectivity regained (WorkManager network-constraint periodic 15min + expedited one-shot after any local edit, debounced 5s), app foreground, manual pull-to-refresh. Offline edits queue naturally via dirty flags.

**Acceptance criteria:**
- [ ] Tests with MockWebServer: push-only, pull-only, both; 410 full resync; dirty preserved when edit races the request; cursor persisted; worker enqueued on edit. Manual e2e vs. local dev server (S9's `app.py`): two emulators converge — document the run in the ticket PR.

### A6 — Lists overview + menu + user info · [Sonnet]

**Depends on:** A5

**Files:** Create `ui/overview/{OverviewScreen.kt, OverviewViewModel.kt}` + tests; wire drawer menu in `Nav.kt`.

**Interfaces produces:** Overview: cards of non-deleted lists (name), tap → list view (persists `lastOpenedListId`), FAB "new list" (creates locally via repo → sync). Drawer menu everywhere: Overview, Settings/Account (shows logged-in email — Notes "user info"), Logout.

**Acceptance criteria:**
- [ ] VM tests: create list appears + dirty; last-opened persisted. UI test: menu navigates.

### A7 — List view (the core screen) · [Opus]

**Depends on:** A6

**Files:** Create `ui/list/{ListScreen.kt, ListViewModel.kt}` + tests.

**Interfaces produces (all Notes):** `todo` items grouped by category, group order = `category_order` then leftovers alphabetically, items alphabetical within group; header: **Add item** button + **show-checked toggle**; checked items (when shown) render **red strikethrough**; tap row body → `todo→checked` + snackbar undo; tap checked row → back to `todo`; **edit icon on the right border** → edit dialog (A8). Uncategorized items group under "—" last. Price shown as `amount currency`, falling back to cached `defaultCurrency` when item currency null.

**Acceptance criteria:**
- [ ] VM tests: grouping/order matrix (ordered, unordered, uncategorized); toggle logic; undo restores `todo`. Compose test: strikethrough + red color on checked row; edit button hit-target distinct from row toggle.

### A8 — Add-item + edit-item dialogs · [Opus]

**Depends on:** A7

**Files:** Create `ui/item/{AddItemDialog.kt, EditItemDialog.kt, ItemFormViewModel.kt}` + tests.

**Interfaces produces (Notes):** Add: name field with live registry suggestions (any status, case-insensitive); picking one → `status=todo` + other fields editable pre-filled; new name → new item (`status=todo`). Edit: all fields — name (rename, uniqueness pre-checked locally case-insensitively → inline error), category (free text w/ `distinctCategories` suggestions), stores (chip input), quantity, price (decimal keyboard + currency picker defaulting to account currency), note, status; **Delete** action (tombstone) with confirm.

**Acceptance criteria:**
- [ ] VM tests: suggestion filtering; existing-pick sets todo; rename collision blocked; delete tombstones. Compose smoke test both dialogs.

### A9 — Share / list properties + invite redemption + App Link · [Opus]

**Depends on:** A6; e2e needs S6/S7.

**Files:** Create `ui/listprops/{ListPropsScreen.kt, ListPropsViewModel.kt}`, App Link intent-filter in manifest (`https://*/invite/*` + `assetlinks.json` note in README), redeem entry in overview menu ("Join list": paste field) + tests.

**Interfaces produces (Notes):** List properties: rename; **category order editor** (drag-reorder of `distinctCategories` ∪ current order → writes `category_order`); members + pending invites (`GET /lists/{id}/members`, online-only with offline notice); invite by email → share sheet with URL; revoke invite; **Unsubscribe** (leave, confirm dialog, then local list removal). App Link / pasted token → `POST /invites/redeem` → `syncNow(fullLists=[listId])` → open list.

**Acceptance criteria:**
- [ ] VM tests: category reorder persists as LWW edit; redeem triggers full-list sync; leave removes locally. Manual e2e with dev server: invite→link→redeem on second emulator — documented.

### A10 — Settings screen · [Sonnet]

**Depends on:** A4

**Files:** Create `ui/settings/{SettingsScreen.kt, SettingsViewModel.kt}` + tests.

**Interfaces produces (Notes):** Default currency (ISO picker → `PATCH /settings`, updates cache); change password; change email; sessions list w/ revoke (this-device badge); delete account (password + double confirm → wipe + login screen); theme override system/light/dark (client-only, DataStore); server URL display (read-only when logged in); app version.

**Acceptance criteria:**
- [ ] VM tests per action incl. failure surfacing (wrong password 401 → inline error); currency change reflected in A7 price rendering test.

### A11 — Registry view · [Sonnet]

**Depends on:** A8

**Files:** Create `ui/registry/{RegistryScreen.kt, RegistryViewModel.kt}` + tests.

**Interfaces produces (Notes):** From list menu: searchable table of **all** items regardless of status (status chip shown), tap → EditItemDialog (A8), swipe/delete → tombstone with undo snackbar.

**Acceptance criteria:**
- [ ] VM tests: search across statuses; edits/deletes propagate to list view. Full `./gradlew test connectedAndroidTest` (or documented emulator run) green — closes the epic.

---

# Epic W — Web client (online-only) · priority P3 (low)

*Coarse tickets — refine each into a full plan before execution. All depend on Epic S
complete and deployed. Stack: Vite + React + TypeScript, no offline storage; uses the
same Wire Contract incl. `/sync` (cursor kept in localStorage; 410 → drop cursor and
refetch). Lives in `web/`, built assets servable by any static host or the reverse proxy.*

### W1 — Scaffold + API/auth layer · [Sonnet]
Vite+React+TS scaffold in `web/`; typed fetch wrapper implementing the Wire Contract
(share DTO shapes as a generated or hand-written `contract.ts`); login/register/logout
pages; server URL configurable (env + settings); token in memory + `sessionStorage`.
CSS honors `prefers-color-scheme` (dark/light).

### W2 — Lists overview + sync layer · [Sonnet]
`/sync`-based store (in-memory, cursor in localStorage), overview page, create list,
menu with account/overview, last-opened list restore.

### W3 — List view + item dialogs · [Sonnet]
Category-grouped todo view with `category_order`, show-checked toggle with red
strikethrough, tap-to-check + undo, add-item with registry suggestions, edit dialog
(all fields + delete), registry page.

### W4 — Share + settings · [Sonnet]
List properties (rename, category order, members, invite mint/revoke via share URL,
leave); redeem page consuming `/invite/<token>` links (the landing page links here
when opened on desktop — coordinate copy with S7); settings (currency, password,
email, sessions, delete account).

---

## Plan self-review (done at write time)

- **Spec coverage:** every Spec §2–§10 requirement maps to S1–S9; every Notes bullet
  maps to A1–A11/W1–W4 (theme→A1, last-opened→A4/A6, menu→A6, list view rules→A7,
  dialogs→A8, share/invite/category-order/registry→A9/A11, settings→A10, security→A3).
- **Placeholders:** none — every ticket has exact files, interfaces, criteria.
- **Type consistency:** wire shapes defined once (Wire Contract) and referenced;
  server signatures cross-checked S3↔S6 (`orphan_check`), S4↔S5↔S8 (`gc_horizon`,
  `check_cursor`), A2↔A5 (`dirtyRows`), A2↔A7/A8/A11 (DAO names).
