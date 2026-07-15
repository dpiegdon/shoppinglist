import { useEffect, useState } from "react";
import { useSyncContext } from "../hooks/SyncContext";
import { syncStatusLabel } from "../lib/syncStatus";

/**
 * A quiet sync-health line in the app header (T-47): "Syncing… / Synced 5 min ago / Sync failed".
 * The web client has no offline mirror, so — unlike Android — there is no pending/blocked count to
 * show, just recency and failure. Reads live loading/error/lastSyncAt from the sync context.
 */
export default function SyncIndicator() {
  const { loading, error, lastSyncAt } = useSyncContext();
  // Without this, "now" is only re-evaluated when loading/error/lastSyncAt changes, so a label
  // like "Synced just now" would freeze indefinitely between syncs (T-54). A 60s tick is enough
  // granularity for a label whose smallest unit is "min ago".
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);
  const label = syncStatusLabel({ loading, error, lastSyncAt }, now);

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
