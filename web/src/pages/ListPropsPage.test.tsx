import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ListPage from "./ListPage";
import ListPropsPage from "./ListPropsPage";
import { SyncProvider } from "../hooks/SyncContext";
import * as api from "../api/client";

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
    vi.mocked(api.getSettings).mockResolvedValue({ default_currency: "EUR" });
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
