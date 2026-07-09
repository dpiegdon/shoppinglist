import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ListPage from "./ListPage";
import { SyncProvider } from "../hooks/SyncContext";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return { ...actual, sync: vi.fn(), getSettings: vi.fn() };
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

function itemObj(id: string, name: string, status: "todo" | "checked" | "backlog") {
  return {
    id,
    list_id: "list-1",
    created_at: 0,
    fields: {
      name: clock(name),
      status: clock(status),
      category: clock(null),
      stores: clock([]),
      quantity: clock(null),
      price: clock(null),
      note: clock(null),
      deleted: clock(false),
    },
  };
}

function renderListPage() {
  return render(
    <MemoryRouter initialEntries={["/list/list-1"]}>
      <SyncProvider>
        <Routes>
          <Route path="/list/:listId" element={<ListPage />} />
        </Routes>
      </SyncProvider>
    </MemoryRouter>,
  );
}

describe("ListPage clear-checked", () => {
  beforeEach(() => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR" });
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
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("shows a count of checked items and moves them all to backlog on click", async () => {
    renderListPage();

    const clearButton = await screen.findByText("Clear checked (2)");

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

    // Cleared items are gone from the (now empty) checked count.
    await waitFor(() => expect(screen.queryByText(/Clear checked/)).not.toBeInTheDocument());
  });

  it("hides the clear-checked button when nothing is checked", async () => {
    vi.mocked(api.sync).mockReset();
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: { lists: [listObj()], items: [itemObj("item-1", "Milk", "todo")] },
    });

    renderListPage();

    await screen.findByText("Milk");
    expect(screen.queryByText(/Clear checked/)).not.toBeInTheDocument();
  });

  it("show-checked is a toggle button that reveals and hides checked items", async () => {
    renderListPage();
    await screen.findByText("Milk");

    const toggle = screen.getByRole("button", { name: "Show checked" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByText("Bread")).not.toBeInTheDocument();

    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("Bread")).toBeInTheDocument();
    expect(screen.getByText("Eggs")).toBeInTheDocument();

    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByText("Bread")).not.toBeInTheDocument();
  });
});
