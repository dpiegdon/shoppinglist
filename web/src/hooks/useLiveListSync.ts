import { useEffect } from "react";
import { useSyncContext } from "./SyncContext";

/**
 * How often an OPEN list re-syncs while someone is looking at it (T-128).
 *
 * Five seconds is chosen for one scenario: two people shopping the same list in the same shop.
 * Whoever picks the milk first must be visible to the other before they reach for it, and walking
 * between aisles takes longer than this.
 *
 * Affordable because a poll is nearly free — an already-up-to-date POST /sync against a 60-item
 * list measured 3.66 ms, so two shoppers at this interval cost well under 0.25% of one core. That
 * measurement is why this is polling and not a server push: a held SSE/WebSocket connection
 * occupies a WSGI worker for its whole life, and this server is a blueprint mounted in someone
 * else's host app, which cannot dictate the worker class. See T-128.
 */
export const LIVE_SYNC_INTERVAL_MS = 5_000;

/**
 * Keeps an open list current while the tab is visible.
 *
 * Deliberately additive: the 100 s fallback poll, visibilitychange, `online` and after-edit pushes
 * in useSync all stay exactly as they were. This narrows to "a list is on screen and someone is
 * looking at it", which is the only situation that justifies the extra traffic.
 *
 * Both timers call the same `refresh()`, which skips if a sync is already in flight — so the two
 * intervals cannot stack into overlapping requests no matter how their ticks line up.
 */
export function useLiveListSync(enabled = true): void {
  const { refresh } = useSyncContext();

  useEffect(() => {
    if (!enabled) return;
    const id = setInterval(() => {
      // Gated per tick rather than by tearing the interval down on every visibility flip — same
      // approach as useSync's fallback poll, so a backgrounded tab makes no network call.
      if (document.hidden) return;
      refresh().catch(() => {
        // Surfaced via the sync context's `error` state; nothing to do here.
      });
    }, LIVE_SYNC_INTERVAL_MS);
    return () => clearInterval(id);
  }, [enabled, refresh]);
}
