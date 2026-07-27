import { describe, expect, it } from "vitest";
import { translate } from "../i18n";
import type { MessageKey } from "../i18n";
import { formatLastSeen } from "./relativeTime";

// A real English translate, so these still assert the exact wording users see.
const t = (k: MessageKey, p?: Record<string, string | number>) => translate("en", k, p);

const NOW = 1_760_000_000_000;
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

describe("formatLastSeen", () => {
  it("reports very recent activity as active", () => {
    expect(formatLastSeen(NOW, NOW, t)).toBe("Active now");
    expect(formatLastSeen(NOW - 90_000, NOW, t)).toBe("Active now");
  });

  it("falls back to minutes, hours, then days as it ages", () => {
    expect(formatLastSeen(NOW - 20 * MINUTE, NOW, t)).toBe("20 min ago");
    expect(formatLastSeen(NOW - 5 * HOUR, NOW, t)).toBe("5 h ago");
    expect(formatLastSeen(NOW - 3 * DAY, NOW, t)).toBe("3 d ago");
  });

  it("reads identically at one as at many, so no plural rule is needed (T-123)", () => {
    expect(formatLastSeen(NOW - 2 * MINUTE, NOW, t)).toBe("2 min ago");
    expect(formatLastSeen(NOW - HOUR - MINUTE, NOW, t)).toBe("1 h ago");
    expect(formatLastSeen(NOW - DAY - HOUR, NOW, t)).toBe("1 d ago");
  });

  it("matches the Android client's wording exactly", () => {
    // Android's formatLastSeen and formatBackgroundSync both read this way; the two clients are
    // deliberately kept in step, which is why this dropped Intl.RelativeTimeFormat.
    expect(formatLastSeen(NOW - 25 * HOUR, NOW, t)).toBe("1 d ago");
  });

  it("does not render clock skew as a future time", () => {
    expect(formatLastSeen(NOW + 5 * HOUR, NOW, t)).toBe("Active now");
  });

  it("stays sensible right up against an expiring web session", () => {
    // The web window is 7 days; a session at 6 days must read as nearly-dead,
    // not round down into hours.
    expect(formatLastSeen(NOW - 6 * DAY, NOW, t)).toBe("6 d ago");
  });
});
