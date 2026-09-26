// Types mirroring the server's Wire Contract exactly.
// See docs/wire-contract.md

export interface FieldClock<T> {
  value: T;
  updated_at: number;
  updated_by: string;
}

export type ItemStatus = "backlog" | "todo" | "checked";

export interface Price {
  amount: string;
  currency: string | null;
}

/**
 * What a ledger entry is (T-245). Every amount on the wire stays positive whichever it is: the
 * sign lives in the type, so an entry is structurally exactly one of the three.
 *
 * - `expense`: someone paid for the group. `paid_by` / `paid_for`.
 * - `income`: the group received money (a refund, a deposit, a sale). The same two maps read
 *   "received by" / "credited to", and the entry counts against what was spent.
 * - `transfer`: one member pays another directly — a settlement. Exactly one sender in `paid_by`
 *   and one different recipient in `paid_for`; it moves a debt without spending anything.
 */
export type ExpenseType = "expense" | "income" | "transfer";

/**
 * The whole money tuple of an expense (T-151), and ONE LWW field: the "both maps sum to the same
 * amount" invariant cannot survive being split across several fields that resolve independently.
 * There is no stored total — it is the sum of either map. `equal_*` record that the map was an
 * equal split, so reopening the form redistributes on a changed total instead of erroring.
 * Amounts are positive decimal strings, and a participant with no share is absent, never zero.
 */
export interface Expense {
  /**
   * Which of the three an entry is (T-245). Optional here, not on the wire: the server fills it in
   * when serving a row written before the type existed, and a client still defaults an absent one
   * to `expense` defensively — see `entryType` in lib/expenses.
   */
  type?: ExpenseType;
  paid_by: Record<string, string>;
  equal_by: boolean;
  paid_for: Record<string, string>;
  equal_for: boolean;
  /** Calendar date, YYYY-MM-DD: no time, no zone. */
  date: string;
}

export interface ItemFields {
  name: FieldClock<string>;
  category: FieldClock<string | null>;
  stores: FieldClock<string[]>;
  quantity: FieldClock<string | null>;
  price: FieldClock<Price | null>;
  note: FieldClock<string | null>;
  status: FieldClock<ItemStatus>;
  /** Null except on an `expenses` list, where every item has one. */
  expense: FieldClock<Expense | null>;
  deleted: FieldClock<boolean>;
}

export interface ItemObject {
  id: string;
  list_id: string;
  created_at?: number;
  fields: Partial<ItemFields>;
  // Whole-item, account-scoped (T-64) — not a per-field LWW clock, so it's not in `fields`. Absent
  // (undefined) on a payload this client builds locally to push; present (string | null) on
  // anything read back from the server.
  last_touched_by?: string | null;
}

/**
 * What a list is (T-110). A checklist is a shopping list minus the shopping-only item fields
 * (stores/price/quantity) — the item schema is identical either way, so this is purely a
 * display toggle and a list can be converted at any time without touching item data.
 *
 * `expenses` (T-151) is not a display toggle: its items carry the `expense` money tuple instead of
 * the shopping fields, their names are not unique, and the server refuses to convert a list to or
 * from this kind for its whole life.
 */
export type ListKind = "shopping" | "checklist" | "expenses";

export interface ListFields {
  name: FieldClock<string>;
  category_order: FieldClock<string[]>;
  notes: FieldClock<string | null>;
  kind: FieldClock<ListKind>;
  /** Free-text label — "EUR", "€", "pizza slices". Required and non-blank on an expenses list. */
  currency: FieldClock<string | null>;
  deleted: FieldClock<boolean>;
}

/** One entry of a list's roster, as carried on the synced list object (T-152). */
export interface ListMember {
  account_id: string;
  email: string;
  /** Already resolved override-or-derived by the server. */
  initials: string;
}

export interface ListObject {
  id: string;
  created_at?: number;
  fields: Partial<ListFields>;
  // Server-maintained, outside `fields` like an item's last_touched_by (T-152): absent on a
  // payload this client builds to push, present on anything read back from the server. The roster
  // rides here so it is available offline, with no separate fetch.
  members?: ListMember[];
  close_votes?: string[];
  closed_at?: number | null;
}

export interface SyncRequest {
  cursor: number;
  device_id: string;
  full_lists: string[];
  changes: {
    lists?: ListObject[];
    items?: ItemObject[];
  };
}

export interface SyncResponse {
  cursor: number;
  changes: {
    lists: ListObject[];
    items: ItemObject[];
  };
  // The admin's one-line server message, or null for none (T-315); on every response, so each
  // sync refreshes it. Optional because a server from before it omits the field: read as null.
  server_message?: string | null;
}

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface RegisterResponse {
  account_id: string;
}

export interface LoginRequest {
  email: string;
  password: string;
  device_label: string;
  // Picks the session's server-side inactivity window (T-104). The server maps
  // this to a duration itself and falls back to its long default for anything
  // it doesn't recognize, so it is a hint, never a duration.
  platform: "web";
}

export interface LoginResponse {
  token: string;
  account_id: string;
  email: string;
  // Whether this account is a configured admin (T-107); drives the admin tab.
  is_admin: boolean;
}

// --- Admin (T-107) ---

export interface AdminUser {
  id: string;
  email: string;
  created_at: number;
  session_count: number;
  is_admin: boolean;
}

export interface AdminUsersResponse {
  users: AdminUser[];
}

export interface AdminServerSettings {
  allow_registration: boolean;
  // The server message (T-315), "" when none. PUT is partial: each key is optional.
  message: string;
}

/** `GET /registration-status`: public, for the login page. */
export interface RegistrationStatus {
  allow_registration: boolean;
  // The server message (T-315), null when none; absent from a server that predates it.
  message?: string | null;
}

/** The server message's cap in code points (T-315); the whole rule is lib/serverMessage.ts (T-316). */
export const SERVER_MESSAGE_MAX_LENGTH = 200;

export interface Session {
  id: string;
  device_label: string | null;
  created_at: number;
  last_seen_at: number;
  current: boolean;
}

export interface Settings {
  default_currency: string;
  // Resolved default-or-override (T-64) — never absent; NULL on the server side just means
  // "derive from email," which the server already does before responding.
  initials: string;
}

// PATCH /settings body: distinct from Settings (the GET/response shape) because the server
// treats an ABSENT initials key as "leave unchanged" (T-87), whereas Settings always carries a
// resolved value. A currency-only save must be able to omit initials entirely when the client
// isn't sure of the current value yet — sending "" would be a real value that clears a custom
// override (T-101). Present-and-"" still overwrites, so omit the key, don't send an empty string.
export interface UpdateSettingsRequest {
  default_currency?: string;
  initials?: string;
}

export interface ListSummary {
  id: string;
  name: string;
  category_order: string[];
}

export interface Member {
  account_id: string;
  email: string;
  initials: string;
  joined_at: number;
}

export interface PendingInvite {
  id: string;
  invited_email: string;
  expires_at: number;
}

export interface MembersResponse {
  members: Member[];
  invites: PendingInvite[];
}

/** An invite waiting for the signed-in account, as the overview offers it (T-233). */
export interface InviteForMe {
  id: string;
  list_id: string;
  list_name: string;
  list_kind: ListKind;
  invited_by_initials: string;
  expires_at: number;
  /** The same token the share URL carries; joining is a plain redeem. */
  token: string;
}

export interface PendingInvitesResponse {
  invites: InviteForMe[];
}

export interface MintInviteResponse {
  invite_id: string;
  token: string;
  url: string;
  expires_at: number;
}

export interface RedeemResponse {
  list_id: string;
}

/** What the close-vote endpoints answer with (T-157). */
export interface CloseVoteState {
  close_votes: string[];
  closed_at: number | null;
}

export interface ApiErrorBody {
  error: string;
  message: string;
}

/**
 * Server cap on rows per /sync push (sync.MAX_CHANGES_PER_SYNC, T-114). Over-cap batches are
 * rejected with `too_many_changes`, so clients split larger pushes — see splitChanges in
 * hooks/useSync.ts. Kept in step with the server by
 * server/tests/test_sync_api.py::test_a_batch_at_the_cap_is_accepted; lowering it here is safe
 * (smaller batches), raising it above the server's value is not.
 */
export const MAX_CHANGES_PER_SYNC = 250;
