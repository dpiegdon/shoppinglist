import type { TranslateFn } from "../i18n";

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
 * four short unit labels that the message catalog translates once (German
 * Min/Std/T), and in exchange the codebase needs no plural machinery at all.
 * Matches Android's formatLastSeen and formatBackgroundSync.
 *
 * `t` is passed in rather than pulled from a hook so this stays a pure function:
 * it is the reason the unit tests can assert exact wording without rendering a
 * component or standing up a provider.
 */
export function formatLastSeen(lastSeenAt: number, now: number, t: TranslateFn): string {
  const elapsed = now - lastSeenAt;
  if (elapsed < 2 * MINUTE) return t("lastSeen.activeNow");
  if (elapsed < HOUR) return t("ago.minutes", { count: Math.floor(elapsed / MINUTE) });
  if (elapsed < DAY) return t("ago.hours", { count: Math.floor(elapsed / HOUR) });
  return t("ago.days", { count: Math.floor(elapsed / DAY) });
}

/**
 * How long an invite still stands, for its card on the overview (T-233). The same coarse,
 * abbreviated units as `formatLastSeen`, for the same reasons: an invite lives seven days, so
 * "Expires in 5 d" is the precision worth stating, and no string has to agree with its number.
 * Never below one minute — a server only returns invites that are still live, and "in 0 min"
 * would read as already gone. Matches Android's formatExpiresIn.
 */
export function formatExpiresIn(expiresAt: number, now: number, t: TranslateFn): string {
  const remaining = Math.max(expiresAt - now, MINUTE);
  if (remaining < HOUR) return t("overview.invite.expiresMinutes", { count: Math.floor(remaining / MINUTE) });
  if (remaining < DAY) return t("overview.invite.expiresHours", { count: Math.floor(remaining / HOUR) });
  return t("overview.invite.expiresDays", { count: Math.floor(remaining / DAY) });
}
