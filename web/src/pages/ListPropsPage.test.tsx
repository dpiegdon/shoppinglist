import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ListPage from "./ListPage";
import ListPropsPage from "./ListPropsPage";
import { SyncProvider } from "../hooks/SyncContext";
import * as api from "../api/client";
import type { ItemStatus } from "../api/contract";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return { ...actual, sync: vi.fn(), getSettings: vi.fn(), getMembers: vi.fn() };
});

function clock<T>(value: T) {
  return { value, updated_at: 1, updated_by: "dev" };
}

function listObj(notes: string | null = null) {
  return {
    id: "list-1",
    created_at: 0,
    fields: {
      name: clock("Groceries"),
      category_order: clock([]),
      notes: clock(notes),
      deleted: clock(false),
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
      <SyncProvider>
        <Routes>
          <Route path="/list/:listId" element={<ListPage />} />
          <Route path="/list/:listId/properties" element={<ListPropsPage />} />
        </Routes>
      </SyncProvider>
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
        status: clock<ItemStatus>("todo"),
        deleted: clock(deleted),
      },
    };
  }

  it("pushes a solo-owned copy of the list and its non-deleted items, then navigates to it", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [listObj("Gate code: 4471")],
        items: [itemObj("item-1", "Milk"), itemObj("item-2", "Old", true)],
      },
    });

    await renderListPropsPageViaListPage();
    await screen.findByRole("button", { name: "Duplicate" });

    // Echo back whatever was pushed, like a real server round-trip - lets the test learn the
    // client-generated new list id rather than having to predict crypto.randomUUID()'s output.
    vi.mocked(api.sync).mockImplementationOnce(async (req) => ({
      cursor: 2,
      changes: { lists: req.changes.lists ?? [], items: req.changes.items ?? [] },
    }));

    await userEvent.click(screen.getByRole("button", { name: "Duplicate" }));

    await waitFor(() => {
      const call = vi.mocked(api.sync).mock.calls[1][0];
      const pushedLists = call.changes.lists!;
      const pushedItems = call.changes.items!;
      expect(pushedLists).toHaveLength(1);
      expect(pushedLists[0].id).not.toBe("list-1");
      expect(pushedLists[0].fields.name?.value).toBe("Groceries (Copy)");
      expect(pushedLists[0].fields.notes?.value).toBe("Gate code: 4471");
      // Only the non-deleted item was copied, as a fresh id, with its status preserved.
      expect(pushedItems).toHaveLength(1);
      expect(pushedItems[0].id).not.toBe("item-1");
      expect(pushedItems[0].list_id).toBe(pushedLists[0].id);
      expect(pushedItems[0].fields.name?.value).toBe("Milk");
      expect(pushedItems[0].fields.status?.value).toBe("todo");
    });

    await screen.findByText("Groceries (Copy)");
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
