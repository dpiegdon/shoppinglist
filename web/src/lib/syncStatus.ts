/** Sync-health label for the header indicator (T-47). Mirrors the Android `syncRecencyText`. */

export interface SyncStatusInput {
  loading: boolean;
  error: string | null;
  lastSyncAt: number | null;
}

function relative(agoMs: number): string {
  const ago = Math.max(0, agoMs);
  if (ago < 60_000) return "just now";
  if (ago < 3_600_000) return `${Math.floor(ago / 60_000)} min ago`;
  if (ago < 86_400_000) return `${Math.floor(ago / 3_600_000)} h ago`;
  return `${Math.floor(ago / 86_400_000)} d ago`;
}

export function syncStatusLabel(input: SyncStatusInput, now: number): string {
  if (input.loading) return "Syncing…";
  if (input.error) {
    return input.lastSyncAt != null ? `Sync failed · last ok ${relative(now - input.lastSyncAt)}` : "Sync failed";
  }
  if (input.lastSyncAt == null) return "Not synced yet";
  return `Synced ${relative(now - input.lastSyncAt)}`;
}
