import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import BalancesPage from "./BalancesPage";
import ListPropsPage from "./ListPropsPage";
import { today } from "../components/ExpenseDialog";
import ListRoute from "./ListRoute";
import { AuthProvider } from "../auth/AuthContext";
import { SyncProvider } from "../hooks/SyncContext";
import * as api from "../api/client";
import type { Expense } from "../api/contract";
import { ApiError } from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return {
    ...actual,
    sync: vi.fn(),
    getSettings: vi.fn(),
    getMembers: vi.fn(),
    castCloseVote: vi.fn(),
    withdrawCloseVote: vi.fn(),
  };
});

const ME = "acct-me";
const OTHER = "acct-other";

function clock<T>(value: T) {
  return { value, updated_at: 1, updated_by: "dev" };
}

function expenseList(members = [ME, OTHER], closeVotes: string[] = [], closedAt: number | null = null) {
  return {
    id: "list-1",
    created_at: 0,
    fields: {
      name: clock("Trip"),
      kind: clock("expenses" as const),
      currency: clock("EUR"),
      category_order: clock([]),
      deleted: clock(false),
    },
    members: members.map((id) => ({
      account_id: id,
      email: `${id}@example.com`,
      initials: id === ME ? "ME" : "OT",
    })),
    close_votes: closeVotes,
    closed_at: closedAt,
  };
}

function expenseItem(id: string, name: string, expense: Expense, createdAt = 0) {
  return {
    id,
    list_id: "list-1",
    created_at: createdAt,
    fields: {
      name: clock(name),
      note: clock(null),
      expense: clock(expense),
      deleted: clock(false),
    },
  };
}

const dinner: Expense = {
  // equal_by is true for a lone payer covering the whole bill: that IS the equal split of the
  // selected set, and it is what the dialog writes. It is also what makes correcting the total
  // follow along instead of asking.
  paid_by: { [ME]: "64.00" },
  equal_by: true,
  paid_for: { [ME]: "32.00", [OTHER]: "32.00" },
  equal_for: true,
  date: "2026-09-17",
};

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <SyncProvider>
          <Routes>
            <Route path="/list/:listId" element={<ListRoute />} />
            <Route path="/list/:listId/balances" element={<BalancesPage />} />
            <Route path="/list/:listId/properties" element={<ListPropsPage />} />
            <Route path="/" element={<div>Overview page</div>} />
          </Routes>
        </SyncProvider>
      </AuthProvider>
    </MemoryRouter>,
  );
}

/** The single push this interaction produced, as the pushed item's fields. */
function pushedItem() {
  const call = vi
    .mocked(api.sync)
    .mock.calls.map((c) => c[0])
    .find((request) => (request.changes.items?.length ?? 0) > 0);
  return call?.changes.items?.[0];
}

function pushedExpense(): Expense {
  return pushedItem()?.fields.expense?.value as Expense;
}

describe("expense list screen", () => {
  beforeEach(() => {
    // Through setToken, not localStorage directly: the token is cached in a module variable read
    // at import time, so writing the key here would leave getToken() null and AuthProvider would
    // treat the session as absent.
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [expenseList()], items: [expenseItem("e1", "Dinner", dinner)] },
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("shows the expenses, the total and my balance", async () => {
    renderAt("/list/list-1");

    expect(await screen.findByText("Dinner")).toBeInTheDocument();
    // A plain summary now; the selector is the way to balances (T-172).
    const summary = screen.getByText("Net spent").closest(".card") as HTMLElement;
    expect(within(summary).getByText("€64.00")).toBeInTheDocument();
    // I paid 64 and my share is 32, so the list owes me 32 — a credit, so signed (T-241).
    expect(within(summary).getByText("+€32.00")).toBeInTheDocument();
    expect(screen.getByText("paid by ME · for everyone")).toBeInTheDocument();
    // The date heading in the app's language, not the stored 2026-09-17 (T-180).
    expect(screen.getByRole("heading", { name: "Sep 17, 2026" })).toBeInTheDocument();
    // None of the shopping apparatus belongs here.
    expect(screen.queryByText("Show checked")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add item" })).not.toBeInTheDocument();
  });

  it("switches between expenses and balances with the selector under the list's own header", async () => {
    renderAt("/list/list-1");

    // The header every list has (T-172): the list's name, not a title of the view.
    expect(await screen.findByRole("heading", { name: "Trip" })).toBeInTheDocument();
    const expenses = screen.getByRole("link", { name: "Entries" });
    expect(expenses).toHaveAttribute("aria-current", "page");
    // The active view is checked, like Show checked when it is on (T-174).
    expect(expenses).toHaveTextContent("✓ Entries");
    expect(screen.getByRole("link", { name: "Balances" })).not.toHaveTextContent("✓");
    expect(screen.getByRole("link", { name: "Balances" })).not.toHaveAttribute("aria-current");

    await userEvent.click(screen.getByRole("link", { name: "Balances" }));
    expect(await screen.findByText("paid 64.00 · share 32.00")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Trip" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Balances" })).toHaveAttribute("aria-current", "page");

    await userEvent.click(screen.getByRole("link", { name: "Entries" }));
    expect(await screen.findByText("Dinner")).toBeInTheDocument();
  });

  it("adds an expense paid by me and split equally, with the leftover cent to the first", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));

    await userEvent.type(screen.getByLabelText("What"), "Taxi");
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "0.01");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    const expense = pushedExpense();
    expect(pushedItem()?.fields.name?.value).toBe("Taxi");
    expect(expense.paid_by).toEqual({ [ME]: "0.01" });
    // One cent cannot be halved: the first participant takes it and the other is simply absent.
    expect(expense.paid_for).toEqual({ [ME]: "0.01" });
    expect(expense.equal_for).toBe(true);
  });

  it("redistributes the auto shares when the total changes", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));
    await userEvent.type(screen.getByLabelText("What"), "Hotel");

    const total = screen.getByLabelText("Total (EUR)");
    await userEvent.type(total, "60.00");
    // Derived shares are placeholders: the field itself is empty, which is what "auto" means.
    expect(screen.getByLabelText(`For ${OTHER}@example.com`)).toHaveAttribute("placeholder", "30.00");

    await userEvent.clear(total);
    await userEvent.type(total, "64.01");
    // The odd cent goes to the first participant, never to both and never nowhere.
    expect(screen.getByLabelText(`For ${ME}@example.com`)).toHaveAttribute("placeholder", "32.01");
    expect(screen.getByLabelText(`For ${OTHER}@example.com`)).toHaveAttribute("placeholder", "32.00");
  });

  it("leaves a share the user typed alone, and lets the others absorb a change", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));
    await userEvent.type(screen.getByLabelText("What"), "Lobster");
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "60.00");

    const mine = screen.getByLabelText(`For ${ME}@example.com`);
    await userEvent.type(mine, "30.00");
    expect(screen.getByLabelText(`For ${OTHER}@example.com`)).toHaveAttribute("placeholder", "30.00");

    const total = screen.getByLabelText("Total (EUR)");
    await userEvent.clear(total);
    await userEvent.type(total, "64.00");
    // The fixed 30 stays exactly as typed; the auto share takes the difference.
    expect(mine).toHaveValue("30.00");
    expect(screen.getByLabelText(`For ${OTHER}@example.com`)).toHaveAttribute("placeholder", "34.00");

    await userEvent.click(screen.getByRole("button", { name: "Add" }));
    expect(pushedExpense().paid_for).toEqual({ [ME]: "30.00", [OTHER]: "34.00" });
    // Not an equal split any more, so reopening it must not redistribute.
    expect(pushedExpense().equal_for).toBe(false);
  });

  it("refuses a fully typed split that misses the total, and offers to use the sum", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));
    await userEvent.type(screen.getByLabelText("What"), "Groceries");
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "60.00");

    for (const id of [ME, OTHER]) {
      await userEvent.type(screen.getByLabelText(`For ${id}@example.com`), "20.00");
    }

    expect(screen.getByRole("alert")).toHaveTextContent("add up to 40.00, not 60.00");
    expect(screen.getByRole("button", { name: "Add" })).toBeDisabled();

    // The one-tap way out, for the case where that sum was the intended total.
    await userEvent.click(screen.getByRole("button", { name: "Set the total to 40.00" }));
    expect(screen.getByLabelText("Total (EUR)")).toHaveValue("40.00");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("refuses typed shares that exceed the total", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));
    await userEvent.type(screen.getByLabelText("What"), "Too much");
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "10.00");

    await userEvent.type(screen.getByLabelText(`For ${ME}@example.com`), "99.00");

    expect(screen.getByRole("alert")).toHaveTextContent("more than the total");
    expect(screen.getByRole("button", { name: "Add" })).toBeDisabled();
  });

  it("reopens an equal split as equal, so correcting the bill redistributes", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Dinner"));

    const total = screen.getByLabelText("Total (EUR)");
    expect(total).toHaveValue("64.00");
    await userEvent.clear(total);
    await userEvent.type(total, "70.00");

    expect(screen.getByLabelText(`For ${ME}@example.com`)).toHaveAttribute("placeholder", "35.00");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(pushedItem()?.id).toBe("e1");
    expect(pushedExpense().paid_for).toEqual({ [ME]: "35.00", [OTHER]: "35.00" });
  });

  it("asks rather than guessing when typed payer amounts no longer match the total", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));
    await userEvent.type(screen.getByLabelText("What"), "Split bill");
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "64.00");

    // Two payers, both typed: nothing is left on auto to absorb a correction.
    await userEvent.click(screen.getByLabelText(`Paid by ${OTHER}@example.com`).previousElementSibling as Element);
    await userEvent.type(screen.getByLabelText(`Paid by ${ME}@example.com`), "40.00");
    await userEvent.type(screen.getByLabelText(`Paid by ${OTHER}@example.com`), "20.00");

    expect(screen.getByRole("alert")).toHaveTextContent("add up to 60.00, not 64.00");
    expect(screen.getByRole("button", { name: "Add" })).toBeDisabled();
  });

  it("deletes an expense by tombstoning it", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Dinner"));
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(pushedItem()?.fields.deleted?.value).toBe(true);
  });
});

describe("expense list screen, on a list of one", () => {
  beforeEach(() => {
    // Through setToken, not localStorage directly: the token is cached in a module variable read
    // at import time, so writing the key here would leave getToken() null and AuthProvider would
    // treat the session as absent.
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "CHF", initials: "ME" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [expenseList([ME])], items: [] },
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("hides the distributions entirely and books the whole amount to me", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));

    expect(screen.queryByText("Paid by")).not.toBeInTheDocument();
    expect(screen.queryByText("For")).not.toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("What"), "Coffee");
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "4.20");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(pushedExpense().paid_by).toEqual({ [ME]: "4.20" });
    expect(pushedExpense().paid_for).toEqual({ [ME]: "4.20" });
  });

  it("shows no balance for a list of one, since it is always zero", async () => {
    renderAt("/list/list-1");

    expect(await screen.findByText("Net spent")).toBeInTheDocument();
    expect(screen.queryByText("Your balance")).not.toBeInTheDocument();
  });
});

describe("balances screen", () => {
  beforeEach(() => {
    // Through setToken, not localStorage directly: the token is cached in a module variable read
    // at import time, so writing the key here would leave getToken() null and AuthProvider would
    // treat the session as absent.
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [expenseList()],
        items: [
          expenseItem("e1", "Dinner", dinner),
          expenseItem("e2", "Taxi", {
            paid_by: { "acct-gone": "20.00" },
            equal_by: false,
            paid_for: { [ME]: "10.00", "acct-gone": "10.00" },
            equal_for: true,
            date: "2026-09-16",
          }),
        ],
      },
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("lists everyone involved, names those who left, and always sums to zero", async () => {
    renderAt("/list/list-1/balances");

    expect(await screen.findByText("Balances")).toBeInTheDocument();
    expect(screen.getByText("Former member 1")).toBeInTheDocument();

    const rows = screen.getAllByText(/^[-+]?€\d+\.\d\d$/);
    const amounts = rows
      .map((node) => node.textContent ?? "")
      .filter((text) => text !== "€84.00");
    // Balances: me +22.00, the other -32.00, the departed member +10.00 — each signed (T-241).
    expect(amounts).toContain("+€22.00");
    expect(amounts).toContain("-€32.00");
    expect(amounts).toContain("+€10.00");
  });

  it("shows what each participant paid and what their share was", async () => {
    renderAt("/list/list-1/balances");

    const mine = (await screen.findByText(`${ME}@example.com`)).closest("div");
    expect(within(mine as HTMLElement).getByText("paid 64.00 · share 42.00")).toBeInTheDocument();
  });
});


// ---- closing (T-159) ---------------------------------------------------------

describe("closing an expenses list", () => {
  function setUp(closeVotes: string[] = [], closedAt: number | null = null) {
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [expenseList([ME, OTHER], closeVotes, closedAt)],
        items: [expenseItem("e1", "Dinner", dinner)],
      },
    });
  }

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("puts closing directly above leave in list settings, and says what agreeing locks", async () => {
    setUp();
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("link", { name: "List properties" }));

    const closing = await screen.findByRole("heading", { name: "Closing" });
    const members = screen.getByRole("heading", { name: "Members" });
    const leave = screen.getByRole("button", { name: "Leave list" });
    // Two stages of one thing (T-169): every other section comes first, then closing, then leave.
    expect(members.compareDocumentPosition(closing) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(closing.compareDocumentPosition(leave) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText(/nothing involving you can be added, changed or deleted/)).toBeInTheDocument();
  });

  it("says nothing while nobody has voted", async () => {
    setUp();
    renderAt("/list/list-1");

    expect(await screen.findByText("Dinner")).toBeInTheDocument();
    expect(screen.queryByText(/Votes to close/)).not.toBeInTheDocument();
  });

  it("counts the votes once one is cast, and offers to agree", async () => {
    setUp([OTHER]);
    vi.mocked(api.castCloseVote).mockResolvedValue({ close_votes: [OTHER, ME], closed_at: 1 });
    renderAt("/list/list-1");

    expect(await screen.findByText("Votes to close: 1 of 2")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Agree to close" }));

    expect(api.castCloseVote).toHaveBeenCalledWith("list-1");
  });

  it("offers no Add once I have agreed to close (T-192)", async () => {
    setUp([ME]);
    renderAt("/list/list-1");

    expect(await screen.findByRole("button", { name: "Withdraw" })).toBeInTheDocument();
    // Agreeing to close means being done: the server refuses a voter's new expenses.
    expect(screen.queryByRole("button", { name: "Add entry" })).not.toBeInTheDocument();
  });

  it("opens no expense once I have agreed to close, though the rows stay to read (T-193)", async () => {
    setUp([ME]);
    renderAt("/list/list-1");

    expect(await screen.findByRole("button", { name: "Withdraw" })).toBeInTheDocument();
    expect(screen.getByText("Dinner").closest("button")).toBeDisabled();
  });

  it("locks the list's name and notes once I have agreed to close (T-193)", async () => {
    setUp([ME]);
    renderAt("/list/list-1/properties");

    expect(await screen.findByText(/You've agreed to close this list/)).toBeInTheDocument();
    expect(await screen.findByDisplayValue("Trip")).toBeDisabled();
    expect(screen.getByPlaceholderText(/Gate code/)).toBeDisabled();
    for (const button of screen.getAllByRole("button", { name: /^Save/ })) expect(button).toBeDisabled();
  });

  it("leaves the name and notes open to someone who has not agreed yet", async () => {
    setUp([OTHER]);
    renderAt("/list/list-1/properties");

    expect(await screen.findByDisplayValue("Trip")).toBeEnabled();
    expect(screen.queryByText(/You've agreed to close this list/)).not.toBeInTheDocument();
  });

  it("will not delete an expense that involves someone who has agreed to close (T-193)", async () => {
    // OTHER has voted and is on the dinner: deleting it would take their share to zero.
    setUp([OTHER]);
    renderAt("/list/list-1");

    await userEvent.click(await screen.findByText("Dinner"));

    expect(screen.getByRole("button", { name: "Delete" })).toBeDisabled();
    expect(screen.getByText(/can't be deleted: it involves someone whose amounts are fixed/)).toBeInTheDocument();
  });

  it("deletes freely while nobody on the expense has voted", async () => {
    setUp();
    renderAt("/list/list-1");

    await userEvent.click(await screen.findByText("Dinner"));

    expect(screen.getByRole("button", { name: "Delete" })).toBeEnabled();
    expect(screen.queryByText(/can't be deleted/)).not.toBeInTheDocument();
  });

  it("offers to withdraw once I have voted", async () => {
    setUp([ME]);
    vi.mocked(api.withdrawCloseVote).mockResolvedValue({ close_votes: [], closed_at: null });
    renderAt("/list/list-1");

    await userEvent.click(await screen.findByRole("button", { name: "Withdraw" }));

    expect(api.withdrawCloseVote).toHaveBeenCalledWith("list-1");
  });

  it("a closed list is an archive: no adding, no opening an expense", async () => {
    setUp([ME, OTHER], Date.UTC(2026, 8, 17));
    renderAt("/list/list-1");

    expect(await screen.findByText(/^Closed on /)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add entry" })).not.toBeInTheDocument();
    // The row is still there to read, it just cannot be opened.
    expect(screen.getByText("Dinner").closest("button")).toBeDisabled();
  });

  it("locks a voter's amounts in the form rather than hiding them", async () => {
    setUp([OTHER]);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Dinner"));

    const theirShare = screen.getByLabelText(`For ${OTHER}@example.com`);
    expect(theirShare).toBeDisabled();
    // Their share is still visible — it is part of the record, not something to drop silently.
    expect(screen.getAllByText("agreed to close — amounts fixed").length).toBeGreaterThan(0);
    // Mine is still editable: the freeze is about their money, not about the expense.
    expect(screen.getByLabelText(`For ${ME}@example.com`)).not.toBeDisabled();
  });

  it("says who is frozen when the server refuses the save", async () => {
    setUp([OTHER]);
    vi.mocked(api.sync).mockRejectedValue(
      new ApiError(422, "participant_frozen", "frozen", { account_id: OTHER }),
    );
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Dinner"));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    // Both causes in one sentence (T-203): the server refuses a former member's change too, and
    // the code alone does not say which of the two this was.
    expect(await screen.findByRole("alert")).toHaveTextContent(
      `The amounts of ${OTHER}@example.com are fixed`,
    );
  });
});


// ---- editing around a frozen participant (T-196) -----------------------------

describe("editing an expense that involves a frozen participant", () => {
  const THIRD = "acct-third";
  const GONE = "acct-gone";

  /** 64.00 three ways: the leftover cent to the first, which is what the dialog would write. */
  const dinnerFor = (third: string): Expense => ({
    paid_by: { [ME]: "64.00" },
    equal_by: true,
    paid_for: { [ME]: "21.34", [OTHER]: "21.33", [third]: "21.33" },
    equal_for: true,
    date: "2026-09-17",
  });

  function setUp(list: ReturnType<typeof expenseList>, items: ReturnType<typeof expenseItem>[]) {
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [list], items } });
  }

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("shows a voter their stored share, and saves it unchanged", async () => {
    setUp(expenseList([ME, OTHER, THIRD], [THIRD]), [expenseItem("e1", "Dinner", dinnerFor(THIRD))]);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Dinner"));

    // Their own 21.33, not the 0.00 that redistributing the whole total between the other two
    // would leave them at — every save of which the server refuses.
    expect(screen.getByLabelText(`For ${THIRD}@example.com`)).toHaveValue("21.33");
    expect(screen.getByLabelText(`For ${ME}@example.com`)).toHaveAttribute("placeholder", "21.34");
    expect(screen.getByLabelText(`For ${OTHER}@example.com`)).toHaveAttribute("placeholder", "21.33");

    // The title is all that changes, so every share must go back exactly as it came.
    await userEvent.clear(screen.getByLabelText("What"));
    await userEvent.type(screen.getByLabelText("What"), "Dinner out");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(pushedItem()?.fields.name?.value).toBe("Dinner out");
    expect(pushedExpense().paid_for).toEqual({ [ME]: "21.34", [OTHER]: "21.33", [THIRD]: "21.33" });
  });

  it("shows someone who has left their stored share, and saves it unchanged", async () => {
    setUp(expenseList([ME, OTHER]), [expenseItem("e1", "Dinner", dinnerFor(GONE))]);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Dinner"));

    // By label rather than by number: which number they carry is the numbering's business.
    expect(screen.getByLabelText(/^For Former member/)).toHaveValue("21.33");

    await userEvent.clear(screen.getByLabelText("What"));
    await userEvent.type(screen.getByLabelText("What"), "Dinner out");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(pushedExpense().paid_for).toEqual({ [ME]: "21.34", [OTHER]: "21.33", [GONE]: "21.33" });
  });

  it("says why each frozen participant is frozen (T-203)", async () => {
    // One expense with both kinds on it: THIRD has voted to close, GONE has left. The lock is the
    // same, the reason is not — and "agreed to close" is simply untrue of someone who left.
    const supper: Expense = {
      paid_by: { [ME]: "60.00" },
      equal_by: true,
      paid_for: { [ME]: "20.00", [THIRD]: "20.00", [GONE]: "20.00" },
      equal_for: true,
      date: "2026-09-17",
    };
    setUp(expenseList([ME, OTHER, THIRD], [THIRD]), [expenseItem("e1", "Supper", supper)]);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Supper"));

    // Once per side, since both sections list every participant.
    const voter = screen.getAllByText("agreed to close — amounts fixed");
    expect(voter).toHaveLength(2);
    expect(voter[0].closest("label")).toHaveTextContent(`${THIRD}@example.com`);
    const former = screen.getAllByText("no longer a member — amounts fixed");
    expect(former).toHaveLength(2);
    expect(former[0].closest("label")).toHaveTextContent(/^Former member/);
  });
});


// ---- one numbering for former members (T-197) --------------------------------

describe("numbering the members who have left", () => {
  const GONE_A = "acct-gone-a";
  const GONE_B = "acct-gone-b";
  // Numbered by first appearance newest-first, so the taxi's departed participant is 1 and the
  // museum's is 2 — everywhere the list, the balances and the form name them.
  const taxi: Expense = {
    paid_by: { [ME]: "20.00" },
    equal_by: true,
    paid_for: { [ME]: "10.00", [GONE_A]: "10.00" },
    equal_for: true,
    date: "2026-09-17",
  };
  const museum: Expense = {
    paid_by: { [ME]: "30.00" },
    equal_by: true,
    paid_for: { [ME]: "15.00", [GONE_B]: "15.00" },
    equal_for: true,
    date: "2026-09-16",
  };

  beforeEach(() => {
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [expenseList()],
        items: [expenseItem("e1", "Taxi", taxi), expenseItem("e2", "Museum", museum)],
      },
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("gives the form the same number as the row it was opened from", async () => {
    renderAt("/list/list-1");

    expect(await screen.findByText(/for ME, Former member 1$/)).toBeInTheDocument();
    expect(screen.getByText(/for ME, Former member 2$/)).toBeInTheDocument();

    await userEvent.click(screen.getByText("Museum"));
    // Not "Former member 1", which numbering this one expense on its own would give, and not
    // "Former member 3", which counting the two current members first would.
    expect(screen.getByLabelText("For Former member 2")).toBeInTheDocument();
  });

  it("gives the balances screen the same numbers", async () => {
    renderAt("/list/list-1/balances");

    expect(await screen.findByText("Former member 1")).toBeInTheDocument();
    expect(screen.getByText("Former member 2")).toBeInTheDocument();
  });
});


// ---- settling up (T-164) -----------------------------------------------------

describe("settling up", () => {
  const taxi: Expense = {
    paid_by: { "acct-gone": "20.00" },
    equal_by: false,
    paid_for: { [ME]: "10.00", "acct-gone": "10.00" },
    equal_for: true,
    date: "2026-09-16",
  };

  /** Replaces whatever sync would answer: the first pull returns these, later pulls nothing. */
  function syncWith(list: ReturnType<typeof expenseList>, items: ReturnType<typeof expenseItem>[]) {
    vi.mocked(api.sync).mockReset();
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [list], items } });
  }

  beforeEach(() => {
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    // Balances: me +22.00, the other -32.00, the departed member +10.00. So the other pays me
    // 22.00 first (largest debtor, largest creditor) and the departed member the remaining 10.00.
    syncWith(expenseList(), [expenseItem("e1", "Dinner", dinner), expenseItem("e2", "Taxi", taxi)]);
  });

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("lists who pays whom, largest first, and names a former member", async () => {
    renderAt("/list/list-1/balances");

    expect(await screen.findByText("Settle up")).toBeInTheDocument();
    const section = screen.getByRole("region", { name: "Settle up" });
    expect(within(section).getAllByText(/ pays /).map((node) => node.textContent)).toEqual([
      `${OTHER}@example.com pays ${ME}@example.com`,
      `${OTHER}@example.com pays Former member 1`,
    ]);
    expect(within(section).getByText("€22.00")).toBeInTheDocument();
    expect(within(section).getByText("€10.00")).toBeInTheDocument();
    // Only the transfer between two current members can be recorded; nobody can settle with
    // someone who has left.
    expect(within(section).getAllByRole("button", { name: "Reimburse" })).toHaveLength(1);
  });

  it("records a transfer as an ordinary expense through the pre-filled form", async () => {
    renderAt("/list/list-1/balances");
    await userEvent.click(await screen.findByRole("button", { name: "Reimburse" }));

    expect((screen.getByLabelText("What") as HTMLInputElement).value).toBe("Settlement");
    expect((screen.getByLabelText("Total (EUR)") as HTMLInputElement).value).toBe("22.00");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    const expense = pushedExpense();
    expect(pushedItem()?.fields.name?.value).toBe("Settlement");
    expect(expense.paid_by).toEqual({ [OTHER]: "22.00" });
    expect(expense.paid_for).toEqual({ [ME]: "22.00" });
    // The equal split of one person on each side — what the dialog writes for a lone payer, and
    // what lets a changed total follow through.
    expect(expense.equal_by).toBe(true);
    expect(expense.equal_for).toBe(true);
    expect(expense.date).toBe(today());
  });

  it("a partial settlement is a changed total", async () => {
    renderAt("/list/list-1/balances");
    await userEvent.click(await screen.findByRole("button", { name: "Reimburse" }));

    const total = screen.getByLabelText("Total (EUR)");
    await userEvent.clear(total);
    await userEvent.type(total, "10.00");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    const expense = pushedExpense();
    expect(expense.paid_by).toEqual({ [OTHER]: "10.00" });
    expect(expense.paid_for).toEqual({ [ME]: "10.00" });
  });

  it("offers nothing to record once a party has agreed to close", async () => {
    syncWith(expenseList([ME, OTHER], [OTHER]), [
      expenseItem("e1", "Dinner", dinner),
      expenseItem("e2", "Taxi", taxi),
    ]);
    renderAt("/list/list-1/balances");

    expect(await screen.findByText("Settle up")).toBeInTheDocument();
    // Both transfers involve the voter, whose amounts are frozen — the rows stay, the buttons go.
    expect(screen.getAllByText(/ pays /)).toHaveLength(2);
    expect(screen.queryByRole("button", { name: "Reimburse" })).not.toBeInTheDocument();
  });

  it("a closed list keeps the transfers as its archive, with nothing to record", async () => {
    syncWith(expenseList([ME, OTHER], [ME, OTHER], 1_758_000_000_000), [
      expenseItem("e1", "Dinner", dinner),
      expenseItem("e2", "Taxi", taxi),
    ]);
    renderAt("/list/list-1/balances");

    expect(await screen.findByText("Settle up")).toBeInTheDocument();
    expect(screen.getAllByText(/ pays /)).toHaveLength(2);
    expect(screen.queryByRole("button", { name: "Reimburse" })).not.toBeInTheDocument();
  });

  it("offers no Reimburse to someone who has agreed to close, even between two others (T-192)", async () => {
    const THIRD = "acct-third";
    // The other owes the third member; nothing here involves me.
    const between: Expense = {
      paid_by: { [THIRD]: "20.00" },
      equal_by: true,
      paid_for: { [OTHER]: "20.00" },
      equal_for: true,
      date: "2026-09-17",
    };
    syncWith(expenseList([ME, OTHER, THIRD]), [expenseItem("e1", "Tickets", between)]);
    renderAt("/list/list-1/balances");
    // Before I vote it is offered: a transfer between two current members who have not voted.
    expect(await screen.findByRole("button", { name: "Reimburse" })).toBeInTheDocument();
    cleanup();

    syncWith(expenseList([ME, OTHER, THIRD], [ME]), [expenseItem("e1", "Tickets", between)]);
    renderAt("/list/list-1/balances");
    expect(await screen.findByText("Settle up")).toBeInTheDocument();
    // Reimburse adds an expense, which I may no longer do.
    expect(screen.queryByRole("button", { name: "Reimburse" })).not.toBeInTheDocument();
  });

  it("says all settled when nothing is owed", async () => {
    const mirror: Expense = { ...dinner, paid_by: { [OTHER]: "64.00" } };
    syncWith(expenseList(), [expenseItem("e1", "Dinner", dinner), expenseItem("e2", "Lunch", mirror)]);
    renderAt("/list/list-1/balances");

    expect(await screen.findByText("All settled")).toBeInTheDocument();
    expect(screen.queryByText(/ pays /)).not.toBeInTheDocument();
  });

  it("hides settling up on a list of one", async () => {
    const solo: Expense = {
      paid_by: { [ME]: "12.00" },
      equal_by: true,
      paid_for: { [ME]: "12.00" },
      equal_for: true,
      date: "2026-09-17",
    };
    syncWith(expenseList([ME]), [expenseItem("e1", "Coffee", solo)]);
    renderAt("/list/list-1/balances");

    expect(await screen.findByText("Balances")).toBeInTheDocument();
    expect(screen.queryByText("Settle up")).not.toBeInTheDocument();
  });
});


// ---- the three entry types (T-245) -------------------------------------------

describe("the entry dialog knows three types", () => {
  function setUp(list: ReturnType<typeof expenseList>, items: ReturnType<typeof expenseItem>[]) {
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [list], items } });
  }

  /** Opens the add form on a two-member ledger holding one ordinary dinner. */
  async function openAddForm() {
    setUp(expenseList(), [expenseItem("e1", "Dinner", dinner)]);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));
  }

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("offers all three, with Expense the one a new entry starts as", async () => {
    await openAddForm();

    expect(screen.getByRole("radio", { name: "Expense" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Income" })).not.toBeChecked();
    expect(screen.getByRole("radio", { name: "Transfer" })).toBeEnabled();
    // The expense form is exactly what it was.
    expect(screen.getByText("Paid by")).toBeInTheDocument();
    expect(screen.getByText("For")).toBeInTheDocument();
  });

  it("reads an income's two sides as received and credited", async () => {
    await openAddForm();
    await userEvent.click(screen.getByRole("radio", { name: "Income" }));

    expect(screen.getByText("Received by")).toBeInTheDocument();
    expect(screen.getByText("Credited to")).toBeInTheDocument();
    expect(screen.queryByText("Paid by")).not.toBeInTheDocument();
    // Still the same form underneath: the shares are there to be edited.
    expect(screen.getByLabelText(`Received by ${ME}@example.com`)).toBeInTheDocument();
  });

  it("saves an income with its type and none of its amounts negated", async () => {
    await openAddForm();
    await userEvent.click(screen.getByRole("radio", { name: "Income" }));
    await userEvent.type(screen.getByLabelText("What"), "Deposit back");
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "30.00");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    const income = pushedExpense();
    expect(income.type).toBe("income");
    expect(income.paid_by).toEqual({ [ME]: "30.00" });
    expect(income.paid_for).toEqual({ [ME]: "15.00", [OTHER]: "15.00" });
  });

  it("swaps the split for a sender and a recipient on a transfer, and saves one of each", async () => {
    await openAddForm();
    await userEvent.click(screen.getByRole("radio", { name: "Transfer" }));

    // No split to make: one person hands money to another.
    expect(screen.queryByText("Paid by")).not.toBeInTheDocument();
    // The value is the account id; what it reads as is the member's own label.
    expect(screen.getByLabelText("From")).toHaveValue(ME);
    expect(screen.getByLabelText("From")).toHaveTextContent(`${ME}@example.com`);
    expect(screen.getByLabelText("To")).toHaveValue(OTHER);

    await userEvent.type(screen.getByLabelText("What"), "Payback");
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "20.00");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    const transfer = pushedExpense();
    expect(transfer.type).toBe("transfer");
    expect(transfer.paid_by).toEqual({ [ME]: "20.00" });
    expect(transfer.paid_for).toEqual({ [OTHER]: "20.00" });
    expect(transfer.equal_by).toBe(true);
  });

  it("neither picker offers the other's choice", async () => {
    await openAddForm();
    await userEvent.click(screen.getByRole("radio", { name: "Transfer" }));

    const from = screen.getByLabelText("From") as HTMLSelectElement;
    const to = screen.getByLabelText("To") as HTMLSelectElement;
    expect([...from.options].map((option) => option.value)).toEqual([ME]);
    expect([...to.options].map((option) => option.value)).toEqual([OTHER]);
  });

  it("keeps the title, the total, the date and the note across a change of type", async () => {
    await openAddForm();
    await userEvent.type(screen.getByLabelText("What"), "Market");
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "18.00");
    await userEvent.type(screen.getByLabelText("Note"), "in cash");

    for (const type of ["Income", "Transfer", "Expense"]) {
      await userEvent.click(screen.getByRole("radio", { name: type }));
      expect(screen.getByLabelText("What")).toHaveValue("Market");
      expect(screen.getByLabelText("Total (EUR)")).toHaveValue("18.00");
      expect(screen.getByLabelText("Note")).toHaveValue("in cash");
      expect(screen.getByLabelText("Date")).toHaveValue(today());
    }
  });

  it("keeps the participants when an expense becomes an income and back", async () => {
    await openAddForm();
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "40.00");
    // A share typed by hand, so the maps are no longer the untouched default.
    await userEvent.type(screen.getByLabelText(`For ${OTHER}@example.com`), "10.00");

    await userEvent.click(screen.getByRole("radio", { name: "Income" }));
    expect(screen.getByLabelText(`Credited to ${OTHER}@example.com`)).toHaveValue("10.00");
    expect(screen.getByLabelText(`Credited to ${ME}@example.com`)).toHaveAttribute("placeholder", "30.00");

    await userEvent.click(screen.getByRole("radio", { name: "Expense" }));
    expect(screen.getByLabelText(`For ${OTHER}@example.com`)).toHaveValue("10.00");
  });

  it("turns a transfer back into an expense the sender paid and the recipient owes", async () => {
    await openAddForm();
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "20.00");
    await userEvent.click(screen.getByRole("radio", { name: "Transfer" }));
    await userEvent.click(screen.getByRole("radio", { name: "Expense" }));
    await userEvent.type(screen.getByLabelText("What"), "Loan");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    // The sender is the whole of "paid by", the recipient the whole of "for": the same movement
    // of money, now counted as spending.
    expect(pushedExpense().type).toBe("expense");
    expect(pushedExpense().paid_by).toEqual({ [ME]: "20.00" });
    expect(pushedExpense().paid_for).toEqual({ [OTHER]: "20.00" });
  });

  it("names a blank income and a blank transfer after their type, but still asks an expense", async () => {
    await openAddForm();
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "12.00");
    // An expense with no title cannot be saved, as it never could.
    expect(screen.getByRole("button", { name: "Add" })).toBeDisabled();

    await userEvent.click(screen.getByRole("radio", { name: "Income" }));
    await userEvent.click(screen.getByRole("button", { name: "Add" }));
    expect(pushedItem()?.fields.name?.value).toBe("Income");
  });

  it("names a blank transfer after its type", async () => {
    await openAddForm();
    await userEvent.type(screen.getByLabelText("Total (EUR)"), "12.00");
    await userEvent.click(screen.getByRole("radio", { name: "Transfer" }));
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(pushedItem()?.fields.name?.value).toBe("Transfer");
  });

  it("opens an entry stored before types existed as the expense it has always been", async () => {
    setUp(expenseList(), [expenseItem("e1", "Dinner", dinner)]);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Dinner"));

    expect(screen.getByRole("heading", { name: "Edit entry" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Expense" })).toBeChecked();
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(pushedExpense().type).toBe("expense");
  });

  it("opens a stored transfer on its own two pickers", async () => {
    const payback: Expense = {
      type: "transfer",
      paid_by: { [OTHER]: "15.00" },
      equal_by: true,
      paid_for: { [ME]: "15.00" },
      equal_for: true,
      date: "2026-09-17",
    };
    setUp(expenseList(), [expenseItem("e1", "Payback", payback)]);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Payback"));

    expect(screen.getByRole("radio", { name: "Transfer" })).toBeChecked();
    expect(screen.getByLabelText("From")).toHaveValue(OTHER);
    expect(screen.getByLabelText("To")).toHaveValue(ME);
    expect(screen.getByLabelText("Total (EUR)")).toHaveValue("15.00");
  });

  it("refuses a transfer that pays its own sender", async () => {
    // Nothing the form can produce — the pickers exclude each other — but a row written by
    // another client can say it, and saving it on would only earn a refusal.
    const circular: Expense = {
      type: "transfer",
      paid_by: { [ME]: "5.00" },
      equal_by: true,
      paid_for: { [ME]: "5.00" },
      equal_for: true,
      date: "2026-09-17",
    };
    setUp(expenseList(), [expenseItem("e1", "Odd one", circular)]);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByText("Odd one"));

    expect(screen.getByRole("alert")).toHaveTextContent("Choose two different people.");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("does not offer a transfer to someone whose amounts are frozen", async () => {
    const THIRD = "acct-third";
    setUp(expenseList([ME, OTHER, THIRD], [THIRD]), [expenseItem("e1", "Dinner", dinner)]);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));
    await userEvent.click(screen.getByRole("radio", { name: "Transfer" }));

    const to = screen.getByLabelText("To") as HTMLSelectElement;
    expect([...to.options].map((option) => option.value)).not.toContain(THIRD);
  });

  it("cannot record a transfer on a list of one", async () => {
    setUp(expenseList([ME]), []);
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));

    expect(screen.getByRole("radio", { name: "Transfer" })).toBeDisabled();
    expect(screen.getByText("A transfer needs two different people on this list.")).toBeInTheDocument();
    // Income is still on offer: one person can be refunded.
    expect(screen.getByRole("radio", { name: "Income" })).toBeEnabled();
  });
});
