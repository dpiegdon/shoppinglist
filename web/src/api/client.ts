import type {
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
} from "./contract";

const API_BASE = "/api/v1";
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

let currentToken: string | null =
  typeof sessionStorage !== "undefined" ? sessionStorage.getItem(TOKEN_STORAGE_KEY) : null;

export function getToken(): string | null {
  return currentToken;
}

export function setToken(token: string | null): void {
  currentToken = token;
  if (typeof sessionStorage === "undefined") return;
  if (token) {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, token);
  } else {
    sessionStorage.removeItem(TOKEN_STORAGE_KEY);
  }
}

interface RequestOptions {
  method: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
}

export async function apiFetch<T>(path: string, options: RequestOptions): Promise<T> {
  const headers: Record<string, string> = {};
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (currentToken) {
    headers["Authorization"] = `Bearer ${currentToken}`;
  }

  const response = await fetch(`${API_BASE}${path}`, {
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
    const body = data as ApiErrorBody | undefined;
    throw new ApiError(response.status, body?.error ?? "unknown_error", body?.message ?? response.statusText);
  }

  return data as T;
}

export function register(body: RegisterRequest): Promise<RegisterResponse> {
  return apiFetch("/register", { method: "POST", body });
}

export function login(body: LoginRequest): Promise<LoginResponse> {
  return apiFetch("/login", { method: "POST", body });
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

export function updateSettings(body: Settings): Promise<Settings> {
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
