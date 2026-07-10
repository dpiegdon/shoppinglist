import { describe, expect, it } from "vitest";
import { syncStatusLabel } from "./syncStatus";

const now = 1_000_000_000;

describe("syncStatusLabel", () => {
  it("reads Syncing… while loading", () => {
    expect(syncStatusLabel({ loading: true, error: null, lastSyncAt: null }, now)).toBe("Syncing…");
  });

  it("reads Not synced yet before the first success", () => {
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: null }, now)).toBe("Not synced yet");
  });

  it("buckets relative times", () => {
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: now - 30_000 }, now)).toBe("Synced just now");
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: now - 5 * 60_000 }, now)).toBe("Synced 5 min ago");
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: now - 2 * 3_600_000 }, now)).toBe("Synced 2 h ago");
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: now - 3 * 86_400_000 }, now)).toBe("Synced 3 d ago");
  });

  it("shows the last good time when the most recent attempt failed", () => {
    expect(syncStatusLabel({ loading: false, error: "boom", lastSyncAt: now - 5 * 60_000 }, now)).toBe(
      "Sync failed · last ok 5 min ago",
    );
  });

  it("shows a bare failure when it never synced", () => {
    expect(syncStatusLabel({ loading: false, error: "boom", lastSyncAt: null }, now)).toBe("Sync failed");
  });
});
