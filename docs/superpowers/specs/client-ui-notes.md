# Client UI — captured requirements (seed for Spec #2: Android app)

> **Status: raw capture, not yet designed.** These are requirements the user
> stated while we finalized the *server* spec. They are client concerns and do
> **not** affect the server. They exist so nothing is lost when we brainstorm
> the Android client (Spec #2). The server already exposes everything below via
> the sync data model (`status`, fields, `default_currency`).

## Navigation & app shell
- On login, open the **list the user last had open**; if none, show an
  **overview** of their lists to pick one.
- A **menu** gives access to: the lists **overview** and an **account/settings**
  section.
- **Dark / light mode**, auto-selected from system settings.

## User / settings menu
- **Log out**, **show user info** (account email), **select** an existing list,
  and **add** (create) a new list.

## List properties / settings
- **Share** button lives here: shows **who the list is shared with** (members'
  emails) + **pending invites**, lets you **add people** (mint invite), **rename**
  the list, and **unsubscribe** (leave). Backed by `GET /lists/{id}/members`,
  `POST /lists/{id}/invites`, rename via `/sync`, `POST /lists/{id}/leave`.
- Invites are shared as **links** (`https://<server>/invite/<token>`) via the
  Android **share sheet**; the app registers an **App Link** for that path and
  prefills the redeem flow. A **paste-a-code field** is the fallback.
- **Registry view** ("all items"): reachable from the list's menu — browse and
  search every item regardless of status; edit or delete entries (fix typos,
  remove junk).
- **Category order** is configurable here (list-level, shared by all members;
  server field `category_order`). Categories not in the order render after the
  ordered ones, alphabetically.

### Category identity & casing (T-108)
Categories are matched **case-insensitively** — the identity of a category is
`category.trim().toLowerCase()`. Both clients MUST use this same key, or they'd
show different groupings for the same data.

- **Grouping:** items whose categories differ only in case ("Group"/"group")
  merge into one group. The group's displayed label is the **canonical casing**:
  a `category_order` entry that matches the key (case-insensitively) wins;
  otherwise the **most-frequent casing** among the group's items, tie-broken
  lexicographically (deterministic, so both clients agree).
- **Autocomplete:** the add/edit dialog offers the list's existing categories
  (canonical casing) as clickable chips, filtered to those containing the typed
  text (case-insensitive) minus an exact match. This keeps people from minting
  new case-variants.
- **Fixing casing / renaming** happens two ways, both running one shared write —
  rewrite every item whose category key matches the target to the new spelling,
  and update the matching `category_order` entry (de-duplicating on a
  case-insensitive collision, i.e. a merge):
  - **Item dialog:** editing an item's category to the **same word with
    different casing** recases the *whole* category (recasing one item alone is a
    no-op under case-insensitive grouping); a **different word** just
    re-categorizes that one item. The recase-all shows a brief confirmation
    ("Fixed casing for N items in X").
  - **List settings:** the category panel lists the **full** set (items ∪
    order) and renames any entry (a different word too); a collision-merge is
    confirmed first.
- Web renders category headers **verbatim** (no forced uppercase), so the
  user-controlled casing is what shows — matching Android.

## First run / connection
- **Server URL is user-configurable**: entered on the login screen, changeable
  in settings (self-hosted server, no fixed public URL).
- Bearer token stored securely (Android Keystore / EncryptedSharedPreferences).

## List view
- Shows **`todo`** items **grouped by category** (order from `category_order`),
  then listed; items sort **alphabetically within a category**.
- **`checked`** items are **selectively** visible; **`backlog`** items are
  **never** shown here (they only feed name suggestions in the add dialog).
- **List header** contains:
  - an **Add item** button, and
  - a control to **show checked items**.
- **Checked items render with a red strike-through.**
- Each listed item has an **edit button on its right border** → edits **all**
  fields, including **Name**.
- **Clicking elsewhere on an entry marks it done** (`todo` → `checked`), hiding
  it from the default view; a brief **snackbar undo** follows. Tapping a checked
  item (while shown) un-checks it back to `todo`.
- The **edit dialog** also offers **delete** (removes the item from the registry
  entirely — server tombstone).

## Explicitly out of scope (v1)
- Barcode scanning, item photos, recipes/templates, location reminders,
  home-screen widgets, Wear OS, voice input.

## Add-item dialog
- Typing in the **Name** field shows **suggestions from the registry** (all
  existing items, any status).
- Selecting an **existing** item → set `status = todo`, let the user edit the
  other fields.
- Entering a **new** name → create a new registry entry (status `todo`).

## Settings dialog ("the usual stuff")
- **Default currency** (synced via `account_settings`).
- Change password; **change email**; manage sessions/devices (remote logout);
  delete account.
- Theme preference (system / light / dark) — client-only, not server-synced.

## Price rendering
- `price_amount` + `price_currency`; when an item has no currency, render using
  the account's `default_currency`.
