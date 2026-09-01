import { render, screen, cleanup } from "@testing-library/react";
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
