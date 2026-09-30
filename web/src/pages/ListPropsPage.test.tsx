import { render, screen, waitFor, cleanup, within, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ListPage from "./ListPage";
import ListPropsPage from "./ListPropsPage";
import { SyncProvider, useSyncContext } from "../hooks/SyncContext";
import { AuthProvider } from "../auth/AuthContext";
import { I18nProvider } from "../i18n";
import { de } from "../i18n/messages/de";
import * as api from "../api/client";
import type { ItemStatus } from "../api/contract";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return {
    ...actual,
    sync: vi.fn(),
    getSettings: vi.fn(),
    getMembers: vi.fn(),
    mintInvite: vi.fn(),
    leaveList: vi.fn(),
  };
});

function clock<T>(value: T) {
  return { value, updated_at: 1, updated_by: "dev" };
}

function listObj(notes: string | null = null, kind: "shopping" | "checklist" | "expenses" | null = null) {
  return {
    id: "list-1",
    created_at: 0,
    fields: {
      name: clock("Groceries"),
      category_order: clock([]),
      notes: clock(notes),
      deleted: clock(false),
      ...(kind ? { kind: clock(kind) } : {}),
    },
  };
}

// ListPropsPage navigates to "/" immediately if the list isn't in sync state yet (unlike
// ListPage, which shows an inline placeholder) — so a cold direct render races the async sync
// fetch and bounces away before it resolves. Mirror real navigation instead: load ListPage
// first (populates SyncContext), then click through to properties, exactly like a user would.
async function renderListPropsPageViaListPage() {
  const utils = render(
    <MemoryRouter initialEntries={["/list/list-1"]}>
      {/* The page reads the signed-in account since T-159, to decide whether the close-vote
          control offers to agree or to withdraw. */}
      <AuthProvider>
        <SyncProvider>
          <Routes>
            <Route path="/list/:listId" element={<ListPage />} />
            <Route path="/list/:listId/properties" element={<ListPropsPage />} />
          </Routes>
        </SyncProvider>
      </AuthProvider>
    </MemoryRouter>,
  );
  await screen.findByText("Groceries");
  await userEvent.click(screen.getByRole("link", { name: "List properties" }));
  return utils;
}

describe("ListPropsPage notes (T-62)", () => {
  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("opened directly — a reload or a deep link — it stays and fills its fields once the list arrives (T-188)", async () => {
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj("Gate code: 4471")], items: [] },
    });

    render(
      <MemoryRouter initialEntries={["/list/list-1/properties"]}>
        <AuthProvider>
          <SyncProvider>
            <Routes>
              <Route path="/list/:listId/properties" element={<ListPropsPage />} />
              <Route path="/" element={<div>Overview page</div>} />
            </Routes>
          </SyncProvider>
        </AuthProvider>
      </MemoryRouter>,
    );

    // It used to redirect on the first render, before the first sync had delivered the list.
    const textarea = await screen.findByPlaceholderText("Gate code, store hours, anything worth remembering…");
    await waitFor(() => expect(textarea).toHaveValue("Gate code: 4471"));
    expect(screen.getByDisplayValue("Groceries")).toBeInTheDocument();
    expect(screen.queryByText("Overview page")).not.toBeInTheDocument();
  });

  it("loads an existing note into the textarea", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj("Gate code: 4471")], items: [] },
    });

    await renderListPropsPageViaListPage();

    const textarea = await screen.findByPlaceholderText("Gate code, store hours, anything worth remembering…");
    await waitFor(() => expect(textarea).toHaveValue("Gate code: 4471"));
  });

  it("typing a note and saving pushes it as an LWW field patch", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj()], items: [] },
    });

    await renderListPropsPageViaListPage();

    const textarea = await screen.findByPlaceholderText("Gate code, store hours, anything worth remembering…");

    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 2,
      changes: { lists: [listObj("Store closes at 6pm")], items: [] },
    });

    await userEvent.type(textarea, "Store closes at 6pm");
    await userEvent.click(screen.getByRole("button", { name: "Save notes" }));

    await waitFor(() => {
      const call = vi.mocked(api.sync).mock.calls[1][0];
      const pushedList = call.changes.lists![0];
      expect(pushedList.fields.notes?.value).toBe("Store closes at 6pm");
    });
  });

  it("saving a blank note pushes null, not an empty string", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj("temporary")], items: [] },
    });

    await renderListPropsPageViaListPage();

    const textarea = await screen.findByPlaceholderText("Gate code, store hours, anything worth remembering…");
    await waitFor(() => expect(textarea).toHaveValue("temporary"));

    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 2,
      changes: { lists: [listObj()], items: [] },
    });

    await userEvent.clear(textarea);
    await userEvent.click(screen.getByRole("button", { name: "Save notes" }));

    await waitFor(() => {
      const call = vi.mocked(api.sync).mock.calls[1][0];
      const pushedList = call.changes.lists![0];
      expect(pushedList.fields.notes?.value).toBeNull();
    });
  });
});

describe("ListPropsPage duplicate list (T-63)", () => {
  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  function itemObj(id: string, name: string, deleted = false) {
    return {
      id,
      list_id: "list-1",
      created_at: 0,
      fields: {
        name: clock(name),
        category: clock("dairy"),
        stores: clock(["Rewe"]),
        quantity: clock("2l"),
        price: clock(null),
        note: clock(null),
        due: clock("2026-10-04"),
        status: clock<ItemStatus>("todo"),
        deleted: clock(deleted),
      },
    };
  }

  it("pushes a solo-owned copy of the list and its non-deleted items, then navigates to it", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        // A non-default kind (T-267): duplicating it must carry the kind along, not fall back to
        // the server's default (shopping) by omitting the field.
        lists: [listObj("Gate code: 4471", "checklist")],
        items: [itemObj("item-1", "Milk"), itemObj("item-2", "Old", true)],
      },
    });

    await renderListPropsPageViaListPage();
    await screen.findByRole("button", { name: "Duplicate list" });

    // Echo back whatever was pushed, like a real server round-trip - lets the test learn the
    // client-generated new list id rather than having to predict crypto.randomUUID()'s output.
    vi.mocked(api.sync).mockImplementationOnce(async (req) => ({
      cursor: 2,
      changes: { lists: req.changes.lists ?? [], items: req.changes.items ?? [] },
    }));

    await userEvent.click(screen.getByRole("button", { name: "Duplicate list" }));

    await waitFor(() => {
      const call = vi.mocked(api.sync).mock.calls[1][0];
      const pushedLists = call.changes.lists!;
      const pushedItems = call.changes.items!;
      expect(pushedLists).toHaveLength(1);
      expect(pushedLists[0].id).not.toBe("list-1");
      expect(pushedLists[0].fields.name?.value).toBe("Groceries (Copy)");
      expect(pushedLists[0].fields.notes?.value).toBe("Gate code: 4471");
      expect(pushedLists[0].fields.kind?.value).toBe("checklist");
      // Only the non-deleted item was copied, as a fresh id, with its status preserved.
      expect(pushedItems).toHaveLength(1);
      expect(pushedItems[0].id).not.toBe("item-1");
      expect(pushedItems[0].list_id).toBe(pushedLists[0].id);
      expect(pushedItems[0].fields.name?.value).toBe("Milk");
      expect(pushedItems[0].fields.status?.value).toBe("todo");
      // The due date travels with the copy like every other item field (T-323).
      expect(pushedItems[0].fields.due?.value).toBe("2026-10-04");
    });

    await screen.findByText("Groceries (Copy)");
  });

  it("shows an inline error and does not navigate when the push fails, instead of failing silently (T-266)", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj()], items: [itemObj("item-1", "Milk")] },
    });

    await renderListPropsPageViaListPage();
    await screen.findByRole("button", { name: "Duplicate list" });

    vi.mocked(api.sync).mockRejectedValueOnce(new Error("network down"));
    await userEvent.click(screen.getByRole("button", { name: "Duplicate list" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Failed to save. Please try again.");
    // Still on the source list's properties page — it never navigated to a half-made copy.
    expect(screen.getByRole("heading", { name: "List properties" })).toBeInTheDocument();
    expect(screen.queryByText("Groceries (Copy)")).not.toBeInTheDocument();
  });
});

describe("ListPropsPage clear-checked (T-75)", () => {
  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  function itemObj(id: string, name: string, status: ItemStatus) {
    return {
      id,
      list_id: "list-1",
      created_at: 0,
      fields: {
        name: clock(name),
        category: clock(null),
        stores: clock([]),
        quantity: clock(null),
        price: clock(null),
        note: clock(null),
        status: clock<ItemStatus>(status),
        deleted: clock(false),
      },
    };
  }

  it("shows the checked count and moves every checked item to backlog on click", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [listObj()],
        items: [
          itemObj("item-1", "Milk", "todo"),
          itemObj("item-2", "Bread", "checked"),
          itemObj("item-3", "Eggs", "checked"),
        ],
      },
    });

    await renderListPropsPageViaListPage();

    const clearButton = await screen.findByRole("button", { name: "Clear checked (2)" });

    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 2,
      changes: {
        lists: [],
        items: [itemObj("item-2", "Bread", "backlog"), itemObj("item-3", "Eggs", "backlog")],
      },
    });

    await userEvent.click(clearButton);

    await waitFor(() => {
      const call = vi.mocked(api.sync).mock.calls[1][0];
      expect(call.changes.items).toHaveLength(2);
      const ids = call.changes.items!.map((i) => i.id).sort();
      expect(ids).toEqual(["item-2", "item-3"]);
      for (const item of call.changes.items!) {
        expect(item.fields.status?.value).toBe("backlog");
      }
    });

    // Nothing left to clear, so the section disappears.
    await waitFor(() => expect(screen.queryByText(/Clear checked/)).not.toBeInTheDocument());
  });

  it("hides the clear-checked section when nothing is checked", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj()], items: [itemObj("item-1", "Milk", "todo")] },
    });

    await renderListPropsPageViaListPage();

    await screen.findByRole("heading", { name: "List properties" });
    expect(screen.queryByText(/Clear checked/)).not.toBeInTheDocument();
  });
});

describe("ListPropsPage invite link (T-83)", () => {
  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("surfaces the minted invite URL with a copy control after inviting", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj()], items: [] },
    });
    vi.mocked(api.mintInvite).mockResolvedValue({
      invite_id: "inv-1",
      token: "tok-abc",
      url: "http://testserver/invite/tok-abc",
      expires_at: 9999999999999,
    });

    await renderListPropsPageViaListPage();

    const emailInput = await screen.findByPlaceholderText("Invite by email");
    await userEvent.type(emailInput, "friend@example.com");
    await userEvent.click(screen.getByRole("button", { name: "Invite" }));

    // The link the inviter needs is now shown (and passed the invited email to mint).
    const linkField = await screen.findByLabelText("Invite link");
    expect(linkField).toHaveValue("http://testserver/invite/tok-abc");
    expect(screen.getByText(/friend@example\.com/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
    expect(vi.mocked(api.mintInvite)).toHaveBeenCalledWith("list-1", "friend@example.com");
  });
});

describe("ListPropsPage leave list (T-268)", () => {
  // A tiny consumer that renders straight from the sync store, so a test can see what leaving
  // actually did to it — not just that navigation happened, which would pass even if the list
  // were still sitting in state waiting for a reload.
  function SyncStoreDebug() {
    const { lists, items } = useSyncContext();
    return (
      <div>
        Lists: {lists.size} Items: {items.size}
      </div>
    );
  }

  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
    vi.restoreAllMocks();
  });

  it("forgets the list and its items locally once the server confirms the leave, before any next pull could", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [listObj()],
        items: [
          {
            id: "item-1",
            list_id: "list-1",
            created_at: 0,
            fields: {
              name: clock("Milk"),
              category: clock(null),
              stores: clock([]),
              quantity: clock(null),
              price: clock(null),
              note: clock(null),
              status: clock<ItemStatus>("todo"),
              deleted: clock(false),
            },
          },
        ],
      },
    });
    vi.mocked(api.leaveList).mockResolvedValue(undefined);

    render(
      <MemoryRouter initialEntries={["/list/list-1"]}>
        <AuthProvider>
          <SyncProvider>
            <Routes>
              <Route path="/list/:listId" element={<ListPage />} />
              <Route path="/list/:listId/properties" element={<ListPropsPage />} />
              <Route path="/" element={<SyncStoreDebug />} />
            </Routes>
          </SyncProvider>
        </AuthProvider>
      </MemoryRouter>,
    );
    await screen.findByText("Groceries");
    await userEvent.click(screen.getByRole("link", { name: "List properties" }));

    await userEvent.click(await screen.findByRole("button", { name: "Leave list" }));

    expect(await screen.findByText("Lists: 0 Items: 0")).toBeInTheDocument();
    expect(api.leaveList).toHaveBeenCalledWith("list-1");
    // No pull was needed to make the list disappear — the server sends no tombstone for a leave,
    // since other members keep the list, so a next sync's delta would say nothing about it either.
    expect(vi.mocked(api.sync).mock.calls).toHaveLength(1);
  });

  it("shows an inline error and stays on the list when the leave itself is refused, instead of forgetting it anyway (T-266)", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj()], items: [] },
    });
    vi.mocked(api.leaveList).mockRejectedValue(new Error("network down"));

    render(
      <MemoryRouter initialEntries={["/list/list-1"]}>
        <AuthProvider>
          <SyncProvider>
            <Routes>
              <Route path="/list/:listId" element={<ListPage />} />
              <Route path="/list/:listId/properties" element={<ListPropsPage />} />
              <Route path="/" element={<SyncStoreDebug />} />
            </Routes>
          </SyncProvider>
        </AuthProvider>
      </MemoryRouter>,
    );
    await screen.findByText("Groceries");
    await userEvent.click(screen.getByRole("link", { name: "List properties" }));

    await userEvent.click(await screen.findByRole("button", { name: "Leave list" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Failed to save. Please try again.");
    // Still on the properties page for this list — it did not navigate away or forget it.
    expect(screen.getByRole("heading", { name: "List properties" })).toBeInTheDocument();
  });
});

// ---- reordering categories (T-212) -----------------------------------------------------------

describe("the category editor, on Android's model (T-212, T-340)", () => {
  function listWithOrder(order: string[]) {
    const base = listObj();
    return { ...base, fields: { ...base.fields, category_order: clock(order) } };
  }

  function categoryItem(id: string, category: string) {
    return {
      id,
      list_id: "list-1",
      created_at: 0,
      fields: {
        name: clock(id),
        category: clock(category),
        stores: clock([]),
        quantity: clock(null),
        price: clock(null),
        note: clock(null),
        status: clock<ItemStatus>("todo"),
        deleted: clock(false),
      },
    };
  }

  /** The category_order of the first list push, if any. */
  function pushedOrder(): string[] | undefined {
    const call = vi
      .mocked(api.sync)
      .mock.calls.map((c) => c[0])
      .find((req) => (req.changes.lists?.length ?? 0) > 0);
    return call?.changes.lists?.[0].fields.category_order?.value as string[] | undefined;
  }

  /** The category rows' names, top to bottom. */
  function rowNames() {
    return screen.getAllByTestId("category-row").map((row) => row.querySelector("span")!.textContent);
  }

  beforeEach(() => {
    api.setToken("test-token");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: "acct-me", email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
  });

  afterEach(() => {
    vi.clearAllMocks();
    vi.restoreAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("lists every category, the stored order first and the rest in name order, each with a pencil and a handle, and no arrows or add field", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [listWithOrder(["Frozen", "Dairy"])],
        items: [categoryItem("i1", "snacks"), categoryItem("i2", "Dairy"), categoryItem("i3", "Bakery")],
      },
    });
    await renderListPropsPageViaListPage();

    await screen.findByRole("button", { name: "Reorder Frozen" });
    expect(rowNames()).toEqual(["Frozen", "Dairy", "Bakery", "snacks"]);
    expect(screen.getByRole("button", { name: "Rename snacks" })).toBeInTheDocument();
    expect(
      screen.getByText(
        "Drag a handle to set the order items are grouped in, then save the order. ✎ renames a category, fixes its casing or merges it into another.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Move up" })).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText("Add category…")).not.toBeInTheDocument();
    // Nothing has moved, so there is nothing to save.
    expect(screen.getByRole("button", { name: "Save order" })).toBeDisabled();
  });

  it("ArrowDown on a handle moves its row, keeps the focus there and says where it went; Save order then saves the order shown", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listWithOrder(["Dairy", "Bread"])], items: [categoryItem("i1", "Fruit")] },
    });
    await renderListPropsPageViaListPage();

    const handle = await screen.findByRole("button", { name: "Reorder Dairy" });
    handle.focus();
    await userEvent.keyboard("{ArrowDown}");

    expect(rowNames()).toEqual(["Bread", "Dairy", "Fruit"]);
    expect(screen.getByRole("button", { name: "Reorder Dairy" })).toHaveFocus();
    expect(screen.getByText("Dairy moved to position 2 of 3.")).toBeInTheDocument();
    // Moving saves nothing yet.
    expect(pushedOrder()).toBeUndefined();

    await userEvent.click(screen.getByRole("button", { name: "Save order" }));
    await waitFor(() => expect(pushedOrder()).toEqual(["Bread", "Dairy", "Fruit"]));
    const card = screen.getByRole("heading", { name: "Categories", level: 2 }).closest("section")!;
    expect(await within(card).findByRole("status")).toHaveTextContent("Saved.");
    expect(screen.getByRole("button", { name: "Save order" })).toBeDisabled();
  });

  it("moves past a stale entry stored between two rows in one press, and saves a clean order", async () => {
    // "dairy" is a second casing of "Dairy", left from before categories were case-insensitive: it
    // is stored but not shown. Swapping RAW positions swapped Bread with it, and nothing moved.
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listWithOrder(["Dairy", "dairy", "Bread"])], items: [] },
    });
    await renderListPropsPageViaListPage();

    const handle = await screen.findByRole("button", { name: "Reorder Bread" });
    handle.focus();
    await userEvent.keyboard("{ArrowUp}");
    await userEvent.click(screen.getByRole("button", { name: "Save order" }));

    await waitFor(() => expect(pushedOrder()).toEqual(["Bread", "Dairy"]));
  });

  it("dragging a handle moves its row one place per row height travelled", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listWithOrder(["Dairy", "Bread", "Fruit", "Frozen"])], items: [] },
    });
    // jsdom lays nothing out: every row is 40px tall here.
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      bottom: 40,
      right: 300,
      width: 300,
      height: 40,
      toJSON: () => ({}),
    });
    await renderListPropsPageViaListPage();

    const handle = await screen.findByRole("button", { name: "Reorder Dairy" });
    fireEvent.pointerDown(handle, { button: 0, pointerId: 1, clientY: 0 });
    // 2.5 rows down, in small steps: two swaps.
    for (let y = 10; y <= 100; y += 10) {
      fireEvent.pointerMove(screen.getByRole("button", { name: "Reorder Dairy" }), { pointerId: 1, clientY: y });
    }
    fireEvent.pointerUp(screen.getByRole("button", { name: "Reorder Dairy" }), { pointerId: 1, clientY: 100 });

    expect(rowNames()).toEqual(["Bread", "Fruit", "Dairy", "Frozen"]);
    expect(screen.getByRole("button", { name: "Save order" })).toBeEnabled();
  });

  it("a failed save shows its error in the card and keeps the moved order to try again, instead of pretending it was saved (T-266)", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listWithOrder(["Dairy", "Bread"])], items: [] },
    });
    await renderListPropsPageViaListPage();
    const handle = await screen.findByRole("button", { name: "Reorder Dairy" });
    handle.focus();
    await userEvent.keyboard("{ArrowDown}");

    vi.mocked(api.sync).mockRejectedValueOnce(new Error("network down"));
    await userEvent.click(screen.getByRole("button", { name: "Save order" }));

    const card = screen.getByRole("heading", { name: "Categories", level: 2 }).closest("section")!;
    expect(await within(card).findByRole("alert")).toHaveTextContent("Failed to save. Please try again.");
    expect(within(card).queryByText("Saved.")).not.toBeInTheDocument();
    expect(rowNames()).toEqual(["Bread", "Dairy"]);
    expect(screen.getByRole("button", { name: "Save order" })).toBeEnabled();
  });

  it("with no categories at all, says so and offers nothing to save", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [listObj()], items: [] } });
    await renderListPropsPageViaListPage();

    expect(await screen.findByText("No categories yet.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save order" })).not.toBeInTheDocument();
  });
});

describe("list properties save feedback, type switch and labels (T-340)", () => {
  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
  });

  afterEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    cleanup();
  });

  it("Save is offered only once the name changes, and says Saved in the List card", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [listObj()], items: [] } });
    await renderListPropsPageViaListPage();

    const field = await screen.findByLabelText("List name");
    const listCard = screen.getByRole("heading", { name: "List", level: 2 }).closest("section")!;
    const save = within(listCard).getByRole("button", { name: "Save" });
    expect(save).toBeDisabled();

    await userEvent.type(field, " Weekly");
    expect(save).toBeEnabled();
    await userEvent.click(save);

    await waitFor(() => expect(vi.mocked(api.sync).mock.calls[1][0].changes.lists![0].fields.name?.value).toBe("Groceries Weekly"));
    expect(await within(listCard).findByRole("status")).toHaveTextContent("Saved.");
    expect(save).toBeDisabled();
    // Editing again takes the note away.
    await userEvent.type(field, "!");
    expect(within(listCard).queryByRole("status")).not.toBeInTheDocument();
  });

  it("Save notes is offered only once the notes change, and says Saved in the Notes card", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [listObj("Gate 4471")], items: [] } });
    await renderListPropsPageViaListPage();

    const notes = await screen.findByRole("textbox", { name: "Notes" });
    await waitFor(() => expect(notes).toHaveValue("Gate 4471"));
    const save = screen.getByRole("button", { name: "Save notes" });
    expect(save).toBeDisabled();
    await userEvent.type(notes, "  ");
    // Only trailing blanks: saved it would be the same note.
    expect(save).toBeDisabled();
    await userEvent.type(notes, "!");
    await userEvent.click(save);

    const notesCard = screen.getByRole("heading", { name: "Notes", level: 2 }).closest("section")!;
    expect(await within(notesCard).findByRole("status")).toHaveTextContent("Saved.");
  });

  it("the type is its icon, its name and a switch named Checklist, which converts the list", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [listObj()], items: [] } });
    await renderListPropsPageViaListPage();

    const toggle = await screen.findByRole("switch", { name: "Checklist" });
    expect(toggle).toHaveAttribute("aria-checked", "false");
    const listCard = screen.getByRole("heading", { name: "List", level: 2 }).closest("section")!;
    expect(within(listCard).getByText("🛒")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Make checklist" })).not.toBeInTheDocument();

    await userEvent.click(toggle);
    await waitFor(() => expect(vi.mocked(api.sync).mock.calls[1][0].changes.lists![0].fields.kind?.value).toBe("checklist"));
  });

  it("a pending invite is a roster row marked pending, with a full-size Revoke", async () => {
    vi.mocked(api.getMembers).mockResolvedValue({
      members: [{ account_id: "acct-me", email: "me@example.com" }],
      invites: [{ id: "inv-1", invited_email: "sam@example.com", expires_at: 9999999999999 }],
    } as unknown as Awaited<ReturnType<typeof api.getMembers>>);
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [listObj()], items: [] } });
    await renderListPropsPageViaListPage();

    expect(await screen.findByText("sam@example.com (pending)")).toBeInTheDocument();
    expect(screen.queryByText("Pending invites")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Revoke" })).not.toHaveClass("btn-sm");
    expect(screen.getByRole("textbox", { name: "Invite by email" })).toBeInTheDocument();
  });

  it("the back link's arrow comes from the translation, so it points back in Arabic", async () => {
    localStorage.setItem("shoppinglist_locale", "ar");
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [listObj()], items: [] } });
    render(
      <MemoryRouter initialEntries={["/list/list-1/properties"]}>
        <I18nProvider>
          <AuthProvider>
            <SyncProvider>
              <Routes>
                <Route path="/list/:listId/properties" element={<ListPropsPage />} />
              </Routes>
            </SyncProvider>
          </AuthProvider>
        </I18nProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("link", { name: "→ Groceries" })).toBeInTheDocument();
  });
});

describe("category merge confirmation goes through the catalog (T-270)", () => {
  function listWithOrder(order: string[]) {
    const base = listObj();
    return { ...base, fields: { ...base.fields, category_order: clock(order) } };
  }

  beforeEach(() => {
    api.setToken("test-token");
    localStorage.setItem("shoppinglist_locale", "de");
    localStorage.setItem(
      "shoppinglist_account",
      JSON.stringify({ id: "acct-me", email: "me@example.com", isAdmin: false }),
    );
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
  });

  afterEach(() => {
    vi.clearAllMocks();
    api.setToken(null);
    localStorage.clear();
    cleanup();
  });

  it("shows the merge confirmation in the active language, not hard-coded English", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listWithOrder(["Dairy", "Produce"])], items: [] },
    });
    // Declines the merge — this test only cares what text confirm() was shown, not the push.
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);

    render(
      <MemoryRouter initialEntries={["/list/list-1"]}>
        <I18nProvider>
          <AuthProvider>
            <SyncProvider>
              <Routes>
                <Route path="/list/:listId" element={<ListPage />} />
                <Route path="/list/:listId/properties" element={<ListPropsPage />} />
              </Routes>
            </SyncProvider>
          </AuthProvider>
        </I18nProvider>
      </MemoryRouter>,
    );
    await screen.findByText("Groceries");
    await userEvent.click(screen.getByRole("link", { name: de["listProps.title"] }));
    await userEvent.click(
      screen.getByRole("button", { name: de["listProps.renameCategory"]!.replace("{category}", "Produce") }),
    );
    const input = screen.getByRole("textbox", {
      name: de["listProps.renameCategory"]!.replace("{category}", "Produce"),
    });
    await userEvent.clear(input);
    await userEvent.type(input, "Dairy");
    const renameForm = input.closest("form")!;
    await userEvent.click(within(renameForm).getByRole("button", { name: de["action.save"] }));

    expect(confirmSpy).toHaveBeenCalledWith(de["listProps.mergeCategoryConfirm"]!.replace("{category}", "Dairy"));
    confirmSpy.mockRestore();
  });

  it("names a copy in the language of whoever makes it (T-302)", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj()], items: [] },
    });

    render(
      <MemoryRouter initialEntries={["/list/list-1"]}>
        <I18nProvider>
          <AuthProvider>
            <SyncProvider>
              <Routes>
                <Route path="/list/:listId" element={<ListPage />} />
                <Route path="/list/:listId/properties" element={<ListPropsPage />} />
              </Routes>
            </SyncProvider>
          </AuthProvider>
        </I18nProvider>
      </MemoryRouter>,
    );
    await screen.findByText("Groceries");
    await userEvent.click(screen.getByRole("link", { name: de["listProps.title"] }));
    vi.mocked(api.sync).mockImplementationOnce(async (req) => ({
      cursor: 2,
      changes: { lists: req.changes.lists ?? [], items: req.changes.items ?? [] },
    }));
    await userEvent.click(await screen.findByRole("button", { name: de["action.duplicate"] }));

    await waitFor(() => {
      const pushed = vi.mocked(api.sync).mock.calls[1][0].changes.lists!;
      expect(pushed[0].fields.name?.value).toBe("Groceries (Kopie)");
    });
  });
});

describe("ListPropsPage sections in cards, in the order both clients share (T-337)", () => {
  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  function renderProperties() {
    render(
      <MemoryRouter initialEntries={["/list/list-1/properties"]}>
        <AuthProvider>
          <SyncProvider>
            <Routes>
              <Route path="/list/:listId/properties" element={<ListPropsPage />} />
            </Routes>
          </SyncProvider>
        </AuthProvider>
      </MemoryRouter>,
    );
  }

  /** The visible section titles, top to bottom, each checked to head a card. */
  function cardTitles() {
    const titles = screen.getAllByRole("heading", { level: 2 });
    for (const title of titles) expect(title.closest("section")).toHaveClass("card");
    return titles.map((title) => title.textContent);
  }

  function checkedItem() {
    return {
      id: "item-1",
      list_id: "list-1",
      created_at: 0,
      fields: {
        name: clock("Milk"),
        category: clock(null),
        stores: clock([]),
        quantity: clock(null),
        price: clock(null),
        note: clock(null),
        status: clock<ItemStatus>("checked"),
        deleted: clock(false),
      },
    };
  }

  it("a shopping list: List (Clear checked last in it), Categories, Notes, Members, Actions, then Leave in a red card", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({ cursor: 1, changes: { lists: [listObj()], items: [checkedItem()] } });
    renderProperties();

    const clear = await screen.findByRole("button", { name: "Clear checked (1)" });
    expect(cardTitles()).toEqual(["List", "Categories", "Notes", "Members", "Actions", "Leave list"]);

    const listCard = screen.getByRole("heading", { name: "List", level: 2 }).closest("section")!;
    expect(clear.closest("section")).toBe(listCard);
    // The last thing in the List card, under the type.
    expect(listCard.lastElementChild).toContainElement(clear);
    expect(within(listCard).getByRole("heading", { name: "Type" })).toBeInTheDocument();

    const actions = screen.getByRole("heading", { name: "Actions", level: 2 }).closest("section")!;
    expect(within(actions).getByRole("button", { name: "Duplicate list" })).toBeInTheDocument();

    const leaveTitle = screen.getByRole("heading", { name: "Leave list", level: 2 });
    const leaveCard = leaveTitle.closest("section")!;
    expect(leaveCard.style.borderColor).toBe("var(--color-danger)");
    expect(leaveTitle.style.color).toBe("var(--color-danger)");
    expect(within(leaveCard).getByRole("button", { name: "Leave list" })).toBeInTheDocument();
    // Plain cards carry no danger border.
    for (const title of ["List", "Categories", "Notes", "Members", "Actions"]) {
      expect(screen.getByRole("heading", { name: title, level: 2 }).closest("section")!.style.borderColor).toBe("");
    }
  });

  it("a ledger: no categories or actions, and Closing directly above Leave", async () => {
    const ledger = listObj(null, "expenses");
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [{ ...ledger, fields: { ...ledger.fields, currency: clock("EUR") } }], items: [] },
    });
    renderProperties();

    await screen.findByRole("heading", { name: "Closing", level: 2 });
    expect(cardTitles()).toEqual(["List", "Notes", "Members", "Closing", "Leave list"]);
    const leaveCard = screen.getByRole("heading", { name: "Leave list", level: 2 }).closest("section")!;
    expect(within(leaveCard).getByText("A ledger can only be left once it is closed.")).toBeInTheDocument();
  });
});
