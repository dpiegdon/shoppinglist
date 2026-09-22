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

/** Opens the console and asks for the user list, which no longer loads on its own (T-221). */
async function showUsers() {
  await userEvent.click(await screen.findByRole("button", { name: "Show registered users" }));
  await screen.findByText("u@example.com");
}

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

    await showUsers();
    const toggle = await screen.findByRole("switch", { name: "Allow new accounts" });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));

    await userEvent.click(toggle);

    expect(api.setServerSettings).toHaveBeenCalledWith(false);
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "false"));
  });

  it("deletes a user only after a confirmation naming them (T-112)", async () => {
    vi.mocked(api.adminDeleteUser).mockResolvedValue(undefined);
    renderAdmin();

    await showUsers();
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

  it("the delete confirmation is a modal dialog that Escape cancels, focus going back to Delete (T-283)", async () => {
    renderAdmin();

    await showUsers();
    await userEvent.type(screen.getByLabelText(/Your password/), "adminpw");
    const deleteButton = screen.getByRole("button", { name: "Delete" });
    await userEvent.click(deleteButton);

    const dialog = screen.getByRole("dialog", { name: "Delete user?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toContainElement(document.activeElement as HTMLElement);

    // Tab from the dialog itself lands on its first button and stays inside from there.
    await userEvent.tab();
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    await userEvent.tab();
    await userEvent.tab();
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();

    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(deleteButton).toHaveFocus();
    expect(api.adminDeleteUser).not.toHaveBeenCalled();
  });

  it("says the password is required instead of silently doing nothing (T-113)", async () => {
    renderAdmin();
    await showUsers();

    // No password typed: clicking Delete must explain why nothing happened.
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText(/Enter your password/)).toBeInTheDocument();
    // ...and neither delete nor its confirmation happened.
    expect(api.adminDeleteUser).not.toHaveBeenCalled();
    expect(screen.queryByText("Delete user?")).not.toBeInTheDocument();
  });

  it("says the password is required for a reset too (T-113)", async () => {
    renderAdmin();
    await showUsers();

    await userEvent.click(screen.getAllByRole("button", { name: "Reset password" })[0]!);

    expect(await screen.findByText(/Enter your password/)).toBeInTheDocument();
    expect(api.adminResetPassword).not.toHaveBeenCalled();
  });

  it("clears the password complaint once one is typed (T-113)", async () => {
    renderAdmin();
    await showUsers();
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(await screen.findByText(/Enter your password/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/Your password/), "adminpw");

    expect(screen.queryByText(/Enter your password/)).not.toBeInTheDocument();
  });

  it("cancelling the confirmation does not delete", async () => {
    vi.mocked(api.adminDeleteUser).mockResolvedValue(undefined);
    renderAdmin();

    await showUsers();
    await userEvent.type(screen.getByLabelText(/Your password/), "adminpw");
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(api.adminDeleteUser).not.toHaveBeenCalled();
    expect(screen.getByText("u@example.com")).toBeInTheDocument();
  });

  it("offers no Delete button for an admin or your own account", async () => {
    renderAdmin();
    await showUsers();
    // Only the one non-admin user (u@example.com) has a Delete button.
    expect(screen.getAllByRole("button", { name: "Delete" })).toHaveLength(1);
  });

  it("shows the reset password result once", async () => {
    vi.mocked(api.adminResetPassword).mockResolvedValue({ password: "NEWpw123456" });
    renderAdmin();

    await showUsers();
    await userEvent.type(screen.getByLabelText(/Your password/), "adminpw");
    // The first "Reset password" is the admin's own row; use the non-admin user's.
    const resetButtons = screen.getAllByRole("button", { name: "Reset password" });
    await userEvent.click(resetButtons[resetButtons.length - 1]);

    expect(await screen.findByText("NEWpw123456")).toBeInTheDocument();
  });
});

describe("AdminPage loads the user list only when asked (T-221)", () => {
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

  it("opens with the registration toggle and no users fetched", async () => {
    renderAdmin();

    // The toggle is one value, so it still loads on open.
    const toggle = await screen.findByRole("switch", { name: "Allow new accounts" });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));

    expect(api.getAdminUsers).not.toHaveBeenCalled();
    expect(screen.queryByText("u@example.com")).not.toBeInTheDocument();
    // Nothing to step up for yet either.
    expect(screen.queryByLabelText(/Your password/)).not.toBeInTheDocument();
  });

  it("fetches and shows the list with its count when asked, and refreshes it in place", async () => {
    renderAdmin();
    await screen.findByRole("switch", { name: "Allow new accounts" });

    await userEvent.click(screen.getByRole("button", { name: "Show registered users" }));

    expect(await screen.findByText("u@example.com")).toBeInTheDocument();
    expect(screen.getByText("Registered users: 2")).toBeInTheDocument();
    expect(api.getAdminUsers).toHaveBeenCalledTimes(1);
    // The button is spent: the list is what stands in its place now.
    expect(screen.queryByRole("button", { name: "Show registered users" })).not.toBeInTheDocument();

    vi.mocked(api.getAdminUsers).mockResolvedValue({
      users: [{ id: "admin-1", email: "boss@example.com", created_at: 1, session_count: 1, is_admin: true }],
    });
    await userEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => expect(screen.getByText("Registered users: 1")).toBeInTheDocument());
    expect(screen.queryByText("u@example.com")).not.toBeInTheDocument();
  });
});
