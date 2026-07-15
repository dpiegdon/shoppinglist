import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { useShowChecked } from "./useShowChecked";

describe("useShowChecked (session-shared toggle)", () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => sessionStorage.clear());

  it("defaults to hidden when nothing is stored", () => {
    const { result } = renderHook(() => useShowChecked());
    expect(result.current[0]).toBe(false);
  });

  it("toggling persists to sessionStorage", () => {
    const { result } = renderHook(() => useShowChecked());

    act(() => result.current[1]());

    expect(result.current[0]).toBe(true);
    expect(sessionStorage.getItem("shoppinglist_show_checked")).toBe("true");
  });

  it("a freshly mounted list picks up the session's stored choice (shared across lists)", () => {
    sessionStorage.setItem("shoppinglist_show_checked", "true");

    const { result } = renderHook(() => useShowChecked());

    expect(result.current[0]).toBe(true);
  });
});
