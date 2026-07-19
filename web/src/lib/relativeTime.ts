const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// Hardcoded "en" to match the rest of the UI's strings rather than following the
// browser locale, which would render "vor 3 Tagen" next to an English "Revoke".
const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

/**
 * Human-readable staleness for a session's `last_seen_at` (T-104).
 *
 * Coarse on purpose: the server throttles `last_seen_at` writes to a 15-minute
 * staleness window (auth.LAST_SEEN_REFRESH_MS), so anything finer would be
 * showing precision the timestamp doesn't have. What this needs to convey is
 * "is this session about to idle out?", against windows measured in days.
 *
 * A future `lastSeenAt` (client/server clock skew) falls into the "Active now"
 * branch rather than rendering as "in 3 hours".
 */
export function formatLastSeen(lastSeenAt: number, now: number = Date.now()): string {
  const elapsed = now - lastSeenAt;
  if (elapsed < 2 * MINUTE) return "Active now";
  if (elapsed < HOUR) return relative.format(-Math.floor(elapsed / MINUTE), "minute");
  if (elapsed < DAY) return relative.format(-Math.floor(elapsed / HOUR), "hour");
  return relative.format(-Math.floor(elapsed / DAY), "day");
}
