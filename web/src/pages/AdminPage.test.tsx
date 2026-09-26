import { render, screen, cleanup, fireEvent, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AdminPage from "./AdminPage";
import * as api from "../api/client";
import type { AdminServerSettings } from "../api/contract";
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
    vi.mocked(api.getServerSettings).mockResolvedValue({ allow_registration: true, message: "" });
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
    vi.mocked(api.setServerSettings).mockResolvedValue({ allow_registration: false, message: "" });
    renderAdmin();

    await showUsers();
    const toggle = await screen.findByRole("switch", { name: "Allow new accounts" });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));

    await userEvent.click(toggle);

    // Only the flag: the PUT is partial and the message is left alone (T-315).
    expect(api.setServerSettings).toHaveBeenCalledWith({ allow_registration: false });
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
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Reset" }));

    expect(await screen.findByText("NEWpw123456")).toBeInTheDocument();
  });

  it("resets a password only after a confirmation naming the user (T-313)", async () => {
    vi.mocked(api.adminResetPassword).mockResolvedValue({ password: "NEWpw123456" });
    renderAdmin();

    await showUsers();
    await userEvent.type(screen.getByLabelText(/Your password/), "adminpw");
    const resetButtons = screen.getAllByRole("button", { name: "Reset password" });
    await userEvent.click(resetButtons[resetButtons.length - 1]);

    // The click opens a confirmation naming the user; nothing is reset yet.
    const dialog = screen.getByRole("dialog", { name: "Reset password?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(
      within(dialog).getByText("Reset the password of u@example.com? Their current password stops working at once."),
    ).toBeInTheDocument();
    expect(api.adminResetPassword).not.toHaveBeenCalled();

    // Cancel sends nothing.
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(api.adminResetPassword).not.toHaveBeenCalled();

    // Confirming does.
    await userEvent.click(resetButtons[resetButtons.length - 1]);
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Reset" }));
    await waitFor(() => expect(api.adminResetPassword).toHaveBeenCalledWith("user-2", "adminpw"));
    expect(api.adminResetPassword).toHaveBeenCalledTimes(1);
  });

  it("asks for the password before it opens the reset confirmation (T-113, T-313)", async () => {
    renderAdmin();
    await showUsers();

    await userEvent.click(screen.getAllByRole("button", { name: "Reset password" })[1]!);

    expect(await screen.findByText(/Enter your password/)).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("copies the new password to the clipboard and says so (T-313)", async () => {
    vi.mocked(api.adminResetPassword).mockResolvedValue({ password: "NEWpw123456" });
    const writeText = vi.fn().mockResolvedValue(undefined);
    renderAdmin();

    await showUsers();
    await userEvent.type(screen.getByLabelText(/Your password/), "adminpw");
    const resetButtons = screen.getAllByRole("button", { name: "Reset password" });
    await userEvent.click(resetButtons[resetButtons.length - 1]);
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Reset" }));
    const shown = await screen.findByText("NEWpw123456");
    // Selectable, in a monospace face, to be read out or pasted exactly.
    expect(shown.tagName).toBe("CODE");
    expect(shown).toHaveStyle({ userSelect: "all" });

    // Installed after the userEvent calls, which put their own clipboard stub in place; fireEvent
    // below does not.
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    fireEvent.click(screen.getByRole("button", { name: "Copy" }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith("NEWpw123456"));
    expect(await screen.findByRole("button", { name: "Copied!" })).toBeInTheDocument();
  });
});

describe("AdminPage loads the user list only when asked (T-221)", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.getServerSettings).mockResolvedValue({ allow_registration: true, message: "" });
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

describe("AdminPage server message (T-315)", () => {
  const INVALID = "The message must be one line of at most 200 characters.";

  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.getServerSettings).mockResolvedValue({ allow_registration: true, message: "Down Sunday" });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("shows the current message and saves only the message, trimmed", async () => {
    vi.mocked(api.setServerSettings).mockResolvedValue({ allow_registration: true, message: "Full, use another" });
    renderAdmin();

    const field = await screen.findByLabelText("Server message");
    await waitFor(() => expect(field).toHaveValue("Down Sunday"));
    expect(screen.getByText("One line, shown to everyone on the login page and above their lists.")).toBeInTheDocument();

    await userEvent.clear(field);
    await userEvent.type(field, "  Full, use another ");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(api.setServerSettings).toHaveBeenCalledWith({ message: "Full, use another" });
    await waitFor(() => expect(field).toHaveValue("Full, use another"));
  });

  it("Clear saves an empty message", async () => {
    vi.mocked(api.setServerSettings).mockResolvedValue({ allow_registration: true, message: "" });
    renderAdmin();

    const field = await screen.findByLabelText("Server message");
    await waitFor(() => expect(field).toHaveValue("Down Sunday"));
    await userEvent.click(screen.getByRole("button", { name: "Clear" }));

    expect(api.setServerSettings).toHaveBeenCalledWith({ message: "" });
    await waitFor(() => expect(field).toHaveValue(""));
  });

  it("refuses a message over 200 characters inline and sends nothing", async () => {
    renderAdmin();

    const field = await screen.findByLabelText("Server message");
    await waitFor(() => expect(field).toHaveValue("Down Sunday"));
    fireEvent.change(field, { target: { value: "a".repeat(201) } });
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(INVALID);
    expect(api.setServerSettings).not.toHaveBeenCalled();
  });

  it("shows the server's 422 invalid_message as the same sentence", async () => {
    vi.mocked(api.setServerSettings).mockRejectedValue(
      new api.ApiError(422, "invalid_message", "message must be one line of at most 200 characters"),
    );
    renderAdmin();

    const field = await screen.findByLabelText("Server message");
    await waitFor(() => expect(field).toHaveValue("Down Sunday"));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(INVALID);
  });

  it("offers no message field on a server from before it", async () => {
    vi.mocked(api.getServerSettings).mockResolvedValue({ allow_registration: true } as unknown as AdminServerSettings);
    renderAdmin();

    await screen.findByRole("switch", { name: "Allow new accounts" });
    await waitFor(() => expect(api.getServerSettings).toHaveBeenCalled());
    expect(screen.queryByLabelText("Server message")).not.toBeInTheDocument();
  });
});
