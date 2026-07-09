import { useEffect, useState } from "react";
import * as api from "../api/client";

const STORAGE_KEY = "shoppinglist_default_currency";
let cached: string | null = typeof localStorage !== "undefined" ? localStorage.getItem(STORAGE_KEY) : null;

export function getCachedDefaultCurrency(): string {
  return cached ?? "EUR";
}

export function setCachedDefaultCurrency(currency: string): void {
  cached = currency;
  localStorage.setItem(STORAGE_KEY, currency);
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
