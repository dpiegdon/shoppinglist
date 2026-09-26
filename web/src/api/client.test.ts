import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as client from "./client";
import { ApiError, apiFetch, getToken, login, onForcedLogout, register, setToken, sync } from "./client";
import {
  PROTOCOL_VERSION,
  RELOAD_STORAGE_KEY,
  onClientOutdated,
  resetClientOutdatedForTests,
} from "./protocol";

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

  it("sends an abort signal with every request, so a stalled one can be cancelled (T-272)", async () => {
    mockFetchOnce(200, { ok: true });

    await apiFetch("/lists", { method: "GET" });

    const [, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it("times out a request that never settles, instead of leaving it in flight forever (T-272)", async () => {
    vi.useFakeTimers();
    globalThis.fetch = vi.fn(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener("abort", () => {
            reject(new DOMException("The operation was aborted.", "AbortError"));
          });
        }),
    ) as unknown as typeof fetch;

    const promise = apiFetch("/lists", { method: "GET" });
    const assertion = expect(promise).rejects.toThrow("Request timed out.");

    await vi.advanceTimersByTimeAsync(30_000);
    await assertion;

    vi.useRealTimers();
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

    await expect(login({ email: "a@example.com", password: "wrong", device_label: "web", platform: "web" })).rejects.toMatchObject({
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

describe("the protocol header and 426 client_outdated (T-244)", () => {
  /** Every request's headers, in call order. */
  const sentHeaders = () =>
    (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.map(
      ([, init]) => (init as RequestInit).headers as Record<string, string>,
    );

  beforeEach(() => {
    setToken(null);
    sessionStorage.removeItem(RELOAD_STORAGE_KEY);
    resetClientOutdatedForTests();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    sessionStorage.removeItem(RELOAD_STORAGE_KEY);
    resetClientOutdatedForTests();
  });

  it("goes out on every request, credential exchanges included", async () => {
    // The server checks the protocol BEFORE it authenticates, so login and register need the
    // header exactly as much as a signed-in /sync does.
    mockFetchOnce(200, { ok: true });
    await login({ email: "a@example.com", password: "pw", device_label: "web", platform: "web" });
    await register({ email: "b@example.com", password: "pw" });
    await sync({ cursor: 0, device_id: "dev-1", full_lists: [], changes: {} });
    await apiFetch("/lists", { method: "GET" });

    expect(sentHeaders()).toHaveLength(4);
    for (const headers of sentHeaders()) {
      expect(headers["X-Client-Protocol"]).toBe(String(PROTOCOL_VERSION));
    }
  });

  it("reloads once on a 426, and raises the notice when the next one arrives too soon", async () => {
    const notice = vi.fn();
    onClientOutdated(notice);
    const outdated = () =>
      apiFetch("/sync", { method: "POST", body: {} }).catch((err: unknown) => err);

    mockFetchOnce(426, { error: "client_outdated", message: "too old", protocol: 3 });
    const first = await outdated();

    // The refusal still reaches the caller as an ordinary ApiError with the server's code.
    expect(first).toMatchObject({ status: 426, code: "client_outdated" });
    // The reload itself is jsdom's to refuse; what this level can see is that it was attempted
    // (protocol.test.ts asserts the call), and that no notice was raised yet.
    expect(sessionStorage.getItem(RELOAD_STORAGE_KEY)).not.toBeNull();
    expect(notice).not.toHaveBeenCalled();

    // The reload replaced the page: a fresh module, the same sessionStorage.
    resetClientOutdatedForTests();
    onClientOutdated(notice);
    mockFetchOnce(426, { error: "client_outdated", message: "still too old", protocol: 3 });
    await outdated();

    expect(notice).toHaveBeenCalledTimes(1);
  });

  it("does not touch the session on a 426 — being outdated is not being logged out", async () => {
    setToken("still-valid-token");
    const forcedLogout = vi.fn();
    onForcedLogout(forcedLogout);
    mockFetchOnce(426, { error: "client_outdated", message: "too old", protocol: 3 });

    await expect(apiFetch("/lists", { method: "GET" })).rejects.toBeInstanceOf(ApiError);

    expect(getToken()).toBe("still-valid-token");
    expect(forcedLogout).not.toHaveBeenCalled();
    onForcedLogout(null);
    setToken(null);
  });

  it("leaves every other failure alone — no reload, no notice", async () => {
    const notice = vi.fn();
    onClientOutdated(notice);
    mockFetchOnce(500, { error: "server_error", message: "boom" });

    await expect(apiFetch("/lists", { method: "GET" })).rejects.toBeInstanceOf(ApiError);

    expect(sessionStorage.getItem(RELOAD_STORAGE_KEY)).toBeNull();
    expect(notice).not.toHaveBeenCalled();
  });
});

describe("token persistence", () => {
  it("persists the token to localStorage and clears it on logout", () => {
    setToken("abc123");
    expect(getToken()).toBe("abc123");
    expect(localStorage.getItem("shoppinglist_token")).toBe("abc123");

    setToken(null);
    expect(getToken()).toBeNull();
    expect(localStorage.getItem("shoppinglist_token")).toBeNull();
  });

  it("does not use sessionStorage, which dies with the tab (T-104)", () => {
    // The regression this guards: a backgrounded mobile tab getting reclaimed
    // used to silently drop the session, forcing a fresh login ~daily.
    setToken("abc123");

    expect(sessionStorage.getItem("shoppinglist_token")).toBeNull();
  });

  it("restores a token written by a previous browser session", async () => {
    // Simulates a browser restart: storage survives, module state does not.
    localStorage.setItem("shoppinglist_token", "from-last-time");
    vi.resetModules();

    const freshClient = await import("./client");

    expect(freshClient.getToken()).toBe("from-last-time");
  });
});

describe("login endpoint", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    setToken(null);
  });

  it("posts credentials and returns the parsed response", async () => {
    mockFetchOnce(200, { token: "tok", account_id: "acc-1", email: "a@example.com" });

    const result = await login({ email: "a@example.com", password: "pw", device_label: "web", platform: "web" });

    expect(result).toEqual({ token: "tok", account_id: "acc-1", email: "a@example.com" });
    const [url] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("/api/v1/login");
  });
});

describe("ids in paths are one percent-encoded segment (T-316)", () => {
  const ID = "a/b?c#d%e";
  const ENC = "a%2Fb%3Fc%23d%25e";

  beforeEach(() => setToken("tok"));
  afterEach(() => vi.restoreAllMocks());

  const cases: Array<[string, () => Promise<unknown>, string]> = [
    ["revokeSession", () => client.revokeSession(ID), `/account/sessions/${ENC}`],
    ["getMembers", () => client.getMembers(ID), `/lists/${ENC}/members`],
    ["leaveList", () => client.leaveList(ID), `/lists/${ENC}/leave`],
    ["castCloseVote", () => client.castCloseVote(ID), `/lists/${ENC}/close-votes`],
    ["withdrawCloseVote", () => client.withdrawCloseVote(ID), `/lists/${ENC}/close-votes`],
    ["mintInvite", () => client.mintInvite(ID, "x@example.com"), `/lists/${ENC}/invites`],
    ["revokeInvite", () => client.revokeInvite(ID), `/invites/${ENC}`],
    ["adminResetPassword", () => client.adminResetPassword(ID, "pw"), `/admin/users/${ENC}/reset-password`],
    ["adminDeleteUser", () => client.adminDeleteUser(ID, "pw"), `/admin/users/${ENC}`],
  ];

  for (const [name, call, path] of cases) {
    it(name, async () => {
      mockFetchOnce(200, {});
      await call();
      const [url] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
      expect(url).toBe(`/api/v1${path}`);
    });
  }
});
