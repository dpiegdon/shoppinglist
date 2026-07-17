// Types mirroring the server's Wire Contract exactly.
// See docs/superpowers/plans/2026-07-08-shopping-list-tickets.md#wire-contract

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

export interface ItemFields {
  name: FieldClock<string>;
  category: FieldClock<string | null>;
  stores: FieldClock<string[]>;
  quantity: FieldClock<string | null>;
  price: FieldClock<Price | null>;
  note: FieldClock<string | null>;
  status: FieldClock<ItemStatus>;
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

export interface ListFields {
  name: FieldClock<string>;
  category_order: FieldClock<string[]>;
  notes: FieldClock<string | null>;
  deleted: FieldClock<boolean>;
}

export interface ListObject {
  id: string;
  created_at?: number;
  fields: Partial<ListFields>;
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
}

export interface LoginResponse {
  token: string;
  account_id: string;
  email: string;
}

export interface Session {
  id: string;
  device_label: string;
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

export interface MintInviteResponse {
  invite_id: string;
  token: string;
  url: string;
  expires_at: number;
}

export interface RedeemResponse {
  list_id: string;
}

export interface ApiErrorBody {
  error: string;
  message: string;
}
