import { render, screen, fireEvent, waitFor, cleanup } from "@testing-library/react";
import { MemoryRouter, Routes, Route, useLocation } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import LoginPage from "./LoginPage";

// A resolving auth so submitting the form always "succeeds"; we only care where it navigates.
vi.mock("../auth/AuthContext", () => ({
  useAuth: () => ({
    account: null,
    loading: false,
    login: () => Promise.resolve(),
    register: () => Promise.resolve(),
  }),
}));

function LocationProbe() {
  const loc = useLocation();
  return <div data-testid="loc">{loc.pathname + loc.search}</div>;
}

function renderLogin(state?: unknown) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: "/login", state }]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

function submitLogin() {
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: "a@b.c" } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: "password1" } });
  fireEvent.click(screen.getByRole("button", { name: "Log in" }));
}

afterEach(cleanup);

describe("LoginPage destination after login (T-43)", () => {
  it("returns to the preserved location, query string and all", async () => {
    renderLogin({ from: { pathname: "/redeem", search: "?token=abc" } });
    submitLogin();
    await waitFor(() => expect(screen.getByTestId("loc").textContent).toBe("/redeem?token=abc"));
  });

  it("falls back to the overview when there is no preserved location", async () => {
    renderLogin(undefined);
    submitLogin();
    await waitFor(() => expect(screen.getByTestId("loc").textContent).toBe("/"));
  });
});

describe("registration disabled by the server (T-61)", () => {
  afterEach(() => {
    document.head.querySelectorAll('meta[name^="app-"]').forEach((m) => m.remove());
  });

  it("disables the register toggle and shows the notice", () => {
    // The server carries the flag as a meta tag (CSP blocks inline config scripts).
    const meta = document.createElement("meta");
    meta.name = "app-allow-registration";
    meta.content = "false";
    document.head.appendChild(meta);
    renderLogin(undefined);

    expect(screen.getByRole("button", { name: "Need an account? Register" })).toBeDisabled();
    expect(screen.getByText("Registration is disabled on this server.")).toBeInTheDocument();
  });

  it("keeps registration available by default", () => {
    renderLogin(undefined);

    expect(screen.getByRole("button", { name: "Need an account? Register" })).toBeEnabled();
    expect(screen.queryByText("Registration is disabled on this server.")).toBeNull();
  });
});
