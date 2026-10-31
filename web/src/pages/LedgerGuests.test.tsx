import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import BalancesPage from "./BalancesPage";
import ListPropsPage from "./ListPropsPage";
import ListRoute from "./ListRoute";
import OverviewPage, { MAX_NEW_GUESTS, _resetInitialResumeForTests } from "./OverviewPage";
import RedeemPage from "./RedeemPage";
import { AuthProvider } from "../auth/AuthContext";
import { SyncProvider } from "../hooks/SyncContext";
import * as api from "../api/client";
import { ApiError } from "../api/client";
import type { Expense, ListGuest, ListObject } from "../api/contract";

/**
 * Ledger guests on every web screen (T-364, T-367): labels, the fold, the frozen rules in the form,
 * the list-properties section, the create dialog, and the chooser after joining.
 */

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return {
    ...actual,
    sync: vi.fn(),
    getSettings: vi.fn(),
    getMembers: vi.fn(),
    getPendingInvites: vi.fn(),
    redeemInvite: vi.fn(),
    createGuest: vi.fn(),
    updateGuest: vi.fn(),
    deleteGuest: vi.fn(),
    claimGuest: vi.fn(),
    unclaimGuest: vi.fn(),
  };
});

const ME = "acct-me";
const OTHER = "acct-other";

function clock<T>(value: T) {
  return { value, updated_at: 1, updated_by: "dev" };
}

function guest(id: string, name: string, extra: Partial<ListGuest> = {}): ListGuest {
  return { id, name, initials: name.slice(0, 2).toUpperCase(), locked: false, claimed_by: null, created_at: 0, ...extra };
}

const DAVE = guest("g:dave", "Dave");

function ledger(
  guests: ListGuest[] | undefined,
  { closeVotes = [] as string[], closedAt = null as number | null, kind = "expenses" } = {},
): ListObject {
  return {
    id: "list-1",
    created_at: 0,
    fields: {
      name: clock("Trip"),
      kind: clock(kind as "expenses"),
      currency: clock("EUR"),
      category_order: clock([]),
      deleted: clock(false),
    },
    members: [ME, OTHER].map((id) => ({
      account_id: id,
      email: `${id}@example.com`,
      initials: id === ME ? "ME" : "OT",
    })),
    close_votes: closeVotes,
    closed_at: closedAt,
    ...(guests === undefined ? {} : { guests }),
  };
}

function entry(id: string, name: string, expense: Expense) {
  return {
    id,
    list_id: "list-1",
    created_at: 0,
    fields: { name: clock(name), note: clock(null), expense: clock(expense), deleted: clock(false) },
  };
}

function deleted(item: ReturnType<typeof entry>) {
  return { ...item, fields: { ...item.fields, deleted: clock(true) } };
}

function expense(paidBy: Record<string, string>, paidFor: Record<string, string>, extra: Partial<Expense> = {}): Expense {
  return { paid_by: paidBy, equal_by: false, paid_for: paidFor, equal_for: false, date: "2026-10-30", ...extra };
}

function serve(list: ListObject, items: ReturnType<typeof entry>[] = []) {
  vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
  vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [list], items } });
}

/** Where the router is, so a test can read the query a page navigated with. */
function Where() {
  const location = useLocation();
  return <p data-testid="where">{`${location.pathname}${location.search}`}</p>;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <SyncProvider>
          <Routes>
            <Route path="/list/:listId" element={<><ListRoute /><Where /></>} />
            <Route path="/list/:listId/balances" element={<BalancesPage />} />
            <Route path="/list/:listId/properties" element={<ListPropsPage />} />
            <Route path="/" element={<OverviewPage />} />
            <Route path="/redeem" element={<RedeemPage />} />
          </Routes>
        </SyncProvider>
      </AuthProvider>
    </MemoryRouter>,
  );
}

function pushedExpense(): Expense | undefined {
  const request = vi
    .mocked(api.sync)
    .mock.calls.map((call) => call[0])
    .find((r) => (r.changes.items?.length ?? 0) > 0);
  return request?.changes.items?.[0].fields.expense?.value as Expense | undefined;
}

beforeEach(() => {
  api.setToken("test-token");
  localStorage.setItem("shoppinglist_account", JSON.stringify({ id: ME, email: "me@example.com", isAdmin: false }));
  vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "ME" });
  vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
  vi.mocked(api.getPendingInvites).mockResolvedValue({ invites: [] });
  vi.mocked(api.createGuest).mockResolvedValue(DAVE);
  vi.mocked(api.updateGuest).mockResolvedValue(DAVE);
  vi.mocked(api.deleteGuest).mockResolvedValue(undefined);
  vi.mocked(api.claimGuest).mockResolvedValue(DAVE);
  vi.mocked(api.unclaimGuest).mockResolvedValue(DAVE);
});

afterEach(() => {
  vi.clearAllMocks();
  api.setToken(null);
  localStorage.clear();
  cleanup();
});

describe("entries name guests", () => {
  it("labels a guest by name and a claimed one by name and claimant", async () => {
    const eve = guest("g:eve", "Eve", { claimed_by: OTHER });
    serve(ledger([DAVE, eve]), [
      entry("e1", "Dinner", expense({ "g:dave": "30.00" }, { [ME]: "10.00", [OTHER]: "10.00", "g:dave": "10.00" })),
      entry("e2", "Taxi", expense({ "g:eve": "8.00" }, { [ME]: "8.00" })),
    ]);
    renderAt("/list/list-1");

    const dinner = (await screen.findByText("Dinner")).closest("button")!;
    // Every member and every unclaimed guest is "everyone" (T-364).
    expect(within(dinner).getByText("paid by Dave · for everyone")).toBeInTheDocument();
    const taxi = screen.getByText("Taxi").closest("button")!;
    expect(within(taxi).getByText("paid by Eve · OT · for ME")).toBeInTheDocument();
  });

  it("shows my effect through a guest I have claimed", async () => {
    // I am Eve now: what Eve paid is mine.
    const eve = guest("g:eve", "Eve", { claimed_by: ME });
    serve(ledger([eve]), [entry("e1", "Taxi", expense({ "g:eve": "8.00" }, { [OTHER]: "8.00" }))]);
    renderAt("/list/list-1");

    const taxi = (await screen.findByText("Taxi")).closest("button")!;
    expect(within(taxi).getByText(/^\+€8\.00$/)).toBeInTheDocument();
  });
});

describe("balances with guests", () => {
  it("gives an unclaimed guest a marked row, folds a claimed one into its claimant, and says a lock", async () => {
    const lockedDave = guest("g:dave", "Dave", { locked: true });
    const eve = guest("g:eve", "Eve", { claimed_by: OTHER });
    serve(ledger([lockedDave, eve]), [
      entry("e1", "Dinner", expense({ [ME]: "30.00" }, { [ME]: "10.00", "g:dave": "10.00", "g:eve": "10.00" })),
    ]);
    renderAt("/list/list-1/balances");

    const daveRow = (await screen.findByText("Dave")).closest<HTMLElement>(".row")!;
    expect(within(daveRow).getByRole("img", { name: "Dave (guest)" })).toBeInTheDocument();
    expect(within(daveRow).getByText("amounts locked")).toBeInTheDocument();
    // Eve's share is the other member's: no row of her own.
    expect(screen.queryByText("Eve")).not.toBeInTheDocument();
    const otherRow = screen.getByText(`${OTHER}@example.com`).closest<HTMLElement>(".row")!;
    expect(within(otherRow).getByText(/-€10\.00/)).toBeInTheDocument();
    expect(within(otherRow).queryByText("amounts locked")).not.toBeInTheDocument();
  });

  it("records a guest's reimbursement under the guest's own id, and offers none for a locked one", async () => {
    serve(ledger([DAVE]), [entry("e1", "Dinner", expense({ [ME]: "20.00" }, { [ME]: "10.00", "g:dave": "10.00" }))]);
    renderAt("/list/list-1/balances");

    const line = (await screen.findByText(/Dave.* pays .*acct-me@example.com/)).closest<HTMLElement>(".row")!;
    await userEvent.click(within(line).getByRole("button", { name: "Reimburse" }));
    await userEvent.click(screen.getByRole("button", { name: "Add" }));
    await waitFor(() => expect(pushedExpense()).toBeDefined());
    expect(pushedExpense()).toMatchObject({ type: "transfer", paid_by: { "g:dave": "10.00" }, paid_for: { [ME]: "10.00" } });
  });

  it("offers no Reimburse to a locked guest", async () => {
    serve(ledger([guest("g:dave", "Dave", { locked: true })]), [
      entry("e1", "Dinner", expense({ [ME]: "20.00" }, { [ME]: "10.00", "g:dave": "10.00" })),
    ]);
    renderAt("/list/list-1/balances");

    await screen.findByText(/Dave.* pays .*acct-me@example.com/);
    expect(screen.queryByRole("button", { name: "Reimburse" })).not.toBeInTheDocument();
  });
});

describe("the entry form with guests", () => {
  it("offers the unclaimed guests after the members, never a claimed one, and a locked one unselected", async () => {
    serve(ledger([DAVE, guest("g:eve", "Eve", { claimed_by: OTHER }), guest("g:fay", "Fay", { locked: true })]));
    renderAt("/list/list-1");

    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));
    const forWhom = screen.getByRole("group", { name: "For" });
    const labels = within(forWhom).getAllByRole("checkbox").map((box) => box.id.replace("for-", ""));
    expect(labels).toEqual([ME, OTHER, "g:dave", "g:fay"]);
    expect(within(forWhom).getByRole("checkbox", { name: /Dave/ })).toBeChecked();
    const fay = within(forWhom).getByRole("checkbox", { name: /Fay/ });
    expect(fay).not.toBeChecked();
    expect(fay).toBeDisabled();
    expect(within(forWhom).getByText("locked — amounts fixed")).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("What"), "Pizza");
    await userEvent.type(screen.getByLabelText(/Total/), "30");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));
    await waitFor(() => expect(pushedExpense()).toBeDefined());
    expect(pushedExpense()?.paid_for).toEqual({ [ME]: "10.00", [OTHER]: "10.00", "g:dave": "10.00" });
  });

  it("keeps a locked guest's share when an equal split is edited (T-196)", async () => {
    serve(ledger([guest("g:dave", "Dave", { locked: true })]), [
      entry(
        "e1",
        "Dinner",
        expense({ [ME]: "30.00" }, { [ME]: "10.00", [OTHER]: "10.00", "g:dave": "10.00" }, { equal_by: true, equal_for: true }),
      ),
    ]);
    renderAt("/list/list-1");

    await userEvent.click((await screen.findByText("Dinner")).closest("button")!);
    const total = screen.getByLabelText(/Total/);
    await userEvent.clear(total);
    await userEvent.type(total, "40");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(pushedExpense()).toBeDefined());
    expect(pushedExpense()?.paid_for).toEqual({ [ME]: "15.00", [OTHER]: "15.00", "g:dave": "10.00" });
  });

  it("lets a transfer name a guest", async () => {
    serve(ledger([DAVE]));
    renderAt("/list/list-1");

    await userEvent.click(await screen.findByRole("button", { name: "Add entry" }));
    await userEvent.click(screen.getByRole("radio", { name: /Transfer/ }));
    await userEvent.selectOptions(screen.getByLabelText("To"), "g:dave");
    expect(screen.getByRole("option", { name: "Dave (guest)" })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/Total/), "5");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));
    await waitFor(() => expect(pushedExpense()).toBeDefined());
    expect(pushedExpense()).toMatchObject({ type: "transfer", paid_by: { [ME]: "5.00" }, paid_for: { "g:dave": "5.00" } });
  });

  it("names a guest in a refusal by its name, not its id", async () => {
    serve(ledger([DAVE]), [entry("e1", "Dinner", expense({ [ME]: "20.00" }, { [ME]: "10.00", "g:dave": "10.00" }))]);
    renderAt("/list/list-1");
    await userEvent.click((await screen.findByText("Dinner")).closest("button")!);
    // Refused for a reason this screen has not synced yet: the sentence still says who.
    vi.mocked(api.sync).mockImplementation(async () => {
      throw new ApiError(422, "participant_frozen", "frozen", { account_id: "g:dave" });
    });
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The amounts of Dave are fixed");
  });

  it("says 'Dave: amounts locked' when the guest is known to be locked", async () => {
    serve(ledger([guest("g:dave", "Dave", { locked: true })]), [
      entry("e1", "Dinner", expense({ [ME]: "20.00" }, { [ME]: "10.00", [OTHER]: "10.00" })),
    ]);
    renderAt("/list/list-1");
    await userEvent.click((await screen.findByText("Dinner")).closest("button")!);
    vi.mocked(api.sync).mockImplementation(async () => {
      throw new ApiError(422, "participant_frozen", "frozen", { account_id: "g:dave" });
    });
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Dave: amounts locked");
  });
});

describe("list properties: the Guests section", () => {
  async function openProps(list: ListObject, items: ReturnType<typeof entry>[] = []) {
    serve(list, items);
    renderAt("/list/list-1/properties");
    return (await screen.findByRole("heading", { name: "Guests" })).closest<HTMLElement>("section")!;
  }

  it("sits directly under Members, on a ledger", async () => {
    const section = await openProps(ledger([DAVE]));
    const members = screen.getByRole("heading", { name: "Members" }).closest("section")!;
    expect(members.nextElementSibling).toBe(section);
  });

  it("is absent when the server serves no guests (protocol 3)", async () => {
    serve(ledger(undefined));
    renderAt("/list/list-1/properties");
    await screen.findByRole("heading", { name: "Members" });
    expect(screen.queryByRole("heading", { name: "Guests" })).not.toBeInTheDocument();
  });

  it("is absent on a shopping list", async () => {
    serve(ledger([], { kind: "shopping" }));
    renderAt("/list/list-1/properties");
    await screen.findByRole("heading", { name: "Members" });
    expect(screen.queryByRole("heading", { name: "Guests" })).not.toBeInTheDocument();
  });

  it("adds a guest by name and pulls", async () => {
    const section = await openProps(ledger([]));
    const syncsBefore = vi.mocked(api.sync).mock.calls.length;
    await userEvent.type(within(section).getByLabelText("Add guest"), "  Dave ");
    await userEvent.click(within(section).getByRole("button", { name: "Add" }));
    expect(api.createGuest).toHaveBeenCalledWith("list-1", "Dave");
    await waitFor(() => expect(vi.mocked(api.sync).mock.calls.length).toBeGreaterThan(syncsBefore));
    expect(within(section).getByLabelText("Add guest")).toHaveValue("");
  });

  it("renames inline, locks, and claims", async () => {
    const section = await openProps(ledger([DAVE]));
    const row = within(section).getByTestId("guest-row");
    expect(within(row).getByRole("img", { name: "Dave (guest)" })).toBeInTheDocument();
    expect(within(row).getByText("Locked: the amounts cannot change, like a member who agreed to close")).toBeInTheDocument();

    await userEvent.click(within(row).getByRole("button", { name: "Rename" }));
    const field = within(row).getByLabelText("Guest name");
    await userEvent.clear(field);
    await userEvent.type(field, "David");
    await userEvent.click(within(row).getByRole("button", { name: "Save" }));
    expect(api.updateGuest).toHaveBeenCalledWith("list-1", "g:dave", { name: "David" });

    await userEvent.click(within(row).getByRole("switch", { name: "Amounts locked" }));
    expect(api.updateGuest).toHaveBeenCalledWith("list-1", "g:dave", { locked: true });

    await userEvent.click(within(row).getByRole("button", { name: "This is me" }));
    expect(api.claimGuest).toHaveBeenCalledWith("list-1", "g:dave");
  });

  it("removes an unused guest, and refuses with the reason while an entry names it", async () => {
    const section = await openProps(ledger([DAVE, guest("g:eve", "Eve")]), [
      entry("e1", "Dinner", expense({ [ME]: "10.00" }, { "g:eve": "10.00" })),
      // A deleted entry is not in the way.
      deleted(entry("e2", "Old", expense({ "g:dave": "1.00" }, { [ME]: "1.00" }))),
    ]);
    const [dave, eve] = within(section).getAllByTestId("guest-row");
    expect(within(eve).getByRole("button", { name: "Remove" })).toBeDisabled();
    expect(within(eve).getByText("Named in entries — edit those first")).toBeInTheDocument();
    expect(within(dave).queryByText("Named in entries — edit those first")).not.toBeInTheDocument();
    await userEvent.click(within(dave).getByRole("button", { name: "Remove" }));
    expect(api.deleteGuest).toHaveBeenCalledWith("list-1", "g:dave");
  });

  it("shows a claimed guest's claimant instead of the lock, and Unclaim to the claimant alone", async () => {
    const section = await openProps(
      ledger([guest("g:dave", "Dave", { claimed_by: ME }), guest("g:eve", "Eve", { claimed_by: OTHER })]),
    );
    const [dave, eve] = within(section).getAllByTestId("guest-row");
    expect(within(dave).getByText("claimed by ME")).toBeInTheDocument();
    expect(within(dave).queryByRole("switch")).not.toBeInTheDocument();
    expect(within(dave).queryByRole("button", { name: "This is me" })).not.toBeInTheDocument();
    await userEvent.click(within(dave).getByRole("button", { name: "Unclaim" }));
    expect(api.unclaimGuest).toHaveBeenCalledWith("list-1", "g:dave");
    expect(within(eve).getByText("claimed by OT")).toBeInTheDocument();
    expect(within(eve).queryByRole("button", { name: "Unclaim" })).not.toBeInTheDocument();
  });

  it("says a refusal in words", async () => {
    vi.mocked(api.claimGuest).mockRejectedValue(new ApiError(409, "guest_claimed", "claimed"));
    const section = await openProps(ledger([DAVE]));
    await userEvent.click(within(section).getByRole("button", { name: "This is me" }));
    expect(await within(section).findByRole("alert")).toHaveTextContent("This guest has already been claimed");
  });

  it("is read-only on a closed list", async () => {
    const section = await openProps(ledger([DAVE, guest("g:eve", "Eve", { claimed_by: OTHER })], { closedAt: 1 }));
    expect(within(section).getByText("Dave")).toBeInTheDocument();
    expect(within(section).getByText("claimed by OT")).toBeInTheDocument();
    expect(within(section).queryAllByRole("button")).toEqual([]);
    expect(within(section).queryByRole("switch")).not.toBeInTheDocument();
    expect(within(section).queryByLabelText("Add guest")).not.toBeInTheDocument();
  });

  it("disables every control once I have agreed to close", async () => {
    const section = await openProps(ledger([DAVE], { closeVotes: [ME] }));
    for (const control of [...within(section).getAllByRole("button"), within(section).getByRole("switch")]) {
      expect(control).toBeDisabled();
    }
    expect(within(section).getByLabelText("Add guest")).toBeDisabled();
  });
});

describe("after joining: are you one of these?", () => {
  it("offers the unclaimed guests once, and claims the one picked", async () => {
    serve(ledger([DAVE, guest("g:eve", "Eve", { claimed_by: OTHER })]));
    renderAt("/list/list-1?joined=1");

    const dialog = await screen.findByRole("dialog", { name: "Are you one of these?" });
    expect(within(dialog).queryByRole("button", { name: /Eve/ })).not.toBeInTheDocument();
    // The flag is consumed: a reload would not ask again.
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\/list\/list-1$/));
    await userEvent.click(within(dialog).getByRole("button", { name: /Dave/ }));
    expect(api.claimGuest).toHaveBeenCalledWith("list-1", "g:dave");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("closes on No, I'm new, claiming nothing", async () => {
    serve(ledger([DAVE]));
    renderAt("/list/list-1?joined=1");

    const dialog = await screen.findByRole("dialog", { name: "Are you one of these?" });
    await userEvent.click(within(dialog).getByRole("button", { name: "No, I'm new" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(api.claimGuest).not.toHaveBeenCalled();
  });

  it("asks nothing without the flag, without unclaimed guests, or from a server without guests", async () => {
    for (const [path, list] of [
      ["/list/list-1", ledger([DAVE])],
      ["/list/list-1?joined=1", ledger([guest("g:eve", "Eve", { claimed_by: OTHER })])],
      ["/list/list-1?joined=1", ledger(undefined)],
    ] as const) {
      serve(list);
      renderAt(path);
      await screen.findByText("Trip");
      await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\/list\/list-1$/));
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      cleanup();
    }
  });

  it("is where redeeming an invite lands", async () => {
    vi.mocked(api.redeemInvite).mockResolvedValue({ list_id: "list-1" });
    serve(ledger([DAVE]));
    renderAt("/redeem");
    await userEvent.type(screen.getByLabelText("Invite code or link"), "abc.def");
    await userEvent.click(screen.getByRole("button", { name: "Join list" }));
    expect(await screen.findByRole("dialog", { name: "Are you one of these?" })).toBeInTheDocument();
  });

  it("is where joining from the overview's inbox lands", async () => {
    _resetInitialResumeForTests();
    vi.mocked(api.getPendingInvites).mockResolvedValue({
      invites: [
        {
          id: "inv",
          list_id: "list-1",
          list_name: "Camping",
          list_kind: "expenses",
          invited_by_initials: "OT",
          expires_at: Date.now() + 86_400_000,
          token: "tok",
        },
      ],
    });
    vi.mocked(api.redeemInvite).mockResolvedValue({ list_id: "list-1" });
    serve(ledger([DAVE]));
    renderAt("/");
    await userEvent.click(await screen.findByRole("button", { name: "Join" }));
    expect(await screen.findByRole("dialog", { name: "Are you one of these?" })).toBeInTheDocument();
  });
});

describe("creating a ledger with guests", () => {
  function pushedList() {
    return vi
      .mocked(api.sync)
      .mock.calls.flatMap((call) => call[0].changes.lists ?? [])
      .at(-1);
  }

  async function openCreate() {
    _resetInitialResumeForTests();
    serve(ledger([]));
    renderAt("/");
    await screen.findByText("Trip");
    await userEvent.click(screen.getByRole("button", { name: "New list" }));
    await userEvent.type(screen.getByLabelText("Name"), "Ski trip");
  }

  it("sends the typed names, blanks dropped and commas kept, on the creation row", async () => {
    await openCreate();
    expect(screen.queryByRole("group", { name: "Guests" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: /Ledger/ }));
    const group = screen.getByRole("group", { name: "Guests" });
    await userEvent.type(within(group).getByLabelText("Guest name"), "Dave, Jr.");
    await userEvent.click(within(group).getByRole("button", { name: "Add another" }));
    await userEvent.click(within(group).getByRole("button", { name: "Add another" }));
    await userEvent.click(within(group).getByRole("button", { name: "Add another" }));
    const boxes = within(group).getAllByLabelText("Guest name");
    expect(boxes).toHaveLength(4);
    await userEvent.type(boxes[1], "Remove me");
    await userEvent.type(boxes[3], "  Eve ");
    await userEvent.click(within(group).getAllByRole("button", { name: "Remove" })[1]);
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => expect(pushedList()?.fields.name?.value).toBe("Ski trip"));
    expect(pushedList()?.guest_names).toEqual(["Dave, Jr.", "Eve"]);
  });

  it("sends no guest_names when none were typed, and none for another kind", async () => {
    await openCreate();
    await userEvent.click(screen.getByRole("radio", { name: /Ledger/ }));
    await userEvent.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() => expect(pushedList()?.fields.name?.value).toBe("Ski trip"));
    expect(pushedList()).not.toHaveProperty("guest_names");
  });

  it("drops the names when the kind is changed away from Ledger", async () => {
    await openCreate();
    await userEvent.click(screen.getByRole("radio", { name: /Ledger/ }));
    await userEvent.type(screen.getByLabelText("Guest name"), "Dave");
    await userEvent.click(screen.getByRole("radio", { name: /Checklist/ }));
    expect(screen.queryByLabelText("Guest name")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() => expect(pushedList()?.fields.name?.value).toBe("Ski trip"));
    expect(pushedList()).not.toHaveProperty("guest_names");
  });

  it("stops offering another box at the server's cap", async () => {
    await openCreate();
    await userEvent.click(screen.getByRole("radio", { name: /Ledger/ }));
    const group = screen.getByRole("group", { name: "Guests" });
    const addAnother = within(group).getByRole("button", { name: "Add another" });
    for (let count = 1; count < MAX_NEW_GUESTS; count += 1) await userEvent.click(addAnother);
    expect(within(group).getAllByLabelText("Guest name")).toHaveLength(50);
    expect(within(group).queryByRole("button", { name: "Add another" })).not.toBeInTheDocument();
  });
});
