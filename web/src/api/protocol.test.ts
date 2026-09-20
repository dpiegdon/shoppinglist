import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  PROTOCOL_HEADER,
  PROTOCOL_VERSION,
  RELOAD_GRACE_MS,
  RELOAD_STORAGE_KEY,
  clientOutdatedAction,
  isClientOutdated,
  onClientOutdated,
  reportClientOutdated,
  resetClientOutdatedForTests,
} from "./protocol";

/** T-240: one silent reload, then the notice — and never a reload loop. */
describe("what a client does about 426 client_outdated", () => {
  // A reload gives the page a fresh module and keeps sessionStorage; the tests below model it by
  // resetting the module state alone.
  const pageReloads = () => resetClientOutdatedForTests();

  beforeEach(() => {
    sessionStorage.clear();
    resetClientOutdatedForTests();
  });

  afterEach(() => {
    resetClientOutdatedForTests();
  });

  it("names the header and a version the server can compare", () => {
    expect(PROTOCOL_HEADER).toBe("X-Client-Protocol");
    expect(Number.isInteger(PROTOCOL_VERSION) && PROTOCOL_VERSION > 0).toBe(true);
  });

  it("reloads the page exactly once and remembers when", () => {
    const reload = vi.fn();

    reportClientOutdated(reload, 1_000_000);

    expect(reload).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(RELOAD_STORAGE_KEY)).toBe("1000000");
    expect(isClientOutdated()).toBe(false);
  });

  it("shows the notice instead of reloading again when a 426 follows within five minutes", () => {
    const reload = vi.fn();
    const notice = vi.fn();

    reportClientOutdated(reload, 1_000_000);
    pageReloads();
    onClientOutdated(notice);
    reportClientOutdated(reload, 1_000_000 + RELOAD_GRACE_MS - 1);

    // The whole point of the guard: a server that keeps refusing must not put the tab in a loop.
    expect(reload).toHaveBeenCalledTimes(1);
    expect(notice).toHaveBeenCalledTimes(1);
    expect(isClientOutdated()).toBe(true);
  });

  it("reloads again once the window has passed — a later upgrade deserves a fresh try", () => {
    const reload = vi.fn();
    const notice = vi.fn();

    reportClientOutdated(reload, 1_000_000);
    pageReloads();
    onClientOutdated(notice);
    reportClientOutdated(reload, 1_000_000 + RELOAD_GRACE_MS);

    expect(reload).toHaveBeenCalledTimes(2);
    expect(notice).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(RELOAD_STORAGE_KEY)).toBe(String(1_000_000 + RELOAD_GRACE_MS));
  });

  it("ignores the refusals of requests that were already in flight while its own reload is under way", () => {
    const reload = vi.fn();
    const notice = vi.fn();
    onClientOutdated(notice);

    // A page start fires several requests at once; all of them come back 426, one after another,
    // before the browser has actually replaced the page. Only the first may act: the others would
    // find the reload marker just set and flash the notice on a page that is already going away.
    reportClientOutdated(reload, 1_000_000);
    reportClientOutdated(reload, 1_000_001);
    reportClientOutdated(reload, 1_000_002);

    expect(reload).toHaveBeenCalledTimes(1);
    expect(notice).not.toHaveBeenCalled();
    expect(isClientOutdated()).toBe(false);
  });

  it("tells a handler that registers after the fact — the refusal can beat the app to mount", () => {
    reportClientOutdated(vi.fn(), 1_000_000);
    pageReloads();
    reportClientOutdated(vi.fn(), 1_000_001);

    const notice = vi.fn();
    onClientOutdated(notice);

    expect(notice).toHaveBeenCalledTimes(1);
  });

  it("ignores a marker that is not a number rather than wedging on the notice", () => {
    sessionStorage.setItem(RELOAD_STORAGE_KEY, "not-a-time");

    expect(clientOutdatedAction(1_000_000)).toBe("reload");
    // ...and it is replaced with something the next call can actually compare against.
    expect(sessionStorage.getItem(RELOAD_STORAGE_KEY)).toBe("1000000");
    expect(clientOutdatedAction(1_000_001)).toBe("notice");
  });
});
