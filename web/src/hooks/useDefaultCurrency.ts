import { useEffect, useState } from "react";
import * as api from "../api/client";
import { safeLocalStorage } from "../lib/safeStorage";

const STORAGE_KEY = "shoppinglist_default_currency";
let cached: string | null = safeLocalStorage.getItem(STORAGE_KEY);

export function getCachedDefaultCurrency(): string {
  return cached ?? "EUR";
}

export function setCachedDefaultCurrency(currency: string): void {
  cached = currency;
  safeLocalStorage.setItem(STORAGE_KEY, currency);
}

/**
 * Drops the cached currency (T-272): called on logout, so the next account signed in on this
 * browser doesn't briefly see the previous account's currency before its own /settings resolves.
 */
export function clearCachedDefaultCurrency(): void {
  cached = null;
  safeLocalStorage.removeItem(STORAGE_KEY);
}

/** Fetches and caches the account's default currency (Spec: fetched at login, re-read after PATCH /settings). */
export function useDefaultCurrency(): string {
  const [currency, setCurrency] = useState(getCachedDefaultCurrency());

  useEffect(() => {
    let cancelled = false;
    api
      .getSettings()
      .then((settings) => {
        if (!cancelled) {
          setCachedDefaultCurrency(settings.default_currency);
          setCurrency(settings.default_currency);
        }
      })
      .catch(() => {
        // keep the cached/default value on failure
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return currency;
}
