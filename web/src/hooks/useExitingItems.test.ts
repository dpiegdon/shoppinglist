import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EXIT_ANIMATION_MS, useExitingItems } from "./useExitingItems";

describe("useExitingItems (T-128)", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("holds an id that left the visible set, then releases it", () => {
    // Without this hold there is nothing to animate: groupVisibleItems drops a checked item the
    // instant its status changes, so the row unmounts in the same commit and just vanishes.
    const { result, rerender } = renderHook(({ ids }) => useExitingItems(ids), {
      initialProps: { ids: ["a", "b"] },
    });
    expect(result.current.size).toBe(0);

    rerender({ ids: ["a"] });
    expect([...result.current]).toEqual(["b"]);

    act(() => void vi.advanceTimersByTime(EXIT_ANIMATION_MS + 1));
    expect(result.current.size).toBe(0);
  });

  it("drops an id that comes back before the animation finishes", () => {
    // Rapid check/uncheck, or a collaborator undoing within the window. Without this the row is
    // rendered twice: once live, once as a ghost sliding away.
    const { result, rerender } = renderHook(({ ids }) => useExitingItems(ids), {
      initialProps: { ids: ["a", "b"] },
    });

    rerender({ ids: ["a"] });
    expect(result.current.has("b")).toBe(true);

    rerender({ ids: ["a", "b"] });
    expect(result.current.has("b")).toBe(false);

    // And the pending timer must not resurrect it as exiting afterwards.
    act(() => void vi.advanceTimersByTime(EXIT_ANIMATION_MS + 1));
    expect(result.current.has("b")).toBe(false);
  });

  it("holds several departures independently", () => {
    const { result, rerender } = renderHook(({ ids }) => useExitingItems(ids), {
      initialProps: { ids: ["a", "b", "c"] },
    });

    rerender({ ids: ["a", "c"] });
    act(() => void vi.advanceTimersByTime(EXIT_ANIMATION_MS / 2));
    rerender({ ids: ["a"] });

    expect([...result.current].sort()).toEqual(["b", "c"]);

    // b's window started earlier, so it clears first.
    act(() => void vi.advanceTimersByTime(EXIT_ANIMATION_MS / 2 + 1));
    expect([...result.current]).toEqual(["c"]);

    act(() => void vi.advanceTimersByTime(EXIT_ANIMATION_MS));
    expect(result.current.size).toBe(0);
  });

  it("stays empty when nothing leaves", () => {
    // "Show checked" ON: a check-off never removes the row, so it strikes through in place and
    // this hook has nothing to do.
    const { result, rerender } = renderHook(({ ids }) => useExitingItems(ids), {
      initialProps: { ids: ["a", "b"] },
    });

    rerender({ ids: ["a", "b"] });
    act(() => void vi.advanceTimersByTime(EXIT_ANIMATION_MS + 1));
    expect(result.current.size).toBe(0);
  });
});

describe("useExitingItems view changes (T-128)", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("does not animate rows away when the view itself changed", () => {
    // Turning "show checked" off removes every checked row at once. Sliding twenty rows away would
    // be slow and would misrepresent what happened — nothing was checked off, the filter changed.
    const { result, rerender } = renderHook(
      ({ ids, key }) => useExitingItems(ids, key),
      { initialProps: { ids: ["a", "b", "c"], key: true } },
    );

    rerender({ ids: ["a"], key: false });

    expect(result.current.size).toBe(0);
  });

  it("cancels an in-flight animation when the view changes", () => {
    const { result, rerender } = renderHook(
      ({ ids, key }) => useExitingItems(ids, key),
      { initialProps: { ids: ["a", "b"], key: true } },
    );

    rerender({ ids: ["a"], key: true });
    expect(result.current.has("b")).toBe(true);

    rerender({ ids: ["a"], key: false });
    expect(result.current.size).toBe(0);
  });

  it("still animates a check-off after a view change", () => {
    // The reset must not leave the baseline stale, or the next real check-off is missed.
    const { result, rerender } = renderHook(
      ({ ids, key }) => useExitingItems(ids, key),
      { initialProps: { ids: ["a", "b", "c"], key: true } },
    );

    rerender({ ids: ["a", "b"], key: false });
    expect(result.current.size).toBe(0);

    rerender({ ids: ["a"], key: false });
    expect([...result.current]).toEqual(["b"]);
  });
});
