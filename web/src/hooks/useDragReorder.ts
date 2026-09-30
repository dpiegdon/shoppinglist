import { useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from "react";

/**
 * Drag-reorder by a handle (T-340), the web twin of Android's DragReorder.kt: pressing the handle
 * starts the drag at once, the row follows the pointer, and once it has travelled its neighbour's
 * measured height (plus [gapPx]) it swaps with that neighbour through [onMove] and the offset is
 * rebased by that height, so the row stays under the pointer.
 *
 * The handle is a focusable button, and ArrowUp/ArrowDown on it move its row one place: the
 * keyboard path to the same [onMove]. Focus stays on the handle while its row moves, and
 * [onKeyboardMove] is told where the row went, for the page to announce it.
 */
export function useDragReorder<K>(
  keys: K[],
  onMove: (from: number, to: number) => void,
  options: { gapPx?: number; onKeyboardMove?: (key: K, index: number, count: number) => void } = {},
) {
  const gapPx = options.gapPx ?? 0;
  const [dragging, setDragging] = useState<K | null>(null);
  const [offset, setOffset] = useState(0);
  const rows = useRef(new Map<K, HTMLElement>());
  const handles = useRef(new Map<K, HTMLElement>());
  // The order as this drag has moved it: a second pointer event can arrive before the page
  // re-renders with the new order, and it must not swap on the old one.
  const current = useRef(keys);
  const drag = useRef<{ key: K; lastY: number; offset: number } | null>(null);
  const refocus = useRef<K | null>(null);
  if (!drag.current) current.current = keys;

  useLayoutEffect(() => {
    const key = refocus.current;
    if (key === null) return;
    refocus.current = null;
    const handle = handles.current.get(key);
    if (handle && document.activeElement !== handle) handle.focus();
  });

  function step(neighbour: K): number {
    return (rows.current.get(neighbour)?.getBoundingClientRect().height ?? 0) + gapPx;
  }

  function move(from: number, to: number) {
    const next = [...current.current];
    [next[from], next[to]] = [next[to], next[from]];
    current.current = next;
    onMove(from, to);
  }

  function dragBy(dy: number) {
    const state = drag.current;
    if (!state) return;
    state.offset += dy;
    const order = current.current;
    const index = order.indexOf(state.key);
    if (index >= 0) {
      if (index > 0 && state.offset <= -step(order[index - 1])) {
        const by = step(order[index - 1]);
        move(index, index - 1);
        state.offset += by;
      } else if (index < order.length - 1 && state.offset >= step(order[index + 1])) {
        const by = step(order[index + 1]);
        move(index, index + 1);
        state.offset -= by;
      }
    }
    setOffset(state.offset);
  }

  function stop() {
    drag.current = null;
    setDragging(null);
    setOffset(0);
  }

  return {
    isDragging: (key: K) => dragging === key,

    /** On the row: its height is measured, and while dragged it follows the pointer, above the rest. */
    rowProps: (key: K) => ({
      ref: (el: HTMLElement | null) => {
        if (el) rows.current.set(key, el);
        else rows.current.delete(key);
      },
      style: (dragging === key
        ? { transform: `translateY(${offset}px)`, position: "relative", zIndex: 1 }
        : {}) as CSSProperties,
    }),

    /** On the handle button. */
    handleProps: (key: K) => ({
      ref: (el: HTMLElement | null) => {
        if (el) handles.current.set(key, el);
        else handles.current.delete(key);
      },
      onPointerDown: (e: PointerEvent<HTMLElement>) => {
        if (e.button !== 0) return;
        e.currentTarget.setPointerCapture?.(e.pointerId);
        drag.current = { key, lastY: e.clientY, offset: 0 };
        setDragging(key);
        setOffset(0);
      },
      onPointerMove: (e: PointerEvent<HTMLElement>) => {
        const state = drag.current;
        if (!state || state.key !== key) return;
        const dy = e.clientY - state.lastY;
        state.lastY = e.clientY;
        dragBy(dy);
      },
      onPointerUp: stop,
      onPointerCancel: stop,
      onKeyDown: (e: KeyboardEvent<HTMLElement>) => {
        if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return;
        e.preventDefault();
        const order = current.current;
        const from = order.indexOf(key);
        const to = e.key === "ArrowUp" ? from - 1 : from + 1;
        if (from < 0 || to < 0 || to >= order.length) return;
        refocus.current = key;
        move(from, to);
        options.onKeyboardMove?.(key, to, order.length);
      },
    }),
  };
}
