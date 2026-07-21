import { render, screen, cleanup, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AdminPage from "./AdminPage";
import * as api from "../api/client";
import { AuthProvider } from "../auth/AuthContext";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return {
    ...actual,
    getToken: vi.fn(() => "tok"),
    onForcedLogout: vi.fn(),
    getAdminUsers: vi.fn(),
    getServerSettings: vi.fn(),
    setServerSettings: vi.fn(),
    adminResetPassword: vi.fn(),
    adminDeleteUser: vi.fn(),
  };
});

function renderAdmin() {
  // Seed a logged-in admin account into storage so AuthProvider hydrates it.
  localStorage.setItem(
    "shoppinglist_account",
    JSON.stringify({ id: "admin-1", email: "boss@example.com", isAdmin: true }),
  );
  return render(
    <MemoryRouter initialEntries={["/admin"]}>
      <AuthProvider>
        <Routes>
          <Route path="/admin" element={<AdminPage />} />
          <Route path="/" element={<div>Home</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("AdminPage (T-107)", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.getServerSettings).mockResolvedValue({ allow_registration: true });
    vi.mocked(api.getAdminUsers).mockResolvedValue({
      users: [
        { id: "admin-1", email: "boss@example.com", created_at: 1, session_count: 1, is_admin: true },
        { id: "user-2", email: "u@example.com", created_at: 2, session_count: 0, is_admin: false },
      ],
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("lists users and toggles registration via the switch", async () => {
    vi.mocked(api.setServerSettings).mockResolvedValue({ allow_registration: false });
    renderAdmin();

    expect(await screen.findByText("u@example.com")).toBeInTheDocument();
    const toggle = await screen.findByRole("switch", { name: "Allow new accounts" });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));

    await userEvent.click(toggle);

    expect(api.setServerSettings).toHaveBeenCalledWith(false);
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "false"));
  });

  it("deletes a user only after a confirmation naming them (T-112)", async () => {
    vi.mocked(api.adminDeleteUser).mockResolvedValue(undefined);
    renderAdmin();

    await screen.findByText("u@example.com");
    await userEvent.type(screen.getByLabelText(/Your password/), "adminpw");
    // First click opens a confirmation dialog — nothing deleted yet.
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(api.adminDeleteUser).not.toHaveBeenCalled();
    expect(screen.getByText("Delete user?")).toBeInTheDocument();

    // Confirm button names the user explicitly.
    await userEvent.click(screen.getByRole("button", { name: "Delete u@example.com" }));

    await waitFor(() => expect(api.adminDeleteUser).toHaveBeenCalledWith("user-2", "adminpw"));
    await waitFor(() => expect(screen.queryByText("u@example.com")).not.toBeInTheDocument());
  });

  it("cancelling the confirmation does not delete", async () => {
    vi.mocked(api.adminDeleteUser).mockResolvedValue(undefined);
    renderAdmin();

    await screen.findByText("u@example.com");
    await userEvent.type(screen.getByLabelText(/Your password/), "adminpw");
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(api.adminDeleteUser).not.toHaveBeenCalled();
    expect(screen.getByText("u@example.com")).toBeInTheDocument();
  });

  it("offers no Delete button for an admin or your own account", async () => {
    renderAdmin();
    await screen.findByText("boss@example.com");
    // Only the one non-admin user (u@example.com) has a Delete button.
    expect(screen.getAllByRole("button", { name: "Delete" })).toHaveLength(1);
  });

  it("shows the reset password result once", async () => {
    vi.mocked(api.adminResetPassword).mockResolvedValue({ password: "NEWpw123456" });
    renderAdmin();

    await screen.findByText("u@example.com");
    await userEvent.type(screen.getByLabelText(/Your password/), "adminpw");
    // The first "Reset password" is the admin's own row; use the non-admin user's.
    const resetButtons = screen.getAllByRole("button", { name: "Reset password" });
    await userEvent.click(resetButtons[resetButtons.length - 1]);

    expect(await screen.findByText("NEWpw123456")).toBeInTheDocument();
  });
});
