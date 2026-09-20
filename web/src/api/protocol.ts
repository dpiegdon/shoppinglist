/**
 * The client/server protocol version (T-240) and what this client does when the server says it is
 * too old.
 *
 * Every API request carries `X-Client-Protocol: <PROTOCOL_VERSION>`. A server whose own version is
 * higher answers `426 client_outdated` before authenticating, touching the database or writing an
 * audit record — so an outdated build is turned away rather than left to misread data.
 *
 * The same integer lives in `server/src/shoppinglist_server/protocol.py` and in the Android
 * client's `Protocol.kt`; protocolVersion.test.ts reads all three and asserts they are equal.
 */
export const PROTOCOL_VERSION = 3;
export const PROTOCOL_HEADER = "X-Client-Protocol";

/**
 * Where the one automatic reload is remembered. sessionStorage, not localStorage: the guard is
 * about *this* page's reload loop, and a stale marker must not survive the tab and suppress the
 * reload that would have fixed things tomorrow.
 */
export const RELOAD_STORAGE_KEY = "shoppinglist_protocol_reload";

/**
 * How long a reload counts as "just tried". A 426 arriving inside this window means reloading did
 * not help — the browser served a cached bundle, or the operator's server is ahead of the bundle
 * it serves — so the user is told instead of being reloaded again.
 */
export const RELOAD_GRACE_MS = 5 * 60 * 1000;

function readAttempt(): number | null {
  if (typeof sessionStorage === "undefined") return null;
  const raw = sessionStorage.getItem(RELOAD_STORAGE_KEY);
  const at = raw === null ? Number.NaN : Number(raw);
  return Number.isFinite(at) ? at : null;
}

function recordAttempt(now: number): void {
  if (typeof sessionStorage === "undefined") return;
  sessionStorage.setItem(RELOAD_STORAGE_KEY, String(now));
}

/**
 * What to do about a 426, recording the reload attempt when it decides to reload.
 *
 * The web bundle is served by the same server that just refused us, so a reload fetches the
 * current one and the whole thing is over before the user sees anything. Only when that has
 * already been tried is there something worth showing.
 */
export function clientOutdatedAction(now: number = Date.now()): "reload" | "notice" {
  const attemptedAt = readAttempt();
  if (attemptedAt !== null && now - attemptedAt < RELOAD_GRACE_MS) {
    return "notice";
  }
  recordAttempt(now);
  return "reload";
}

// Same subscriber seam as client.ts's forced logout: this module has no access to React context,
// so the notice is raised through a handler the app root registers. Latched, because the 426 can
// land before the root has mounted (the first sync starts from a mount effect) and the notice must
// not be lost: a late subscriber is told immediately.
let noticeHandler: (() => void) | null = null;
let noticeRaised = false;
let reloadStarted = false;

/** Registers the app root's "show the full-page notice" handler; null unregisters. */
export function onClientOutdated(handler: (() => void) | null): void {
  noticeHandler = handler;
  if (handler && noticeRaised) handler();
}

/** Whether the notice is already due — the initial state for a root mounting after the fact. */
export function isClientOutdated(): boolean {
  return noticeRaised;
}

/**
 * Acts on a `426 client_outdated`: one silent reload, then the notice.
 *
 * [reload] and [now] are injectable only so tests can observe the reload and control the clock;
 * production calls this with no arguments.
 */
export function reportClientOutdated(
  reload: () => void = () => window.location.reload(),
  now: number = Date.now(),
): void {
  // The reload is under way and this page is about to be replaced. Requests that were already in
  // flight when the first 426 came back are refused too; without this they would find the reload
  // marker set, conclude that reloading did not help and flash the notice on a page that is
  // already going away.
  if (reloadStarted) return;
  if (clientOutdatedAction(now) === "reload") {
    reloadStarted = true;
    reload();
    return;
  }
  noticeRaised = true;
  noticeHandler?.();
}

/** Test-only reset of the latched notice; a real page gets a fresh module on every load. */
export function resetClientOutdatedForTests(): void {
  noticeRaised = false;
  noticeHandler = null;
  reloadStarted = false;
}
