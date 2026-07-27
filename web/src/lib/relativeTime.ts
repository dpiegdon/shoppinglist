const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

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
 *
 * Units are abbreviated (T-123) so no string has to agree with a number: "1 min
 * ago" and "20 min ago" use the same word, in every language. This replaced
 * Intl.RelativeTimeFormat, which handled plurals correctly for free but produced
 * different wording from the Android client — and the two are deliberately kept
 * in step. The trade is intentional: we give up Intl's built-in localization for
 * four short unit labels that a message catalog translates once (German
 * Min/Std/T), and in exchange the codebase needs no plural machinery at all.
 * Matches Android's formatLastSeen and formatBackgroundSync.
 */
export function formatLastSeen(lastSeenAt: number, now: number = Date.now()): string {
  const elapsed = now - lastSeenAt;
  if (elapsed < 2 * MINUTE) return "Active now";
  if (elapsed < HOUR) return `${Math.floor(elapsed / MINUTE)} min ago`;
  if (elapsed < DAY) return `${Math.floor(elapsed / HOUR)} h ago`;
  return `${Math.floor(elapsed / DAY)} d ago`;
}
