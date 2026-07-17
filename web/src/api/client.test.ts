import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiFetch, getToken, login, onForcedLogout, register, setToken } from "./client";

function mockFetchOnce(status: number, body?: unknown, headers?: Record<string, string>) {
  const responseHeaders = new Headers(headers ?? (body !== undefined ? { "content-type": "application/json" } : {}));
  globalThis.fetch = vi.fn().mockResolvedValue({
    status,
    ok: status >= 200 && status < 300,
    headers: responseHeaders,
    json: async () => body,
  } as Response);
}

describe("apiFetch", () => {
  beforeEach(() => {
    setToken(null);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("sends the bearer token when set", async () => {
    setToken("secret-token");
    mockFetchOnce(200, { ok: true });

    await apiFetch("/lists", { method: "GET" });

    const [, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(init.headers.Authorization).toBe("Bearer secret-token");
  });

  it("omits the Authorization header when no token is set", async () => {
    mockFetchOnce(200, { ok: true });

    await apiFetch("/lists", { method: "GET" });

    const [, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(init.headers.Authorization).toBeUndefined();
  });

  it("serializes the body as JSON with a matching Content-Type", async () => {
    mockFetchOnce(200, { ok: true });

    await apiFetch("/register", { method: "POST", body: { email: "a@example.com" } });

    const [, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(init.body).toBe(JSON.stringify({ email: "a@example.com" }));
  });

  it("returns undefined for a 204 response", async () => {
    mockFetchOnce(204);

    const result = await apiFetch("/logout", { method: "POST" });

    expect(result).toBeUndefined();
  });

  it("throws an ApiError with the server's error envelope on failure", async () => {
    mockFetchOnce(409, { error: "email_taken", message: "An account with this email already exists." });

    await expect(apiFetch("/register", { method: "POST", body: {} })).rejects.toMatchObject({
      status: 409,
      code: "email_taken",
      message: "An account with this email already exists.",
    });
  });

  it("throws ApiError as an instance of Error", async () => {
    mockFetchOnce(401, { error: "invalid_credentials", message: "bad" });

    try {
      await apiFetch("/login", { method: "POST", body: {} });
      expect.unreachable();
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect(err).toBeInstanceOf(Error);
    }
  });
});

describe("forced logout on 401 (T-89)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    onForcedLogout(null);
    setToken(null);
  });

  it("clears the token and notifies the forced-logout handler when a token-bearing request gets a 401", async () => {
    setToken("secret-token");
    mockFetchOnce(401, { error: "invalid_token", message: "token revoked" });
    const handler = vi.fn();
    onForcedLogout(handler);

    await expect(apiFetch("/lists", { method: "GET" })).rejects.toBeInstanceOf(ApiError);

    expect(getToken()).toBeNull();
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it("does not notify the forced-logout handler when a 401 comes back with no token sent (e.g. login failure)", async () => {
    mockFetchOnce(401, { error: "invalid_credentials", message: "bad credentials" });
    const handler = vi.fn();
    onForcedLogout(handler);

    await expect(apiFetch("/login", { method: "POST", body: {} })).rejects.toMatchObject({
      status: 401,
      code: "invalid_credentials",
    });

    expect(handler).not.toHaveBeenCalled();
  });

  it("does not wipe a valid session when a logged-in user fails a /login attempt", async () => {
    // /login is reachable while logged in (it is not behind ProtectedRoute), so a
    // credential failure there must not carry the current token — otherwise the
    // 401 would look like a revoked session and force-logout a valid one.
    setToken("still-valid-token");
    mockFetchOnce(401, { error: "invalid_credentials", message: "bad credentials" });
    const handler = vi.fn();
    onForcedLogout(handler);

    await expect(login({ email: "a@example.com", password: "wrong", device_label: "web" })).rejects.toMatchObject({
      status: 401,
      code: "invalid_credentials",
    });

    const [, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(init.headers.Authorization).toBeUndefined();
    expect(getToken()).toBe("still-valid-token");
    expect(handler).not.toHaveBeenCalled();
  });

  it("does not send the current token on /register either", async () => {
    setToken("still-valid-token");
    mockFetchOnce(200, { account_id: "acc-2" });

    await register({ email: "b@example.com", password: "pw" });

    const [, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(init.headers.Authorization).toBeUndefined();
  });

  it("is safe when multiple in-flight token-bearing requests 401 at once (idempotent, no throw)", async () => {
    setToken("secret-token");
    mockFetchOnce(401, { error: "invalid_token", message: "token revoked" });
    const handler = vi.fn();
    onForcedLogout(handler);

    await Promise.all([
      apiFetch("/sync", { method: "POST", body: {} }).catch(() => {}),
      apiFetch("/lists", { method: "GET" }).catch(() => {}),
    ]);

    expect(getToken()).toBeNull();
    expect(handler).toHaveBeenCalledTimes(2);
  });
});

describe("token persistence", () => {
  it("persists the token to sessionStorage and clears it on logout", () => {
    setToken("abc123");
    expect(getToken()).toBe("abc123");
    expect(sessionStorage.getItem("shoppinglist_token")).toBe("abc123");

    setToken(null);
    expect(getToken()).toBeNull();
    expect(sessionStorage.getItem("shoppinglist_token")).toBeNull();
  });
});

describe("login endpoint", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    setToken(null);
  });

  it("posts credentials and returns the parsed response", async () => {
    mockFetchOnce(200, { token: "tok", account_id: "acc-1", email: "a@example.com" });

    const result = await login({ email: "a@example.com", password: "pw", device_label: "web" });

    expect(result).toEqual({ token: "tok", account_id: "acc-1", email: "a@example.com" });
    const [url] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/v1/login");
  });
});
