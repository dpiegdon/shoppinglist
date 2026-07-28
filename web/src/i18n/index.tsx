import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { DEFAULT_LOCALE, LOCALES, localeDir, matchLocale, resolveLocale } from "./locales";
import type { Locale } from "./locales";
import { de } from "./messages/de";
import { en } from "./messages/en";
import type { Catalog, MessageKey } from "./messages/en";

export { LOCALES, DEFAULT_LOCALE, localeDir, matchLocale, resolveLocale };
export type { Locale, MessageKey };

const STORAGE_KEY = "shoppinglist_locale";

/**
 * Loaded catalogs. English is bundled eagerly because it is also the fallback for every partial
 * translation — it must always be present, synchronously, or a missing key would render as its
 * own identifier. Others are registered by [[registerCatalog]] as T-124 adds them.
 */
const catalogs = new Map<Locale, Catalog>([
  ["en", en],
  ["de", de],
]);

export function registerCatalog(locale: Locale, catalog: Catalog): void {
  catalogs.set(locale, catalog);
}

// Strong right-to-left characters: Hebrew, Arabic (incl. supplement and extended-A), and the
// Arabic presentation forms. Enough to decide whether a value needs isolating.
const RTL_CHARS = /[֐-׿؀-ۿ܀-ݏݐ-ݿࢠ-ࣿיִ-﷿ﹰ-﻿]/;

// First Strong Isolate / Pop Directional Isolate.
const FSI = "⁨";
const PDI = "⁩";

/**
 * Wraps an interpolated value in bidi isolates when it actually needs them (T-126).
 *
 * User-authored text dropped into a sentence reorders the surrounding punctuation without this:
 * an Arabic list name inside "Casing fixed in {category}: {count}" drags the colon to the wrong
 * side. Isolating the value tells the bidi algorithm to resolve it independently of its
 * surroundings.
 *
 * Only when RTL is present, deliberately: isolates are invisible but they are real characters, and
 * adding them unconditionally would change every interpolated string in the app — including in
 * tests and in copy-pasted text — to buy nothing for the overwhelmingly common all-LTR case.
 */
function isolate(value: string): string {
  return RTL_CHARS.test(value) ? `${FSI}${value}${PDI}` : value;
}

/**
 * Substitutes `{name}` placeholders. Unknown placeholders are left untouched rather than replaced
 * with "undefined": a translator's typo should degrade to visible, diagnosable text, not to a word
 * that looks deliberate.
 */
function interpolate(template: string, params?: Record<string, string | number>): string {
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in params ? isolate(String(params[name])) : whole,
  );
}

export function translate(
  locale: Locale,
  key: MessageKey,
  params?: Record<string, string | number>,
): string {
  const template = catalogs.get(locale)?.[key] ?? en[key];
  return interpolate(template, params);
}

/** The device's stored choice, if it is still a language we ship. */
function storedLocale(): Locale | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? matchLocale(raw) : null;
  } catch {
    // localStorage throws in private-mode Safari and when cookies are blocked. A missing
    // preference is recoverable; a crash at startup is not.
    return null;
  }
}

function detectLocale(): Locale {
  const stored = storedLocale();
  if (stored) return stored;
  if (typeof navigator === "undefined") return DEFAULT_LOCALE;
  // navigator.languages is the ordered preference list; navigator.language is only the top one.
  return resolveLocale(navigator.languages ?? [navigator.language]);
}

export type TranslateFn = (key: MessageKey, params?: Record<string, string | number>) => string;

interface I18nValue {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: TranslateFn;
}

/**
 * Default value, deliberately functional rather than null.
 *
 * `t` must work in any tree, provider or not. The provider is mounted once at the app root
 * (main.tsx), so nothing in production can render outside it — while in tests components are
 * routinely rendered as isolated subtrees, and requiring every one of them to wrap in a provider
 * would be a permanent tax on writing a test for a component that merely happens to contain a
 * label. Falling back to English is exactly what those tests want to assert anyway.
 *
 * `setLocale` is different: it is meaningless without the state the provider holds, so it fails
 * loudly instead of silently doing nothing. That keeps the guard where it has real value — a
 * language chooser mounted outside the provider (T-127) is a genuine bug — without imposing it
 * where it has none.
 */
const I18nContext = createContext<I18nValue>({
  locale: DEFAULT_LOCALE,
  setLocale: () => {
    throw new Error("setLocale requires <I18nProvider>; mount it at the app root.");
  },
  t: (key, params) => translate(DEFAULT_LOCALE, key, params),
});

/**
 * Holds the active language and re-renders the tree when it changes.
 *
 * Deliberately React state rather than a module-level constant read at import: the language
 * chooser (T-127) must apply immediately without a reload, and a value captured at import time
 * cannot do that. It also owns the document's `lang`/`dir` attributes, so selecting Arabic flips
 * writing direction for the whole page in the same commit as the text changes (T-126).
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(detectLocale);

  useEffect(() => {
    if (typeof document !== "undefined") {
      document.documentElement.lang = locale;
      document.documentElement.dir = localeDir(locale);
    }
  }, [locale]);

  const setLocale = useCallback((next: Locale) => {
    setLocaleState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // See storedLocale(): an unwritable localStorage costs the preference on the next visit,
      // which is far better than failing the click that set it.
    }
  }, []);

  const value = useMemo<I18nValue>(
    () => ({
      locale,
      setLocale,
      t: (key, params) => translate(locale, key, params),
    }),
    [locale, setLocale],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  return useContext(I18nContext);
}

/** The common case: just the translate function. */
export function useT(): TranslateFn {
  return useI18n().t;
}
