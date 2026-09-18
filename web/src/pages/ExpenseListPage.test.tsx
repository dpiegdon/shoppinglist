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
    const summary = screen.getByText("Total spent").closest(".card") as HTMLElement;
    expect(within(summary).getByText("64.00 EUR")).toBeInTheDocument();
    // I paid 64 and my share is 32, so the list owes me 32.
    expect(within(summary).getByText("32.00 EUR")).toBeInTheDocument();
    expect(screen.getByText("paid by ME · for everyone")).toBeInTheDocument();
    // None of the shopping apparatus belongs here.
    expect(screen.queryByText("Show checked")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add item" })).not.toBeInTheDocument();
  });

  it("switches between expenses and balances with the selector under the list's own header", async () => {
    renderAt("/list/list-1");

    // The header every list has (T-172): the list's name, not a title of the view.
    expect(await screen.findByRole("heading", { name: "Trip" })).toBeInTheDocument();
    const expenses = screen.getByRole("link", { name: "Expenses" });
    expect(expenses).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Balances" })).not.toHaveAttribute("aria-current");

    await userEvent.click(screen.getByRole("link", { name: "Balances" }));
    expect(await screen.findByText("paid 64.00 · share 32.00")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Trip" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Balances" })).toHaveAttribute("aria-current", "page");

    await userEvent.click(screen.getByRole("link", { name: "Expenses" }));
    expect(await screen.findByText("Dinner")).toBeInTheDocument();
  });

  it("adds an expense paid by me and split equally, with the leftover cent to the first", async () => {
    renderAt("/list/list-1");
    await userEvent.click(await screen.findByRole("button", { name: "Add expense" }));

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
    await userEvent.click(await screen.findByRole("button", { name: "Add expense" }));
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
    await userEvent.click(await screen.findByRole("button", { name: "Add expense" }));
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
    await userEvent.click(await screen.findByRole("button", { name: "Add expense" }));
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
    await userEvent.click(await screen.findByRole("button", { name: "Add expense" }));
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
    await userEvent.click(await screen.findByRole("button", { name: "Add expense" }));
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
    await userEvent.click(await screen.findByRole("button", { name: "Add expense" }));

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

    expect(await screen.findByText("Total spent")).toBeInTheDocument();
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

    const rows = screen.getAllByText(/^-?\d+\.\d\d EUR$/);
    const amounts = rows
      .map((node) => node.textContent ?? "")
      .filter((text) => text !== "84.00 EUR");
    // Balances: me +22.00, the other -32.00, the departed member +10.00.
    expect(amounts).toContain("22.00 EUR");
    expect(amounts).toContain("-32.00 EUR");
    expect(amounts).toContain("10.00 EUR");
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
    expect(screen.getByText(/nothing involving you can be added or changed/)).toBeInTheDocument();
  });

  it("says nothing while nobody has voted", async () => {
    setUp();
    renderAt("/list/list-1");

    expect(await screen.findByText("Dinner")).toBeInTheDocument();
    expect(screen.queryByText(/agree to close/)).not.toBeInTheDocument();
  });

  it("counts the votes once one is cast, and offers to agree", async () => {
    setUp([OTHER]);
    vi.mocked(api.castCloseVote).mockResolvedValue({ close_votes: [OTHER, ME], closed_at: 1 });
    renderAt("/list/list-1");

    expect(await screen.findByText("1 of 2 agree to close")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Agree to close" }));

    expect(api.castCloseVote).toHaveBeenCalledWith("list-1");
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
    expect(screen.queryByRole("button", { name: "Add expense" })).not.toBeInTheDocument();
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

    expect(await screen.findByRole("alert")).toHaveTextContent(
      `${OTHER}@example.com has agreed to close the list`,
    );
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
    expect(within(section).getByText("22.00 EUR")).toBeInTheDocument();
    expect(within(section).getByText("10.00 EUR")).toBeInTheDocument();
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
