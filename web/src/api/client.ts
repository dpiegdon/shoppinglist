import type {
  AdminServerSettings,
  AdminUsersResponse,
  ApiErrorBody,
  ListSummary,
  LoginRequest,
  LoginResponse,
  MembersResponse,
  MintInviteResponse,
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

// Resolved per-request against the server-injected mount root, so an instance
// served at e.g. /shopping calls /shopping/api/v1 (T-60). "" in dev = /api/v1.
const apiBase = () => `${appBasename()}/api/v1`;
const TOKEN_STORAGE_KEY = "shoppinglist_token";

export class ApiError extends Error {
  status: number;
  code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

// localStorage, not sessionStorage (T-104): sessionStorage dies with the tab,
// which on mobile means every time the browser reclaims a backgrounded tab —
// users were being asked to log in again roughly daily. The session's real
// lifetime is now enforced server-side instead (a 7-day sliding inactivity
// window for web), so the token surviving a browser restart doesn't mean it
// lives forever, and Settings → Sessions can still revoke it.
let currentToken: string | null =
  typeof localStorage !== "undefined" ? localStorage.getItem(TOKEN_STORAGE_KEY) : null;

export function getToken(): string | null {
  return currentToken;
}

export function setToken(token: string | null): void {
  currentToken = token;
  if (typeof localStorage === "undefined") return;
  if (token) {
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
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

export async function apiFetch<T>(path: string, options: RequestOptions): Promise<T> {
  const headers: Record<string, string> = {};
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  const tokenSent = !options.skipAuth && Boolean(currentToken);
  if (tokenSent) {
    headers["Authorization"] = `Bearer ${currentToken}`;
  }

  const response = await fetch(`${apiBase()}${path}`, {
    method: options.method,
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

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
    const body = data as ApiErrorBody | undefined;
    throw new ApiError(response.status, body?.error ?? "unknown_error", body?.message ?? response.statusText);
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

export function listSessions(): Promise<{ sessions: Session[] }> {
  return apiFetch("/account/sessions", { method: "GET" });
}

export function revokeSession(id: string): Promise<void> {
  return apiFetch(`/account/sessions/${id}`, { method: "DELETE" });
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
  return apiFetch(`/lists/${listId}/members`, { method: "GET" });
}

export function leaveList(listId: string): Promise<void> {
  return apiFetch(`/lists/${listId}/leave`, { method: "POST" });
}

export function mintInvite(listId: string, invitedEmail: string): Promise<MintInviteResponse> {
  return apiFetch(`/lists/${listId}/invites`, {
    method: "POST",
    body: { invited_email: invitedEmail },
  });
}

export function revokeInvite(inviteId: string): Promise<void> {
  return apiFetch(`/invites/${inviteId}`, { method: "DELETE" });
}

export function redeemInvite(token: string): Promise<RedeemResponse> {
  return apiFetch("/invites/redeem", { method: "POST", body: { token } });
}

export function sync(body: SyncRequest): Promise<SyncResponse> {
  return apiFetch("/sync", { method: "POST", body });
}

// --- Admin (T-107) ---

/** Public: the EFFECTIVE registration flag (reflects an admin's live override), for the auth page. */
export function getRegistrationStatus(): Promise<AdminServerSettings> {
  return apiFetch("/registration-status", { method: "GET", skipAuth: true });
}

export function getAdminUsers(): Promise<AdminUsersResponse> {
  return apiFetch("/admin/users", { method: "GET" });
}

export function getServerSettings(): Promise<AdminServerSettings> {
  return apiFetch("/admin/server-settings", { method: "GET" });
}

export function setServerSettings(allowRegistration: boolean): Promise<AdminServerSettings> {
  return apiFetch("/admin/server-settings", {
    method: "PUT",
    body: { allow_registration: allowRegistration },
  });
}

/** Step-up: `password` is the ADMIN's own password. Returns the new password once. */
export function adminResetPassword(accountId: string, password: string): Promise<{ password: string }> {
  return apiFetch(`/admin/users/${accountId}/reset-password`, { method: "POST", body: { password } });
}

/** Step-up: `password` is the ADMIN's own password. */
export function adminDeleteUser(accountId: string, password: string): Promise<void> {
  return apiFetch(`/admin/users/${accountId}`, { method: "DELETE", body: { password } });
}
