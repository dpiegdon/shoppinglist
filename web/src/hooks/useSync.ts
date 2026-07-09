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
  const cursorRef = useRef(0);

  const applyResponse = useCallback((response: SyncResponse) => {
    cursorRef.current = response.cursor;
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
      }
    },
    [deviceId, applyResponse],
  );

  const push = useCallback(
    (changes: { lists?: ListObject[]; items?: ItemObject[] }, fullLists: string[] = []) =>
      runSync(changes, fullLists),
    [runSync],
  );

  const refresh = useCallback(() => runSync({}), [runSync]);

  useEffect(() => {
    refresh().catch(() => {
      // surfaced via `error` state; nothing further to do here
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { lists, items, loading, error, deviceId, push, refresh };
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
