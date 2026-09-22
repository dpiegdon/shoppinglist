import { useCallback, useRef, type MouseEvent } from "react";

/**
 * A dialog overlay's "click outside to close" handler, done properly (T-272).
 *
 * A plain `onClick={onClose}` on the overlay closes on ANY click whose event target ends up being
 * the overlay itself — including one that started as a text-selection drag inside a field (e.g.
 * dragging to select typed text) and was released outside the dialog panel. The browser's `click`
 * event fires on the common ancestor of the mousedown and mouseup targets, so that drag's click
 * target is the overlay, and the whole form was silently discarded.
 *
 * Tracking mousedown and mouseup separately fixes this: the overlay only closes when BOTH land on
 * the overlay itself (never on the panel, since `e.target === e.currentTarget` fails for anything
 * inside it), so a drag that starts inside the panel never counts as a click on the overlay.
 */
export function useOverlayClose(onClose: () => void): {
  onMouseDown: (e: MouseEvent<HTMLElement>) => void;
  onMouseUp: (e: MouseEvent<HTMLElement>) => void;
} {
  const downOnOverlay = useRef(false);

  const onMouseDown = useCallback((e: MouseEvent<HTMLElement>) => {
    downOnOverlay.current = e.target === e.currentTarget;
  }, []);

  const onMouseUp = useCallback(
    (e: MouseEvent<HTMLElement>) => {
      if (downOnOverlay.current && e.target === e.currentTarget) onClose();
      downOnOverlay.current = false;
    },
    [onClose],
  );

  return { onMouseDown, onMouseUp };
}
