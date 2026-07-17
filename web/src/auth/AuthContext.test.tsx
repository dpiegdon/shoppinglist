import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "./AuthContext";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return {
    ...actual,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    setToken: vi.fn(),
    getToken: vi.fn(() => null),
    onForcedLogout: vi.fn(),
  };
});

function Probe() {
  const { account, login, register, logout } = useAuth();
  return (
    <div>
      <span data-testid="account">{account ? account.email : "none"}</span>
      <button onClick={() => login("a@example.com", "pw")}>login</button>
      <button onClick={() => register("a@example.com", "pw")}>register</button>
      <button onClick={() => logout()}>logout</button>
    </div>
  );
}

describe("AuthProvider", () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.mocked(api.getToken).mockReturnValue(null);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("starts logged out", () => {
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    expect(screen.getByTestId("account")).toHaveTextContent("none");
  });

  it("login populates the account and stores the token", async () => {
    vi.mocked(api.login).mockResolvedValue({
      token: "tok",
      account_id: "acc-1",
      email: "a@example.com",
    });

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await userEvent.click(screen.getByText("login"));

    await waitFor(() => expect(screen.getByTestId("account")).toHaveTextContent("a@example.com"));
    expect(api.setToken).toHaveBeenCalledWith("tok");
  });

  it("register calls register then login", async () => {
    vi.mocked(api.register).mockResolvedValue({ account_id: "acc-1" });
    vi.mocked(api.login).mockResolvedValue({
      token: "tok",
      account_id: "acc-1",
      email: "a@example.com",
    });

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await userEvent.click(screen.getByText("register"));

    await waitFor(() => expect(screen.getByTestId("account")).toHaveTextContent("a@example.com"));
    expect(api.register).toHaveBeenCalledWith({ email: "a@example.com", password: "pw" });
  });

  it("logout clears the account even if the API call fails", async () => {
    vi.mocked(api.login).mockResolvedValue({
      token: "tok",
      account_id: "acc-1",
      email: "a@example.com",
    });
    vi.mocked(api.logout).mockRejectedValue(new Error("network error"));

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await userEvent.click(screen.getByText("login"));
    await waitFor(() => expect(screen.getByTestId("account")).toHaveTextContent("a@example.com"));

    await userEvent.click(screen.getByText("logout"));

    await waitFor(() => expect(screen.getByTestId("account")).toHaveTextContent("none"));
    expect(api.setToken).toHaveBeenCalledWith(null);
  });

  it("clears the stored account when the client reports a forced logout (T-89)", async () => {
    vi.mocked(api.login).mockResolvedValue({
      token: "tok",
      account_id: "acc-1",
      email: "a@example.com",
    });
    // Mirror the real registry's last-write-wins semantics so we always hold
    // whatever handler THIS test's AuthProvider registered on mount, immune to
    // call-history ordering (e.g. unmount cleanup calls from earlier tests).
    let forcedLogoutHandler: (() => void) | null = null;
    vi.mocked(api.onForcedLogout).mockImplementation((handler) => {
      forcedLogoutHandler = handler;
    });

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await userEvent.click(screen.getByText("login"));
    await waitFor(() => expect(screen.getByTestId("account")).toHaveTextContent("a@example.com"));
    expect(sessionStorage.getItem("shoppinglist_account")).not.toBeNull();

    // Simulate client.ts invoking the registered handler when a token-bearing
    // request comes back 401 (revoked session, etc.).
    expect(forcedLogoutHandler).not.toBeNull();
    act(() => {
      forcedLogoutHandler?.();
    });

    await waitFor(() => expect(screen.getByTestId("account")).toHaveTextContent("none"));
    expect(sessionStorage.getItem("shoppinglist_account")).toBeNull();
  });
});
