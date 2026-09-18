import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import OverviewPage, { LAST_LIST_STORAGE_KEY, _resetInitialResumeForTests } from "./OverviewPage";
import { SyncProvider } from "../hooks/SyncContext";
import { AuthProvider } from "../auth/AuthContext";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return { ...actual, sync: vi.fn(), getSettings: vi.fn() };
});

function syncResponse(listId: string) {
  return {
    cursor: 1,
    changes: {
      lists: [
        {
          id: listId,
          created_at: 0,
          fields: {
            name: { value: "My List", updated_at: 1, updated_by: "dev" },
            category_order: { value: [], updated_at: 1, updated_by: "dev" },
            deleted: { value: false, updated_at: 1, updated_by: "dev" },
          },
        },
      ],
      items: [],
    },
  };
}

function renderOverview() {
  return render(
    <MemoryRouter initialEntries={["/"]}>
      <AuthProvider>
      <SyncProvider>
        <Routes>
          <Route path="/" element={<OverviewPage />} />
          <Route path="/list/:listId" element={<div>list screen</div>} />
        </Routes>
      </SyncProvider>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("OverviewPage last-opened-list resume", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.sync).mockResolvedValue(syncResponse("list-1"));
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("redirects to the last-opened list on first entry (fresh page load)", async () => {
    _resetInitialResumeForTests();
    localStorage.setItem(LAST_LIST_STORAGE_KEY, "list-1");

    renderOverview();

    await waitFor(() => expect(screen.getByText("list screen")).toBeInTheDocument());
  });

  it("does NOT redirect again on a later in-app visit to the overview", async () => {
    _resetInitialResumeForTests();
    localStorage.setItem(LAST_LIST_STORAGE_KEY, "list-1");

    // First render simulates the initial app-entry resume (flips the flag).
    const first = renderOverview();
    await waitFor(() => expect(screen.getByText("list screen")).toBeInTheDocument());
    first.unmount();

    // Second render, WITHOUT resetting the flag, simulates the user
    // clicking back to "/" later in the same session (e.g. "All lists" /
    // the header link) - must show the overview itself, not bounce back to
    // the list again.
    renderOverview();

    await waitFor(() => expect(screen.getByText("Your lists")).toBeInTheDocument());
    expect(screen.queryByText("list screen")).not.toBeInTheDocument();
  });

  it("shows the overview directly when there is no last-opened list", async () => {
    _resetInitialResumeForTests();

    renderOverview();

    await waitFor(() => expect(screen.getByText("Your lists")).toBeInTheDocument());
  });
});

// ---- expenses lists (T-155) --------------------------------------------------

const ME = "acct-me";

function expensesSyncResponse() {
  const clock = <T,>(value: T) => ({ value, updated_at: 1, updated_by: "dev" });
  return {
    cursor: 1,
    changes: {
      lists: [
        {
          id: "trip",
          created_at: 0,
          fields: {
            name: clock("Trip"),
            kind: clock("expenses" as const),
            currency: clock("CHF"),
            category_order: clock([]),
            deleted: clock(false),
          },
          members: [
            { account_id: ME, email: "me@example.com", initials: "ME" },
            { account_id: "acct-other", email: "other@example.com", initials: "OT" },
          ],
          close_votes: [],
          closed_at: null,
        },
      ],
      items: [
        {
          id: "e1",
          list_id: "trip",
          created_at: 0,
          fields: {
            name: clock("Dinner"),
            expense: clock({
              paid_by: { [ME]: "60.00" },
              equal_by: true,
              paid_for: { [ME]: "30.00", "acct-other": "30.00" },
              equal_for: true,
              date: "2026-09-17",
            }),
            deleted: clock(false),
          },
        },
      ],
    },
  };
}

describe("OverviewPage with expenses lists", () => {
  beforeEach(() => {
    localStorage.clear();
    _resetInitialResumeForTests();
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.sync).mockResolvedValue(expensesSyncResponse());
  });

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("shows what was spent and where I stand, instead of an open-item count", async () => {
    renderOverview();

    expect(await screen.findByText("Trip")).toBeInTheDocument();
    expect(screen.getByText("60.00 CHF")).toBeInTheDocument();
    // I paid 60 and owe 30, so the list owes me 30.
    expect(screen.getByText("30.00 CHF")).toBeInTheDocument();
  });

  it("asks for a currency when creating an expenses list, and pushes it", async () => {
    renderOverview();
    await screen.findByText("Trip");

    await userEvent.click(screen.getByRole("button", { name: "New list" }));
    await userEvent.type(screen.getByLabelText("Name"), "Ski trip");

    // No currency field until the kind actually needs one.
    expect(screen.queryByLabelText("Currency")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: /Expenses/ }));
    await userEvent.clear(screen.getByLabelText("Currency"));
    await userEvent.type(screen.getByLabelText("Currency"), "pizza slices");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    const pushed = vi
      .mocked(api.sync)
      .mock.calls.map((call) => call[0])
      .flatMap((request) => request.changes.lists ?? [])
      .at(-1);
    expect(pushed?.fields.kind?.value).toBe("expenses");
    expect(pushed?.fields.currency?.value).toBe("pizza slices");
  });

  it("prefills the account's own currency, so the usual case is one less thing to type", async () => {
    renderOverview();
    await screen.findByText("Trip");

    await userEvent.click(screen.getByRole("button", { name: "New list" }));
    await userEvent.type(screen.getByLabelText("Name"), "Ski trip");
    await userEvent.click(screen.getByRole("radio", { name: /Expenses/ }));
    expect(screen.getByLabelText("Currency")).toHaveValue("EUR");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    const pushed = vi
      .mocked(api.sync)
      .mock.calls.map((call) => call[0])
      .flatMap((request) => request.changes.lists ?? [])
      .at(-1);
    expect(pushed?.fields.currency?.value).toBe("EUR");
  });
});
