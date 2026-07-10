import { useSyncContext } from "../hooks/SyncContext";
import { syncStatusLabel } from "../lib/syncStatus";

/**
 * A quiet sync-health line in the app header (T-47): "Syncing… / Synced 5 min ago / Sync failed".
 * The web client has no offline mirror, so — unlike Android — there is no pending/blocked count to
 * show, just recency and failure. Reads live loading/error/lastSyncAt from the sync context.
 */
export default function SyncIndicator() {
  const { loading, error, lastSyncAt } = useSyncContext();
  const label = syncStatusLabel({ loading, error, lastSyncAt }, Date.now());

  return (
    <span
      role="status"
      aria-live="polite"
      style={{
        marginLeft: "auto",
        fontSize: "0.8rem",
        color: error ? "var(--color-danger)" : "var(--color-text-muted)",
      }}
    >
      {label}
    </span>
  );
}
