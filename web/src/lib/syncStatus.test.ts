import { describe, expect, it } from "vitest";
import { translate } from "../i18n";
import type { MessageKey } from "../i18n";
import { syncStatusLabel } from "./syncStatus";

// A real English translate, so these still assert the exact wording users see.
const t = (k: MessageKey, p?: Record<string, string | number>) => translate("en", k, p);

const now = 1_000_000_000;

describe("syncStatusLabel", () => {
  it("reads Syncing… while loading", () => {
    expect(syncStatusLabel({ loading: true, error: null, lastSyncAt: null }, now, t)).toBe("Syncing…");
  });

  it("reads Not synced yet before the first success", () => {
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: null }, now, t)).toBe("Not synced yet");
  });

  it("buckets relative times", () => {
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: now - 30_000 }, now, t)).toBe("Synced just now");
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: now - 5 * 60_000 }, now, t)).toBe("Synced 5 min ago");
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: now - 2 * 3_600_000 }, now, t)).toBe("Synced 2 h ago");
    expect(syncStatusLabel({ loading: false, error: null, lastSyncAt: now - 3 * 86_400_000 }, now, t)).toBe("Synced 3 d ago");
  });

  it("shows the last good time when the most recent attempt failed", () => {
    expect(syncStatusLabel({ loading: false, error: "boom", lastSyncAt: now - 5 * 60_000 }, now, t)).toBe(
      "Sync failed · last ok 5 min ago",
    );
  });

  it("shows a bare failure when it never synced", () => {
    expect(syncStatusLabel({ loading: false, error: "boom", lastSyncAt: null }, now, t)).toBe("Sync failed");
  });
});
