import { render, screen, waitFor, cleanup } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import OverviewPage, { LAST_LIST_STORAGE_KEY, _resetInitialResumeForTests } from "./OverviewPage";
import { SyncProvider } from "../hooks/SyncContext";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return { ...actual, sync: vi.fn() };
});

function syncResponse(listId: string) {
  return {
    cursor: 1,
    changes: {
      lists: [
        {
          id: listId,
          created_at: 0,
          fields: {
            name: { value: "My List", updated_at: 1, updated_by: "dev" },
            category_order: { value: [], updated_at: 1, updated_by: "dev" },
            deleted: { value: false, updated_at: 1, updated_by: "dev" },
          },
        },
      ],
      items: [],
    },
  };
}

function renderOverview() {
  return render(
    <MemoryRouter initialEntries={["/"]}>
      <SyncProvider>
        <Routes>
          <Route path="/" element={<OverviewPage />} />
          <Route path="/list/:listId" element={<div>list screen</div>} />
        </Routes>
      </SyncProvider>
    </MemoryRouter>,
  );
}

describe("OverviewPage last-opened-list resume", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.sync).mockResolvedValue(syncResponse("list-1"));
  });

  afterEach(() => {
    vi.clearAllMocks();
    cleanup();
  });

  it("redirects to the last-opened list on first entry (fresh page load)", async () => {
    _resetInitialResumeForTests();
    localStorage.setItem(LAST_LIST_STORAGE_KEY, "list-1");

    renderOverview();

    await waitFor(() => expect(screen.getByText("list screen")).toBeInTheDocument());
  });

  it("does NOT redirect again on a later in-app visit to the overview", async () => {
    _resetInitialResumeForTests();
    localStorage.setItem(LAST_LIST_STORAGE_KEY, "list-1");

    // First render simulates the initial app-entry resume (flips the flag).
    const first = renderOverview();
    await waitFor(() => expect(screen.getByText("list screen")).toBeInTheDocument());
    first.unmount();

    // Second render, WITHOUT resetting the flag, simulates the user
    // clicking back to "/" later in the same session (e.g. "All lists" /
    // the header link) - must show the overview itself, not bounce back to
    // the list again.
    renderOverview();

    await waitFor(() => expect(screen.getByText("Your lists")).toBeInTheDocument());
    expect(screen.queryByText("list screen")).not.toBeInTheDocument();
  });

  it("shows the overview directly when there is no last-opened list", async () => {
    _resetInitialResumeForTests();

    renderOverview();

    await waitFor(() => expect(screen.getByText("Your lists")).toBeInTheDocument());
  });
});
