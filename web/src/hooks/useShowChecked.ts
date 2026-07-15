import { useCallback, useState } from "react";

const STORAGE_KEY = "shoppinglist_show_checked";

/**
 * The list screen's "show checked" toggle, shared across every list for the browser session and
 * remembered on reload — persisted in sessionStorage (device/session-local, never synced to the
 * server). A fresh browser session starts with checked items hidden. Mirrors the Android app's
 * global, remembered show-checked preference.
 */
export function useShowChecked(): [boolean, () => void] {
  const [showChecked, setShowChecked] = useState<boolean>(() => sessionStorage.getItem(STORAGE_KEY) === "true");

  const toggle = useCallback(() => {
    setShowChecked((previous) => {
      const next = !previous;
      sessionStorage.setItem(STORAGE_KEY, String(next));
      return next;
    });
  }, []);

  return [showChecked, toggle];
}
