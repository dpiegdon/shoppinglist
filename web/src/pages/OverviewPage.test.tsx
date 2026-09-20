import { render, screen, waitFor, cleanup, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import OverviewPage, { IGNORED_INVITES_STORAGE_KEY, LAST_LIST_STORAGE_KEY, _resetInitialResumeForTests } from "./OverviewPage";
import { SyncProvider } from "../hooks/SyncContext";
import { AuthProvider } from "../auth/AuthContext";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return { ...actual, sync: vi.fn(), getSettings: vi.fn(), getPendingInvites: vi.fn(), redeemInvite: vi.fn() };
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
    vi.mocked(api.getPendingInvites).mockResolvedValue({ invites: [] });
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

    await waitFor(() => expect(screen.getByRole("heading", { name: "Overview" })).toBeInTheDocument());
    expect(screen.queryByText("list screen")).not.toBeInTheDocument();
  });

  it("shows the overview directly when there is no last-opened list", async () => {
    _resetInitialResumeForTests();

    renderOverview();

    await waitFor(() => expect(screen.getByRole("heading", { name: "Overview" })).toBeInTheDocument());
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
    vi.mocked(api.getPendingInvites).mockResolvedValue({ invites: [] });
    vi.mocked(api.sync).mockResolvedValue(expensesSyncResponse());
  });

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("shows what was spent, where I stand, and how many expenses there are", async () => {
    renderOverview();

    expect(await screen.findByText("Trip")).toBeInTheDocument();
    expect(screen.getByText(/^CHF\s60\.00$/)).toBeInTheDocument();
    // I paid 60 and owe 30, so the list owes me 30 — a credit, so signed (T-241).
    expect(screen.getByText(/^\+CHF\s30\.00$/)).toBeInTheDocument();
    // One expense on the list, counted as the app counts it (T-191).
    expect(screen.getByText("1")).toBeInTheDocument();
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

// ---- invites waiting for this account (T-233) ---------------------------------

const NOW = Date.now();
const DAY = 24 * 60 * 60 * 1000;

function invite(id: string, listName: string) {
  return {
    id,
    list_id: `list-${id}`,
    list_name: listName,
    list_kind: "shopping" as const,
    invited_by_initials: "AL",
    expires_at: NOW + 5 * DAY + 60_000,
    token: `token-${id}`,
  };
}

describe("OverviewPage pending invites", () => {
  beforeEach(() => {
    localStorage.clear();
    _resetInitialResumeForTests();
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.sync).mockResolvedValue(syncResponse("list-1"));
    vi.mocked(api.getPendingInvites).mockResolvedValue({ invites: [invite("a", "Camping"), invite("b", "Chores")] });
  });

  afterEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    cleanup();
  });

  it("lists them under the lists, with who invited and how long they stand", async () => {
    renderOverview();

    expect(await screen.findByRole("heading", { name: "Invitations" })).toBeInTheDocument();
    expect(screen.getByText("Camping")).toBeInTheDocument();
    expect(screen.getAllByText("From AL · Expires in 5 d")).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: "Join" })).toHaveLength(2);
    expect(screen.queryByRole("heading", { name: "Ignored" })).not.toBeInTheDocument();
    // The section comes after the lists.
    const listCard = screen.getByText("My List");
    const heading = screen.getByRole("heading", { name: "Invitations" });
    expect(listCard.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("moves an ignored invite to the greyed section at the bottom, still joinable, and remembers it", async () => {
    renderOverview();
    await screen.findByRole("heading", { name: "Invitations" });

    const campingCard = screen.getByText("Camping").closest<HTMLElement>(".card")!;
    await userEvent.click(within(campingCard).getByRole("button", { name: "Ignore" }));

    const ignoredHeading = screen.getByRole("heading", { name: "Ignored" });
    const shelved = screen.getByText("Camping").closest<HTMLElement>(".card")!;
    expect(ignoredHeading.compareDocumentPosition(shelved) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(shelved).getByRole("button", { name: "Join" })).toBeInTheDocument();
    expect(within(shelved).queryByRole("button", { name: "Ignore" })).not.toBeInTheDocument();
    // "Chores" is still offered above; the other section lies below it.
    const choresCard = screen.getByText("Chores").closest<HTMLElement>(".card")!;
    expect(choresCard.compareDocumentPosition(ignoredHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(JSON.parse(localStorage.getItem(IGNORED_INVITES_STORAGE_KEY)!)).toEqual(["a"]);
  });

  it("starts an invite out in the ignored section when this browser ignored it before", async () => {
    localStorage.setItem(IGNORED_INVITES_STORAGE_KEY, JSON.stringify(["b"]));
    renderOverview();

    await screen.findByRole("heading", { name: "Ignored" });
    const shelved = screen.getByText("Chores").closest<HTMLElement>(".card")!;
    expect(within(shelved).queryByRole("button", { name: "Ignore" })).not.toBeInTheDocument();
    expect(within(screen.getByText("Camping").closest<HTMLElement>(".card")!).getByRole("button", { name: "Ignore" })).toBeInTheDocument();
  });

  it("joins with the invite's own token, pulls the list and opens it", async () => {
    vi.mocked(api.redeemInvite).mockResolvedValue({ list_id: "list-a" });
    renderOverview();
    await screen.findByRole("heading", { name: "Invitations" });

    const campingCard = screen.getByText("Camping").closest<HTMLElement>(".card")!;
    await userEvent.click(within(campingCard).getByRole("button", { name: "Join" }));

    await waitFor(() => expect(screen.getByText("list screen")).toBeInTheDocument());
    expect(api.redeemInvite).toHaveBeenCalledWith("token-a");
    // The full pull of the newly joined list, as the redeem page does it.
    expect(vi.mocked(api.sync).mock.calls.some((call) => call[0].full_lists.includes("list-a"))).toBe(true);
    expect(localStorage.getItem(LAST_LIST_STORAGE_KEY)).toBe("list-a");
  });

  it("says why a join failed, in the server's words, and re-reads the inbox", async () => {
    vi.mocked(api.redeemInvite).mockRejectedValue(new api.ApiError(409, "invite_revoked", "revoked"));
    renderOverview();
    await screen.findByRole("heading", { name: "Invitations" });
    vi.mocked(api.getPendingInvites).mockResolvedValue({ invites: [invite("b", "Chores")] });

    await userEvent.click(within(screen.getByText("Camping").closest<HTMLElement>(".card")!).getByRole("button", { name: "Join" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("This invite was withdrawn");
    await waitFor(() => expect(screen.queryByText("Camping")).not.toBeInTheDocument());
  });

  it("shows no section at all when the inbox cannot be read", async () => {
    vi.mocked(api.getPendingInvites).mockRejectedValue(new TypeError("Failed to fetch"));
    renderOverview();

    await screen.findByText("My List");
    expect(screen.queryByRole("heading", { name: "Invitations" })).not.toBeInTheDocument();
  });
});
