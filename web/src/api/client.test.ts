import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiFetch, getToken, login, setToken } from "./client";

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
