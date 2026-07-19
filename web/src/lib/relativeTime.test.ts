import { describe, expect, it } from "vitest";
import { formatLastSeen } from "./relativeTime";

const NOW = 1_760_000_000_000;
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

describe("formatLastSeen", () => {
  it("reports very recent activity as active", () => {
    expect(formatLastSeen(NOW, NOW)).toBe("Active now");
    expect(formatLastSeen(NOW - 90_000, NOW)).toBe("Active now");
  });

  it("falls back to minutes, hours, then days as it ages", () => {
    expect(formatLastSeen(NOW - 20 * MINUTE, NOW)).toBe("20 minutes ago");
    expect(formatLastSeen(NOW - 5 * HOUR, NOW)).toBe("5 hours ago");
    expect(formatLastSeen(NOW - 3 * DAY, NOW)).toBe("3 days ago");
  });

  it("uses natural wording where the locale has it", () => {
    expect(formatLastSeen(NOW - 25 * HOUR, NOW)).toBe("yesterday");
  });

  it("does not render clock skew as a future time", () => {
    expect(formatLastSeen(NOW + 5 * HOUR, NOW)).toBe("Active now");
  });

  it("stays sensible right up against an expiring web session", () => {
    // The web window is 7 days; a session at 6 days must read as nearly-dead,
    // not round down into hours.
    expect(formatLastSeen(NOW - 6 * DAY, NOW)).toBe("6 days ago");
  });
});
