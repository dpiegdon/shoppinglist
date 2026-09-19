import { render, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AppShell from "./AppShell";
import { AuthProvider } from "../auth/AuthContext";
import { en } from "../i18n/messages/en";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return {
    ...actual,
    getToken: vi.fn(() => "tok"),
    onForcedLogout: vi.fn(),
    // The shell mounts the sync provider; nothing here is about syncing, so it just never answers.
    sync: vi.fn(() => new Promise(() => {})),
  };
});

function renderShell(isAdmin: boolean) {
  localStorage.setItem(
    "shoppinglist_account",
    JSON.stringify({ id: "acc-1", email: "boss@example.com", isAdmin }),
  );
  return render(
    <MemoryRouter>
      <AuthProvider>
        <AppShell>
          <div>Page content</div>
        </AppShell>
      </AuthProvider>
    </MemoryRouter>,
  );
}

async function openMenu() {
  await userEvent.click(screen.getByRole("button", { name: en["nav.menu"] }));
}

describe("AppShell menu (T-220, T-224)", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("offers Server admin beside Settings to an admin", async () => {
    renderShell(true);
    await openMenu();

    const entry = screen.getByRole("link", { name: en["nav.serverAdmin"] });
    expect(entry).toHaveAttribute("href", "/admin");
    // Beside Settings, not somewhere else in the menu: it is the entry right after it.
    const labels = screen.getAllByRole("link").map((el) => el.textContent);
    expect(labels.indexOf(en["nav.serverAdmin"])).toBe(labels.indexOf(en["settings.title"]) + 1);
  });

  it("hides Server admin from everyone else", async () => {
    renderShell(false);
    await openMenu();

    expect(screen.getByRole("link", { name: en["settings.title"] })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: en["nav.serverAdmin"] })).not.toBeInTheDocument();
  });

  it("offers About last, right before Log out (T-224)", async () => {
    renderShell(false);
    await openMenu();

    const entry = screen.getByRole("link", { name: en["nav.about"] });
    expect(entry).toHaveAttribute("href", "/about");
    const labels = screen.getAllByRole("link").map((el) => el.textContent);
    // The last link in the menu; Log out is a button after it.
    expect(labels[labels.length - 1]).toBe(en["nav.about"]);
    expect(labels.indexOf(en["nav.about"])).toBe(labels.indexOf(en["settings.title"]) + 1);
  });

  it("keeps Server admin between Settings and About for an admin (T-220, T-224)", async () => {
    renderShell(true);
    await openMenu();

    const labels = screen.getAllByRole("link").map((el) => el.textContent);
    expect(labels.indexOf(en["nav.about"])).toBe(labels.indexOf(en["nav.serverAdmin"]) + 1);
  });
});
