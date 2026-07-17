import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SyncIndicator from "./SyncIndicator";
import * as SyncContextModule from "../hooks/SyncContext";

vi.mock("../hooks/SyncContext", async () => {
  const actual = await vi.importActual<typeof SyncContextModule>("../hooks/SyncContext");
  return { ...actual, useSyncContext: vi.fn() };
});

describe("SyncIndicator recency tick (T-54)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it("ticks the relative time about once a minute instead of freezing on the initial render", () => {
    const lastSyncAt = Date.now();
    vi.mocked(SyncContextModule.useSyncContext).mockReturnValue({
      lists: new Map(),
      items: new Map(),
      loading: false,
      error: null,
      lastSyncAt,
      deviceId: "dev-1",
      push: vi.fn(),
      refresh: vi.fn(),
    });

    render(<SyncIndicator />);
    expect(screen.getByRole("status")).toHaveTextContent("Synced just now");

    act(() => {
      vi.advanceTimersByTime(5 * 60_000);
    });

    expect(screen.getByRole("status")).toHaveTextContent("Synced 5 min ago");
  });

  it("does not tick faster than the interval (no per-second re-render churn)", () => {
    const lastSyncAt = Date.now();
    vi.mocked(SyncContextModule.useSyncContext).mockReturnValue({
      lists: new Map(),
      items: new Map(),
      loading: false,
      error: null,
      lastSyncAt,
      deviceId: "dev-1",
      push: vi.fn(),
      refresh: vi.fn(),
    });

    render(<SyncIndicator />);

    act(() => {
      vi.advanceTimersByTime(59_000);
    });

    expect(screen.getByRole("status")).toHaveTextContent("Synced just now");
  });
});

describe("SyncIndicator manual refresh (T-90)", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("triggers a sync when clicked", async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    vi.mocked(SyncContextModule.useSyncContext).mockReturnValue({
      lists: new Map(),
      items: new Map(),
      loading: false,
      error: null,
      lastSyncAt: Date.now(),
      deviceId: "dev-1",
      push: vi.fn(),
      refresh,
    });

    render(<SyncIndicator />);
    await userEvent.click(screen.getByRole("button", { name: "Sync now" }));

    expect(refresh).toHaveBeenCalledTimes(1);
  });
});
