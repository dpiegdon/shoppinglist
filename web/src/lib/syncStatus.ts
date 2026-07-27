import type { TranslateFn } from "../i18n";

/** Sync-health label for the header indicator (T-47). Mirrors the Android `syncRecencyText`. */

export interface SyncStatusInput {
  loading: boolean;
  error: string | null;
  lastSyncAt: number | null;
}

/**
 * Shares the `ago.*` catalog strings with formatLastSeen rather than carrying its own copies: the
 * wording is identical, so duplicating the keys would mean translating the same four labels twice
 * and inviting them to drift apart. Abbreviated units, so no plural rule is needed (T-123).
 */
function relative(agoMs: number, t: TranslateFn): string {
  const ago = Math.max(0, agoMs);
  if (ago < 60_000) return t("ago.justNow");
  if (ago < 3_600_000) return t("ago.minutes", { count: Math.floor(ago / 60_000) });
  if (ago < 86_400_000) return t("ago.hours", { count: Math.floor(ago / 3_600_000) });
  return t("ago.days", { count: Math.floor(ago / 86_400_000) });
}

/** `t` is passed in, not taken from a hook, so this stays pure and directly unit-testable. */
export function syncStatusLabel(input: SyncStatusInput, now: number, t: TranslateFn): string {
  if (input.loading) return t("sync.syncing");
  if (input.error) {
    return input.lastSyncAt != null
      ? t("sync.failedSince", { ago: relative(now - input.lastSyncAt, t) })
      : t("sync.failed");
  }
  if (input.lastSyncAt == null) return t("sync.never");
  return t("sync.synced", { ago: relative(now - input.lastSyncAt, t) });
}
