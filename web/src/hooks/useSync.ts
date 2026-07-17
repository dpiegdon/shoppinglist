import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import * as api from "../api/client";
import { ApiError } from "../api/client";
import type { ItemFields, ItemObject, ListFields, ListObject, SyncResponse } from "../api/contract";

const DEVICE_ID_STORAGE_KEY = "shoppinglist_device_id";

function getDeviceId(): string {
  let id = localStorage.getItem(DEVICE_ID_STORAGE_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(DEVICE_ID_STORAGE_KEY, id);
  }
  return id;
}

export function nowMs(): number {
  return Date.now();
}

/** Builds a single-field patch for a sync push: `{ [field]: {value, updated_at, updated_by} }`. */
export function fieldPatch<K extends string, V>(
  deviceId: string,
  field: K,
  value: V,
): Record<K, { value: V; updated_at: number; updated_by: string }> {
  return { [field]: { value, updated_at: nowMs(), updated_by: deviceId } } as Record<
    K,
    { value: V; updated_at: number; updated_by: string }
  >;
}

export interface SyncState {
  lists: Map<string, ListObject>;
  items: Map<string, ItemObject>;
  loading: boolean;
  error: string | null;
  /** Epoch-ms of the last successful sync, for the sync-health indicator (T-47); null until the first. */
  lastSyncAt: number | null;
  deviceId: string;
  /** Push local changes (and/or force a snapshot of full_lists), then pull. */
  push: (changes: { lists?: ListObject[]; items?: ItemObject[] }, fullLists?: string[]) => Promise<void>;
  /** Pull-only sync (e.g. periodic refresh or manual reload). */
  refresh: () => Promise<void>;
}

/**
 * In-memory sync store (Spec §6; web has no offline mirror). The cursor is
 * deliberately kept in memory only (a ref, not localStorage): it may only
 * ever advance past what the client durably holds, and since lists/items
 * here live only in React state - wiped on every page reload - persisting
 * the cursor without the data it presupposes would make the next reload's
 * *correctly empty* incremental delta look like "you have nothing" (a real
 * bug this once was: the cursor survived a reload in localStorage while the
 * data didn't, so the follow-up sync asked for "what's new since N" instead
 * of a full resync, and correctly got nothing back). Starting at 0 on every
 * fresh mount means a reload always requests a full snapshot, which is the
 * only correct behavior for a client with no persistent local copy.
 */
// Visible-tab-only fallback poll (T-90). This is deliberately a *fallback*,
// not the primary sync mechanism: visibilitychange->visible, window 'online',
// and manual refresh cover the cases that matter promptly. Per the T-73
// precedent (aggressive background/Doze polling on Android was rejected),
// there is no hidden-tab equivalent here at all - the interval keeps ticking
// but its body is a no-op while document.hidden, so a backgrounded tab makes
// zero network calls.
const SYNC_INTERVAL_MS = 100_000;

export function useSync(): SyncState {
  const deviceId = useMemo(() => getDeviceId(), []);
  const [lists, setLists] = useState<Map<string, ListObject>>(new Map());
  const [items, setItems] = useState<Map<string, ItemObject>>(new Map());
  // Starts true: the mount effect below unconditionally kicks off an initial
  // sync, so `loading` must never read as "false" before that first sync has
  // actually run - a consumer (e.g. OverviewPage's last-list-resume check)
  // could otherwise see a premature "not loading" moment against an empty,
  // not-yet-populated `lists`/`items` map.
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastSyncAt, setLastSyncAt] = useState<number | null>(null);
  const cursorRef = useRef(0);
  // Tracks whether a runSync() call (push or refresh) is currently
  // outstanding, so the re-sync triggers below can skip-if-in-flight instead
  // of queuing a redundant request behind it. push() itself is never gated by
  // this flag - a user-initiated write must never be silently dropped - only
  // the background/manual refresh triggers are.
  const inFlightRef = useRef(false);

  const applyResponse = useCallback((response: SyncResponse) => {
    cursorRef.current = response.cursor;
    // Called on every successfully-applied response (normal and the 410 retry), so it's the single
    // place to stamp last-synced for the health indicator (T-47).
    setLastSyncAt(nowMs());
    setLists((prev) => {
      const next = new Map(prev);
      for (const list of response.changes.lists) {
        if (list.fields.deleted?.value) {
          next.delete(list.id);
        } else {
          next.set(list.id, list);
        }
      }
      return next;
    });
    setItems((prev) => {
      const next = new Map(prev);
      for (const item of response.changes.items) {
        if (item.fields.deleted?.value) {
          next.delete(item.id);
        } else {
          next.set(item.id, item);
        }
      }
      return next;
    });
  }, []);

  const runSync = useCallback(
    async (changes: { lists?: ListObject[]; items?: ItemObject[] }, fullLists: string[] = []) => {
      inFlightRef.current = true;
      setLoading(true);
      setError(null);
      try {
        const response = await api.sync({
          cursor: cursorRef.current,
          device_id: deviceId,
          full_lists: fullLists,
          changes,
        });
        applyResponse(response);
      } catch (err) {
        if (err instanceof ApiError && err.status === 410) {
          // Full resync required: drop cursor and mirror, then retry from 0.
          cursorRef.current = 0;
          setLists(new Map());
          setItems(new Map());
          const response = await api.sync({
            cursor: 0,
            device_id: deviceId,
            full_lists: fullLists,
            changes: {},
          });
          applyResponse(response);
        } else {
          setError(err instanceof Error ? err.message : "Sync failed.");
          throw err;
        }
      } finally {
        setLoading(false);
        inFlightRef.current = false;
      }
    },
    [deviceId, applyResponse],
  );

  const push = useCallback(
    (changes: { lists?: ListObject[]; items?: ItemObject[] }, fullLists: string[] = []) =>
      runSync(changes, fullLists),
    [runSync],
  );

  // Pull-only sync used by the mount effect, the re-sync triggers below, and
  // manual refresh (SyncIndicator). Skip-if-in-flight: a trigger that fires
  // while a sync (push or refresh) is already outstanding is dropped, not
  // queued - the in-flight sync will bring the client current anyway.
  const refresh = useCallback(() => {
    if (inFlightRef.current) {
      return Promise.resolve();
    }
    return runSync({});
  }, [runSync]);

  useEffect(() => {
    refresh().catch(() => {
      // surfaced via `error` state; nothing further to do here
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Re-sync on document visibilitychange -> visible (T-90). This is a
  // primary trigger: a tab that was backgrounded during a shared shopping
  // trip should catch up the moment it's looked at again, not wait for the
  // next interval tick.
  useEffect(() => {
    function handleVisibilityChange() {
      if (!document.hidden) {
        refresh().catch(() => {
          // surfaced via `error` state; nothing further to do here
        });
      }
    }
    document.addEventListener("visibilitychange", handleVisibilityChange);
    return () => document.removeEventListener("visibilitychange", handleVisibilityChange);
  }, [refresh]);

  // Re-sync on window 'online' (T-90): another primary trigger, for a tab
  // that stayed visible through a network blip (e.g. laptop lid closed with
  // the tab pinned, wifi handoff).
  useEffect(() => {
    function handleOnline() {
      refresh().catch(() => {
        // surfaced via `error` state; nothing further to do here
      });
    }
    window.addEventListener("online", handleOnline);
    return () => window.removeEventListener("online", handleOnline);
  }, [refresh]);

  // Modest visible-tab-only fallback poll (T-90, T-73 precedent - see
  // SYNC_INTERVAL_MS comment above). The interval itself keeps running so it
  // doesn't need to be torn down/recreated on every visibility flip, but its
  // body is gated on document.hidden: a backgrounded tab never triggers a
  // network call from this timer.
  useEffect(() => {
    const id = setInterval(() => {
      if (document.hidden) return;
      refresh().catch(() => {
        // surfaced via `error` state; nothing further to do here
      });
    }, SYNC_INTERVAL_MS);
    return () => clearInterval(id);
  }, [refresh]);

  return { lists, items, loading, error, lastSyncAt, deviceId, push, refresh };
}

export function itemFieldValue<K extends keyof ItemFields>(
  item: ItemObject,
  key: K,
): ItemFields[K]["value"] | undefined {
  return item.fields[key]?.value;
}

export function listFieldValue<K extends keyof ListFields>(
  list: ListObject,
  key: K,
): ListFields[K]["value"] | undefined {
  return list.fields[key]?.value;
}
