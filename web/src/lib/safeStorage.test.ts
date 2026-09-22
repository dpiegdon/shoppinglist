import { afterEach, describe, expect, it, vi } from "vitest";
import { safeLocalStorage, safeSessionStorage } from "./safeStorage";

/**
 * A browser with site data blocked (or private-mode Safari) has a `localStorage` that EXISTS but
 * THROWS on every access — including a bare `.getItem()` (T-269). A `typeof localStorage ===
 * "undefined"` guard does not catch this: the property is there, it just throws when read. These
 * tests simulate that by making the getter itself throw, which is what a blocked browser does.
 */
function makeThrowingStorage(): Storage {
  const boom = () => {
    throw new DOMException("The operation is insecure.", "SecurityError");
  };
  return {
    getItem: boom,
    setItem: boom,
    removeItem: boom,
    clear: boom,
    key: boom,
    length: 0,
  } as unknown as Storage;
}

describe("safeLocalStorage / safeSessionStorage (T-269)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it("getItem returns null instead of throwing when the underlying accessor throws", () => {
    vi.spyOn(window, "localStorage", "get").mockReturnValue(makeThrowingStorage());
    expect(() => safeLocalStorage.getItem("k")).not.toThrow();
    expect(safeLocalStorage.getItem("k")).toBeNull();
  });

  it("setItem swallows a throw instead of propagating it", () => {
    vi.spyOn(window, "localStorage", "get").mockReturnValue(makeThrowingStorage());
    expect(() => safeLocalStorage.setItem("k", "v")).not.toThrow();
  });

  it("removeItem swallows a throw instead of propagating it", () => {
    vi.spyOn(window, "localStorage", "get").mockReturnValue(makeThrowingStorage());
    expect(() => safeLocalStorage.removeItem("k")).not.toThrow();
  });

  it("sessionStorage accessors are guarded the same way", () => {
    vi.spyOn(window, "sessionStorage", "get").mockReturnValue(makeThrowingStorage());
    expect(() => safeSessionStorage.getItem("k")).not.toThrow();
    expect(safeSessionStorage.getItem("k")).toBeNull();
    expect(() => safeSessionStorage.setItem("k", "v")).not.toThrow();
    expect(() => safeSessionStorage.removeItem("k")).not.toThrow();
  });

  it("behaves like ordinary storage when nothing is blocked", () => {
    safeLocalStorage.setItem("shoppinglist_test_key", "hello");
    expect(safeLocalStorage.getItem("shoppinglist_test_key")).toBe("hello");
    safeLocalStorage.removeItem("shoppinglist_test_key");
    expect(safeLocalStorage.getItem("shoppinglist_test_key")).toBeNull();
  });
});
