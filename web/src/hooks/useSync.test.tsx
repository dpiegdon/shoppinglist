import { act, renderHook, waitFor } from "@testing-library/react";
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

describe("useSync re-sync triggers (T-90)", () => {
  beforeEach(() => {
    localStorage.clear();
    // Tests explicitly set document.hidden per-case; reset to the jsdom default
    // ("visible") so cases don't bleed into each other.
    Object.defineProperty(document, "hidden", { value: false, configurable: true });
  });

  afterEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it("re-syncs when the document becomes visible again", async () => {
    vi.mocked(api.sync).mockResolvedValue(listResponse(1, ["list-1"]));
    const { result } = renderHook(() => useSync());
    await waitFor(() => expect(result.current.loading).toBe(false));
    vi.mocked(api.sync).mockClear();

    Object.defineProperty(document, "hidden", { value: false, configurable: true });
    await act(async () => {
      document.dispatchEvent(new Event("visibilitychange"));
    });

    await waitFor(() => expect(api.sync).toHaveBeenCalledTimes(1));
  });

  it("does not re-sync on a visibilitychange that leaves the tab hidden", async () => {
    vi.mocked(api.sync).mockResolvedValue(listResponse(1, ["list-1"]));
    const { result } = renderHook(() => useSync());
    await waitFor(() => expect(result.current.loading).toBe(false));
    vi.mocked(api.sync).mockClear();

    Object.defineProperty(document, "hidden", { value: true, configurable: true });
    await act(async () => {
      document.dispatchEvent(new Event("visibilitychange"));
    });

    expect(api.sync).not.toHaveBeenCalled();
  });

  it("re-syncs when the browser reports it's back online", async () => {
    vi.mocked(api.sync).mockResolvedValue(listResponse(1, ["list-1"]));
    const { result } = renderHook(() => useSync());
    await waitFor(() => expect(result.current.loading).toBe(false));
    vi.mocked(api.sync).mockClear();

    await act(async () => {
      window.dispatchEvent(new Event("online"));
    });

    await waitFor(() => expect(api.sync).toHaveBeenCalledTimes(1));
  });

  it("polls on an interval while the tab stays visible", async () => {
    vi.useFakeTimers();
    vi.mocked(api.sync).mockResolvedValue(listResponse(1, ["list-1"]));
    Object.defineProperty(document, "hidden", { value: false, configurable: true });

    renderHook(() => useSync());
    await vi.advanceTimersByTimeAsync(0); // flush the mount effect's initial sync
    vi.mocked(api.sync).mockClear();

    await vi.advanceTimersByTimeAsync(120_000);

    expect(api.sync).toHaveBeenCalledTimes(1);
  });

  it("does not poll while the tab is hidden", async () => {
    vi.useFakeTimers();
    vi.mocked(api.sync).mockResolvedValue(listResponse(1, ["list-1"]));
    Object.defineProperty(document, "hidden", { value: true, configurable: true });

    renderHook(() => useSync());
    await vi.advanceTimersByTimeAsync(0); // flush the mount effect's initial sync
    vi.mocked(api.sync).mockClear();

    await vi.advanceTimersByTimeAsync(120_000);

    expect(api.sync).not.toHaveBeenCalled();
  });

  it("drops an overlapping trigger while a sync is already in flight, instead of queueing it", async () => {
    let resolveSync!: (value: ReturnType<typeof listResponse>) => void;
    vi.mocked(api.sync).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveSync = resolve;
        }),
    );
    const { result } = renderHook(() => useSync());
    // The mount effect's initial sync is now in flight and unresolved.
    await waitFor(() => expect(result.current.loading).toBe(true));
    vi.mocked(api.sync).mockClear();

    // A trigger firing while that sync is still outstanding must be dropped,
    // not queued behind it.
    await act(async () => {
      window.dispatchEvent(new Event("online"));
    });
    expect(api.sync).not.toHaveBeenCalled();

    // Let the in-flight sync resolve so it doesn't leak into other tests.
    await act(async () => {
      resolveSync(listResponse(1, ["list-1"]));
    });
    await waitFor(() => expect(result.current.loading).toBe(false));
  });

  it("never gates push() behind the in-flight guard: a push while a refresh is outstanding still goes out, payload intact", async () => {
    const pending: Array<(value: ReturnType<typeof listResponse>) => void> = [];
    vi.mocked(api.sync).mockImplementation(
      () =>
        new Promise((resolve) => {
          pending.push(resolve);
        }),
    );
    const { result } = renderHook(() => useSync());
    // The mount effect's refresh() is now in flight and unresolved.
    await waitFor(() => expect(result.current.loading).toBe(true));
    expect(api.sync).toHaveBeenCalledTimes(1);

    // A user write while that refresh is still outstanding must NOT be
    // dropped by the skip-if-in-flight guard - only refresh triggers are.
    const pushedChanges = {
      items: [
        {
          id: "item-1",
          list_id: "list-1",
          fields: {
            name: { value: "Milk", updated_at: 2, updated_by: "dev" },
            status: { value: "checked" as const, updated_at: 2, updated_by: "dev" },
          },
        },
      ],
    };
    let pushPromise!: Promise<void>;
    act(() => {
      pushPromise = result.current.push(pushedChanges);
    });

    expect(api.sync).toHaveBeenCalledTimes(2);
    // splitChanges (T-114) normalises a push to both keys, so the payload is
    // {lists: [], items: [...]} rather than the caller's {items: [...]}. Semantically identical —
    // the server reads `changes.lists or []` — and the point here is that the items survived.
    expect(api.sync).toHaveBeenLastCalledWith(
      expect.objectContaining({ changes: { lists: [], items: pushedChanges.items } }),
    );

    // Drain both outstanding requests so nothing leaks into other tests.
    await act(async () => {
      for (const resolve of pending) {
        resolve(listResponse(1, ["list-1"]));
      }
      await pushPromise;
    });
    await waitFor(() => expect(result.current.loading).toBe(false));
  });

  it("removes its listeners and interval on unmount", async () => {
    vi.mocked(api.sync).mockResolvedValue(listResponse(1, ["list-1"]));
    const docAddSpy = vi.spyOn(document, "addEventListener");
    const docRemoveSpy = vi.spyOn(document, "removeEventListener");
    const winAddSpy = vi.spyOn(window, "addEventListener");
    const winRemoveSpy = vi.spyOn(window, "removeEventListener");
    const clearIntervalSpy = vi.spyOn(window, "clearInterval");

    const { result, unmount } = renderHook(() => useSync());
    await waitFor(() => expect(result.current.loading).toBe(false));

    const visibilityAdds = docAddSpy.mock.calls.filter((c) => c[0] === "visibilitychange").length;
    const onlineAdds = winAddSpy.mock.calls.filter((c) => c[0] === "online").length;
    expect(visibilityAdds).toBeGreaterThan(0);
    expect(onlineAdds).toBeGreaterThan(0);

    unmount();

    const visibilityRemoves = docRemoveSpy.mock.calls.filter((c) => c[0] === "visibilitychange").length;
    const onlineRemoves = winRemoveSpy.mock.calls.filter((c) => c[0] === "online").length;
    expect(visibilityRemoves).toBe(visibilityAdds);
    expect(onlineRemoves).toBe(onlineAdds);
    expect(clearIntervalSpy).toHaveBeenCalled();
  });
});
