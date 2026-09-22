/**
 * localStorage/sessionStorage, made safe to call from anywhere (T-269).
 *
 * In a browser with site data blocked (or private-mode Safari), touching `localStorage` or
 * `sessionStorage` — even a bare `.getItem()` — throws. A `typeof localStorage === "undefined"`
 * guard does not catch this: the global exists, it just throws when used, so code like
 * `typeof localStorage !== "undefined" ? localStorage.getItem(k) : null` still crashes.
 * `getDeviceId()` in useSync ran this unguarded during render, so a blocked browser white-screened
 * the whole app with no message at all — the same hazard i18n's `storedLocale()` already worked
 * around with its own try/catch. This generalizes that pattern into one helper every call site uses.
 *
 * Every accessor also tolerates the storage object not existing at all (SSR, a test environment
 * with no global): referencing an undeclared `localStorage` throws a ReferenceError, which the
 * try/catch below catches exactly like a throwing accessor.
 */
interface SafeStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function wrap(getStorage: () => Storage): SafeStorage {
  return {
    getItem(key: string): string | null {
      try {
        return getStorage().getItem(key);
      } catch {
        return null;
      }
    },
    setItem(key: string, value: string): void {
      try {
        getStorage().setItem(key, value);
      } catch {
        // Best-effort: losing a preference or a cached value on the next visit is far better than
        // failing whatever action the caller was actually performing.
      }
    },
    removeItem(key: string): void {
      try {
        getStorage().removeItem(key);
      } catch {
        // Best-effort, as above.
      }
    },
  };
}

export const safeLocalStorage: SafeStorage = wrap(() => localStorage);
export const safeSessionStorage: SafeStorage = wrap(() => sessionStorage);
