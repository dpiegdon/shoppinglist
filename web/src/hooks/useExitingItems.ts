import { useEffect, useRef, useState } from "react";

/**
 * How long a checked-off row stays mounted so it can animate away (T-128).
 *
 * The strike-through lands first and the row holds still for a moment, then it slides out. Short
 * on purpose: this is a confirmation that the tap registered, not a performance. Kept in step with
 * the `item-exiting` animation in index.css — if these disagree, the row either vanishes
 * mid-slide or lingers as a dead gap.
 */
export const EXIT_ANIMATION_MS = 300;

/**
 * Holds ids that have just left `visibleIds` for [EXIT_ANIMATION_MS], so the rows can animate out.
 *
 * Without this there is nothing to animate: `groupVisibleItems` drops a checked item the instant
 * its status changes, so the row unmounts in the same commit and simply disappears. React has no
 * built-in exit transition, so the only way to animate a removal is to keep rendering the thing
 * for a moment after it is logically gone.
 *
 * Returns the set of ids currently exiting. The caller renders those rows alongside the visible
 * ones and marks them so CSS can animate them.
 *
 * An id that comes BACK while it is still exiting (rapid check/uncheck, or a collaborator undoing
 * a check within the window) is dropped from the exiting set immediately — otherwise it would be
 * rendered twice, once live and once as a ghost sliding away.
 *
 * `resetKey` distinguishes "this row left because someone checked it off" from "these rows left
 * because the view changed". Turning "show checked" off removes every checked row at once, and
 * animating twenty rows sliding away is both slow and a lie — nothing was checked off, the filter
 * changed. When `resetKey` changes, the baseline is re-synced with no animation at all.
 */
export function useExitingItems(visibleIds: readonly string[], resetKey?: unknown): Set<string> {
  const [exiting, setExiting] = useState<Set<string>>(new Set());
  const previous = useRef<readonly string[]>(visibleIds);
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  const previousResetKey = useRef(resetKey);

  useEffect(() => {
    const current = new Set(visibleIds);
    const viewChanged = previousResetKey.current !== resetKey;
    previousResetKey.current = resetKey;

    if (viewChanged) {
      // Adopt the new baseline silently and cancel anything mid-flight: whatever is on screen now
      // is simply what this view shows, not the result of anyone checking something off.
      previous.current = visibleIds;
      for (const timer of timers.current.values()) clearTimeout(timer);
      timers.current.clear();
      setExiting((prev) => (prev.size === 0 ? prev : new Set()));
      return;
    }

    const departed = previous.current.filter((id) => !current.has(id));
    previous.current = visibleIds;

    if (departed.length > 0) {
      setExiting((prev) => {
        const next = new Set(prev);
        for (const id of departed) next.add(id);
        return next;
      });
      for (const id of departed) {
        clearTimeout(timers.current.get(id));
        timers.current.set(
          id,
          setTimeout(() => {
            timers.current.delete(id);
            setExiting((prev) => {
              if (!prev.has(id)) return prev;
              const next = new Set(prev);
              next.delete(id);
              return next;
            });
          }, EXIT_ANIMATION_MS),
        );
      }
    }

    // A returning id must stop exiting at once, or it renders twice.
    setExiting((prev) => {
      if (prev.size === 0) return prev;
      let changed = false;
      const next = new Set(prev);
      for (const id of next) {
        if (current.has(id)) {
          next.delete(id);
          clearTimeout(timers.current.get(id));
          timers.current.delete(id);
          changed = true;
        }
      }
      return changed ? next : prev;
    });
  }, [visibleIds]);

  // Clearing on unmount stops a timer firing setState against a gone component — which in practice
  // shows up as a React warning during navigation away from a list mid-animation.
  useEffect(() => {
    const pending = timers.current;
    return () => {
      for (const timer of pending.values()) clearTimeout(timer);
      pending.clear();
    };
  }, []);

  return exiting;
}
