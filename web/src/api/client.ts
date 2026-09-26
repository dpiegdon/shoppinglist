import type {
  CloseVoteState,
  AdminServerSettings,
  RegistrationStatus,
  AdminUsersResponse,
  ApiErrorBody,
  ListSummary,
  LoginRequest,
  LoginResponse,
  MembersResponse,
  MintInviteResponse,
  PendingInvitesResponse,
  RedeemResponse,
  RegisterRequest,
  RegisterResponse,
  Session,
  Settings,
  SyncRequest,
  SyncResponse,
  UpdateSettingsRequest,
} from "./contract";
import { appBasename } from "../lib/appConfig";
import { PROTOCOL_HEADER, PROTOCOL_VERSION, reportClientOutdated } from "./protocol";
import { safeLocalStorage } from "../lib/safeStorage";

// Resolved per-request against the server-injected mount root, so an instance
// served at e.g. /shopping calls /shopping/api/v1 (T-60). "" in dev = /api/v1.
const apiBase = () => `${appBasename()}/api/v1`;
const TOKEN_STORAGE_KEY = "shoppinglist_token";

export class ApiError extends Error {
  status: number;
  code: string;
  /**
   * The error envelope's extra keys, which some codes carry: `row_id` and `field` on a /sync
   * rejection, and `account_id` on `participant_frozen` (T-157) so the client can say who.
   */
  details: Record<string, unknown>;

  constructor(status: number, code: string, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

// localStorage, not sessionStorage (T-104): sessionStorage dies with the tab,
// which on mobile means every time the browser reclaims a backgrounded tab —
// users were being asked to log in again roughly daily. The session's real
// lifetime is now enforced server-side instead (a 7-day sliding inactivity
// window for web), so the token surviving a browser restart doesn't mean it
// lives forever, and Settings → Sessions can still revoke it.
// safeLocalStorage (T-269) rather than a typeof guard: a browser with site data blocked (or
// private-mode Safari) has a `localStorage` that EXISTS but THROWS on use, which a typeof check
// does not catch — this ran at module load, so it used to crash the whole app before anything
// could render.
let currentToken: string | null = safeLocalStorage.getItem(TOKEN_STORAGE_KEY);

export function getToken(): string | null {
  return currentToken;
}

export function setToken(token: string | null): void {
  currentToken = token;
  if (token) {
    safeLocalStorage.setItem(TOKEN_STORAGE_KEY, token);
  } else {
    safeLocalStorage.removeItem(TOKEN_STORAGE_KEY);
  }
}

// client.ts is a plain module with no access to React context, so a forced
// logout (401 on a request that actually sent a bearer token — mirrors
// Android's ErrorInterceptor/SessionEvents.notifyForcedLogout) is reported
// through this subscriber seam instead. AuthContext registers a handler that
// clears the stored account; ProtectedRoute then redirects to /login on its
// own once account state goes null (T-89).
let forcedLogoutHandler: (() => void) | null = null;

export function onForcedLogout(handler: (() => void) | null): void {
  forcedLogoutHandler = handler;
}

interface RequestOptions {
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  // Credential exchanges (/login, /register) must not carry the current
  // session token: /login is reachable while logged in, and a wrong-password
  // 401 there would otherwise look like a revoked session and trip the
  // forced-logout path, wiping a still-valid session. /logout keeps its token
  // (the server needs it to know which session to revoke).
  skipAuth?: boolean;
}

// A request with no timeout at all (T-272): a stalled connection or a proxy that swallows the
// response left it in flight forever, and useSync's in-flight counter never came back down — every
// later refresh was dropped by the skip-if-in-flight guard and the sync indicator sat on
// "Syncing…" for the rest of the page's life. 20s is generous for a mobile network without letting
// a truly dead connection hang around indefinitely.
const REQUEST_TIMEOUT_MS = 20_000;

export async function apiFetch<T>(path: string, options: RequestOptions): Promise<T> {
  // On EVERY request, login and register included (T-240): the server checks the protocol before
  // it authenticates, so a request without the header is refused whoever sends it.
  const headers: Record<string, string> = { [PROTOCOL_HEADER]: String(PROTOCOL_VERSION) };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  const tokenSent = !options.skipAuth && Boolean(currentToken);
  if (tokenSent) {
    headers["Authorization"] = `Bearer ${currentToken}`;
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetch(`${apiBase()}${path}`, {
      method: options.method,
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: controller.signal,
    });
  } catch (err) {
    // A caller sees this exactly like any other network failure (e.g. fetch's own "Failed to
    // fetch" on a dropped connection) — a plain Error, not an ApiError, since no response was ever
    // received to carry a code.
    if (err instanceof DOMException && err.name === "AbortError") {
      throw new Error("Request timed out.");
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get("content-type") ?? "";
  const data = contentType.includes("application/json") ? await response.json() : undefined;

  if (!response.ok) {
    // A 401 on a request that carried a bearer token means the server has
    // rejected the session (revoked, expired, password changed elsewhere —
    // T-45). Requests that sent no token (e.g. a failed /login) are just a
    // normal credential rejection and must not trigger this (mirrors
    // Android's ErrorInterceptor rule).
    if (response.status === 401 && tokenSent) {
      setToken(null);
      forcedLogoutHandler?.();
    }
    // 426 means this bundle is older than the server's protocol (T-240). The session is fine and
    // the request is not at fault, so nothing is cleared here: one automatic reload fetches the
    // current bundle from that same server, and only a second 426 raises the notice.
    if (response.status === 426) {
      reportClientOutdated();
    }
    const body = data as ApiErrorBody | undefined;
    throw new ApiError(
      response.status,
      body?.error ?? "unknown_error",
      body?.message ?? response.statusText,
      (data ?? {}) as Record<string, unknown>,
    );
  }

  return data as T;
}

export function register(body: RegisterRequest): Promise<RegisterResponse> {
  return apiFetch("/register", { method: "POST", body, skipAuth: true });
}

export function login(body: LoginRequest): Promise<LoginResponse> {
  return apiFetch("/login", { method: "POST", body, skipAuth: true });
}

export function logout(): Promise<void> {
  return apiFetch("/logout", { method: "POST" });
}

export function changePassword(body: {
  current_password: string;
  new_password: string;
}): Promise<void> {
  return apiFetch("/account/change-password", { method: "POST", body });
}

export function changeEmail(body: { password: string; new_email: string }): Promise<void> {
  return apiFetch("/account/change-email", { method: "POST", body });
}

/**
 * An id as one path segment (T-316): percent-encoded, so a "/", "?", "#" or "%" in it can never
 * change which route a request reaches. Every id interpolated into a path goes through this.
 */
const seg = (id: string) => encodeURIComponent(id);

export function listSessions(): Promise<{ sessions: Session[] }> {
  return apiFetch("/account/sessions", { method: "GET" });
}

export function revokeSession(id: string): Promise<void> {
  return apiFetch(`/account/sessions/${seg(id)}`, { method: "DELETE" });
}

export function deleteAccount(body: { password: string }): Promise<void> {
  return apiFetch("/account", { method: "DELETE", body });
}

export function getSettings(): Promise<Settings> {
  return apiFetch("/settings", { method: "GET" });
}

export function updateSettings(body: UpdateSettingsRequest): Promise<Settings> {
  return apiFetch("/settings", { method: "PATCH", body });
}

export function getLists(): Promise<{ lists: ListSummary[] }> {
  return apiFetch("/lists", { method: "GET" });
}

export function getMembers(listId: string): Promise<MembersResponse> {
  return apiFetch(`/lists/${seg(listId)}/members`, { method: "GET" });
}

export function leaveList(listId: string): Promise<void> {
  return apiFetch(`/lists/${seg(listId)}/leave`, { method: "POST" });
}

/** Agree to close an expenses list (T-157). It closes when the last current member agrees. */
export function castCloseVote(listId: string): Promise<CloseVoteState> {
  return apiFetch(`/lists/${seg(listId)}/close-votes`, { method: "POST" });
}

export function withdrawCloseVote(listId: string): Promise<CloseVoteState> {
  return apiFetch(`/lists/${seg(listId)}/close-votes`, { method: "DELETE" });
}

export function mintInvite(listId: string, invitedEmail: string): Promise<MintInviteResponse> {
  return apiFetch(`/lists/${seg(listId)}/invites`, {
    method: "POST",
    body: { invited_email: invitedEmail },
  });
}

export function revokeInvite(inviteId: string): Promise<void> {
  return apiFetch(`/invites/${seg(inviteId)}`, { method: "DELETE" });
}

export function redeemInvite(token: string): Promise<RedeemResponse> {
  return apiFetch("/invites/redeem", { method: "POST", body: { token } });
}

/** The invites addressed to the signed-in account that are still open to join (T-233). */
export function getPendingInvites(): Promise<PendingInvitesResponse> {
  return apiFetch("/invites/pending", { method: "GET" });
}

export function sync(body: SyncRequest): Promise<SyncResponse> {
  return apiFetch("/sync", { method: "POST", body });
}

// --- Admin (T-107) ---

/** Public: the EFFECTIVE registration flag (reflects an admin's live override), for the auth page. */
export function getRegistrationStatus(): Promise<RegistrationStatus> {
  return apiFetch("/registration-status", { method: "GET", skipAuth: true });
}

export function getAdminUsers(): Promise<AdminUsersResponse> {
  return apiFetch("/admin/users", { method: "GET" });
}

export function getServerSettings(): Promise<AdminServerSettings> {
  return apiFetch("/admin/server-settings", { method: "GET" });
}

/** Partial (T-315): only the keys given change; the answer carries both current values. */
export function setServerSettings(
  changes: { allow_registration?: boolean; message?: string },
): Promise<AdminServerSettings> {
  return apiFetch("/admin/server-settings", {
    method: "PUT",
    body: changes,
  });
}

/** Step-up: `password` is the ADMIN's own password. Returns the new password once. */
export function adminResetPassword(accountId: string, password: string): Promise<{ password: string }> {
  return apiFetch(`/admin/users/${seg(accountId)}/reset-password`, { method: "POST", body: { password } });
}

/** Step-up: `password` is the ADMIN's own password. */
export function adminDeleteUser(accountId: string, password: string): Promise<void> {
  return apiFetch(`/admin/users/${seg(accountId)}`, { method: "DELETE", body: { password } });
}
