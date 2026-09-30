import { render, screen, cleanup, fireEvent, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import RegistryPage from "./RegistryPage";
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

function listObj() {
  return {
    id: "list-1",
    created_at: 0,
    fields: { name: clock("Groceries"), category_order: clock([]), deleted: clock(false) },
  };
}

function itemObj(
  id: string,
  name: string,
  category: string | null = null,
  stores: string[] = [],
) {
  return {
    id,
    list_id: "list-1",
    created_at: 0,
    fields: {
      name: clock(name),
      status: clock<ItemStatus>("backlog"),
      category: clock(category),
      stores: clock(stores),
      quantity: clock(null),
      price: clock(null),
      note: clock(null),
      deleted: clock(false),
    },
  };
}

function renderRegistryPage() {
  return render(
    <MemoryRouter initialEntries={["/list/list-1/registry"]}>
      <SyncProvider>
        <Routes>
          <Route path="/list/:listId/registry" element={<RegistryPage />} />
        </Routes>
      </SyncProvider>
    </MemoryRouter>,
  );
}

describe("RegistryPage item dialog autocomplete", () => {
  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [listObj()],
        items: [
          itemObj("item-1", "Milk", "Dairy", ["Rewe"]),
          // No category and no stores of its own, so both chip rows have something to offer.
          itemObj("item-2", "Bread"),
        ],
      },
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("stays put while the first sync is still in flight (T-146)", async () => {
    renderRegistryPage();

    // `list` is undefined on the first render of every visit; this screen used to redirect to the
    // overview on that, so a reload or a deep link never reached the registry at all.
    expect(await screen.findByText("Milk")).toBeInTheDocument();
    expect(screen.getByText("Bread")).toBeInTheDocument();
  });

  it("shows each item's status in the app's language, not its wire value (T-186)", async () => {
    renderRegistryPage();

    expect(await screen.findByText("Milk")).toBeInTheDocument();
    expect(screen.getAllByText("Backlog").length).toBeGreaterThan(0);
    expect(screen.queryByText("backlog")).not.toBeInTheDocument();
  });

  it("offers the list's categories when editing an item from here (T-145)", async () => {
    renderRegistryPage();
    await userEvent.click(await screen.findByText("Bread"));

    // The dialog opened from this screen used to render without categorySuggestions, so the
    // chips silently did nothing here while working on the list screen.
    expect(await screen.findByLabelText("Category")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Dairy" })).toBeInTheDocument();
  });

  it("offers the list's stores when editing an item from here (T-139)", async () => {
    renderRegistryPage();
    await userEvent.click(await screen.findByText("Bread"));

    expect(await screen.findByLabelText("Stores")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Rewe" })).toBeInTheDocument();
  });
});

describe("RegistryPage due date (T-323)", () => {
  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  function mountWith(kind: "shopping" | "checklist") {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValue({ cursor: 2, changes: { lists: [], items: [] } });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [{ ...listObj(), fields: { ...listObj().fields, kind: clock(kind) } }],
        items: [itemObj("item-1", "Passport")],
      },
    });
    renderRegistryPage();
  }

  it("sets a due date when editing a checklist's item from here", async () => {
    mountWith("checklist");
    await userEvent.click(await screen.findByText("Passport"));
    fireEvent.change(await screen.findByLabelText("Due"), { target: { value: "2026-12-01" } });
    await userEvent.click(screen.getByText("Save"));

    const pushed = vi
      .mocked(api.sync)
      .mock.calls.flatMap((c) => c[0].changes.items ?? []);
    expect(pushed).toHaveLength(1);
    expect(Object.keys(pushed[0].fields)).toEqual(["due"]);
    expect(pushed[0].fields.due!.value).toBe("2026-12-01");
  });

  it("does not offer it on a shopping list", async () => {
    mountWith("shopping");
    await userEvent.click(await screen.findByText("Passport"));
    expect(await screen.findByLabelText("Note")).toBeInTheDocument();
    expect(screen.queryByLabelText("Due")).not.toBeInTheDocument();
  });
});

describe("RegistryPage rows, as on Android (T-342)", () => {
  /** A server that answers every write with the rows as they now stand, as the real one does. */
  function serveEcho() {
    const rows = new Map([
      ["item-1", itemObj("item-1", "Milk")],
      ["item-2", itemObj("item-2", "Bread")],
    ]);
    let first = true;
    vi.mocked(api.sync).mockImplementation(async (request) => {
      if (first) {
        first = false;
        return { cursor: 1, changes: { lists: [listObj()], items: [...rows.values()] } } as Awaited<
          ReturnType<typeof api.sync>
        >;
      }
      const changed = (request.changes.items ?? []).map((sent) => {
        const row = rows.get(sent.id)!;
        const next = { ...row, fields: { ...row.fields, ...sent.fields } } as typeof row;
        rows.set(sent.id, next);
        return next;
      });
      return { cursor: 2, changes: { lists: [], items: changed } } as Awaited<ReturnType<typeof api.sync>>;
    });
  }

  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
  });

  afterEach(() => {
    vi.mocked(api.sync).mockReset();
    vi.clearAllMocks();
    cleanup();
  });

  function sentDeleted() {
    return vi
      .mocked(api.sync)
      .mock.calls.flatMap((c) => c[0].changes.items ?? [])
      .map((item) => [item.id, item.fields.deleted?.value]);
  }

  it("deletes a row at once and offers it back", async () => {
    serveEcho();
    renderRegistryPage();

    await userEvent.click(await screen.findByRole("button", { name: "Delete Milk" }));

    expect(sentDeleted()).toEqual([["item-1", true]]);
    expect(await screen.findByRole("status")).toHaveTextContent("Milk deleted");
    expect(screen.queryByRole("button", { name: "Delete Milk" })).not.toBeInTheDocument();
    // No dialog opened on the way: the row's delete is its own button, not the row's.
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await userEvent.click(within(screen.getByRole("status")).getByRole("button", { name: "Undo" }));

    expect(sentDeleted()).toEqual([
      ["item-1", true],
      ["item-1", false],
    ]);
    expect(await screen.findByRole("button", { name: "Delete Milk" })).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("labels the search field and leads back to the list from the catalogue", async () => {
    serveEcho();
    renderRegistryPage();

    expect(await screen.findByRole("searchbox", { name: "Search" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "← Groceries" })).toHaveAttribute("href", "/list/list-1");
  });

  it("says it is loading, not that the list is gone, until the first sync lands", async () => {
    vi.mocked(api.sync).mockReturnValue(new Promise(() => {}));
    renderRegistryPage();

    expect(await screen.findByText("Loading…")).toBeInTheDocument();
    expect(screen.queryByText(/List not found/)).toBeNull();
  });
});
