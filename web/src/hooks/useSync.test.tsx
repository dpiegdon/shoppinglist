import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSync } from "./useSync";
import * as api from "../api/client";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof api>("../api/client");
  return { ...actual, sync: vi.fn() };
});

function listResponse(cursor: number, listIds: string[]) {
  return {
    cursor,
    changes: {
      lists: listIds.map((id) => ({
        id,
        created_at: 0,
        fields: {
          name: { value: `List ${id}`, updated_at: 1, updated_by: "dev" },
          category_order: { value: [], updated_at: 1, updated_by: "dev" },
          deleted: { value: false, updated_at: 1, updated_by: "dev" },
        },
      })),
      items: [],
    },
  };
}

describe("useSync across a page reload", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("requests a full resync (cursor 0) on every fresh mount, not a stale cursor", async () => {
    // --- "first login": fresh browser.
    vi.mocked(api.sync).mockResolvedValueOnce(listResponse(42, ["list-1", "list-2", "list-3"]));
    const first = renderHook(() => useSync());
    await waitFor(() => expect(first.result.current.lists.size).toBe(3));
    expect(api.sync).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 0 }));

    // --- "reload": a brand-new hook instance (React state wiped, as a real
    // page reload does). Even though cursor 42 was reached last session,
    // nothing durable survived the reload to apply a delta *to* - the
    // client must ask for a full snapshot again, not "what's new since 42".
    vi.mocked(api.sync).mockResolvedValueOnce(listResponse(42, ["list-1", "list-2", "list-3"]));
    const second = renderHook(() => useSync());
    await waitFor(() => expect(second.result.current.loading).toBe(false));

    expect(api.sync).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 0 }));
    expect(second.result.current.lists.size).toBe(3);
  });
});

describe("useSync health tracking (T-47)", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("stamps lastSyncAt on a successful sync and leaves error null", async () => {
    vi.mocked(api.sync).mockResolvedValueOnce(listResponse(1, ["list-1"]));
    const { result } = renderHook(() => useSync());

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(typeof result.current.lastSyncAt).toBe("number");
    expect(result.current.error).toBeNull();
  });

  it("records the error message when a sync fails", async () => {
    vi.mocked(api.sync).mockRejectedValueOnce(new Error("network down"));
    const { result } = renderHook(() => useSync());

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.error).toBe("network down");
    expect(result.current.lastSyncAt).toBeNull();
  });
});
