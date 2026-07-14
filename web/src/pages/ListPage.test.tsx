import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ListPage from "./ListPage";
import { SyncProvider } from "../hooks/SyncContext";
import * as api from "../api/client";

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
  status: "todo" | "checked" | "backlog",
  category: string | null = null,
) {
  return {
    id,
    list_id: "list-1",
    created_at: 0,
    fields: {
      name: clock(name),
      status: clock(status),
      category: clock(category),
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
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
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

  it("keeps a checked item in its own category, not a separate Checked section", async () => {
    vi.mocked(api.sync).mockReset();
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [listObj()],
        items: [
          itemObj("item-1", "Milk", "todo", "dairy"),
          itemObj("item-2", "Butter", "checked", "dairy"),
          itemObj("item-3", "Bread", "todo", "bakery"),
        ],
      },
    });

    renderListPage();
    await screen.findByText("Milk");
    await userEvent.click(screen.getByRole("button", { name: "Show checked" }));
    await screen.findByText("Butter");

    // No separate "Checked" heading exists anywhere.
    expect(screen.queryByText("Checked")).not.toBeInTheDocument();

    // Butter (checked) sits in the same DAIRY group as Milk (todo), not bakery.
    const dairyHeading = screen.getByText("dairy");
    const dairyGroup = dairyHeading.parentElement!;
    expect(dairyGroup.textContent).toContain("Milk");
    expect(dairyGroup.textContent).toContain("Butter");
    expect(dairyGroup.textContent).not.toContain("Bread");
  });
});

describe("ListPage last-touched-by indicator (T-64)", () => {
  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("shows the indicator with the right initials when the list has 2+ members", async () => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({
      members: [
        { account_id: "acc-a", email: "a@example.com", initials: "A", joined_at: 1 },
        { account_id: "acc-b", email: "b@example.com", initials: "B", joined_at: 2 },
      ],
      invites: [],
    });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [listObj()],
        items: [{ ...itemObj("item-1", "Milk", "todo"), last_touched_by: "acc-b" }],
      },
    });

    renderListPage();

    await screen.findByText("Milk");
    expect(await screen.findByText("B")).toBeInTheDocument();
    expect(screen.getByLabelText("Last touched by b@example.com")).toBeInTheDocument();
  });

  it("hides the indicator on a solo list even when last_touched_by is set", async () => {
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR", initials: "TE" });
    vi.mocked(api.getMembers).mockResolvedValue({ members: [], invites: [] });
    vi.mocked(api.sync).mockResolvedValueOnce({
      cursor: 1,
      changes: {
        lists: [listObj()],
        items: [{ ...itemObj("item-1", "Milk", "todo"), last_touched_by: "acc-a" }],
      },
    });

    renderListPage();

    await screen.findByText("Milk");
    expect(screen.queryByLabelText(/Last touched by/)).not.toBeInTheDocument();
  });
});
