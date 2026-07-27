/**
 * The languages this client ships, and how a browser locale is narrowed to one.
 *
 * Kept in its own module (not index.ts) so the locale-matching logic can be unit-tested without
 * pulling in React, and so the Android client's equivalent list has an obvious counterpart to stay
 * in step with.
 */

/**
 * Shipped languages. Each entry's `name` is written in its OWN language, never translated into the
 * current UI language — that is the standard convention, and it is the only way a user who has
 * accidentally selected a language they cannot read can find their way back out.
 *
 * `dir` drives the document's writing direction. Only Arabic is RTL today; keeping it as data
 * rather than an `=== "ar"` test means adding Hebrew or Farsi later touches this table only.
 */
export const LOCALES = [
  { tag: "en", name: "English", dir: "ltr" },
  { tag: "de", name: "Deutsch", dir: "ltr" },
  { tag: "es", name: "Español", dir: "ltr" },
  { tag: "fr", name: "Français", dir: "ltr" },
  { tag: "pt-BR", name: "Português (Brasil)", dir: "ltr" },
  { tag: "zh-Hans", name: "简体中文", dir: "ltr" },
  { tag: "ja", name: "日本語", dir: "ltr" },
  { tag: "uk", name: "Українська", dir: "ltr" },
  { tag: "ar", name: "العربية", dir: "rtl" },
] as const;

export type Locale = (typeof LOCALES)[number]["tag"];

export const DEFAULT_LOCALE: Locale = "en";

const BY_TAG = new Map(LOCALES.map((l) => [l.tag.toLowerCase(), l]));

export function localeDir(tag: Locale): "ltr" | "rtl" {
  return BY_TAG.get(tag.toLowerCase())?.dir ?? "ltr";
}

/**
 * Narrows one BCP-47 tag to a shipped language, or null if nothing matches.
 *
 * Three passes, most specific first: exact ("pt-BR"), then the same base language with any region
 * ("pt-PT" -> "pt-BR", "de-AT" -> "de"), then a bare base ("zh" -> "zh-Hans"). The middle pass is
 * what stops a Portuguese browser from falling all the way back to English just because we ship
 * the Brazilian variant, and the last is what makes a bare "zh" resolve at all.
 */
export function matchLocale(tag: string): Locale | null {
  const wanted = tag.trim().toLowerCase();
  if (!wanted) return null;

  const exact = BY_TAG.get(wanted);
  if (exact) return exact.tag;

  const base = wanted.split("-")[0];
  const sameBase = LOCALES.find((l) => l.tag.toLowerCase().split("-")[0] === base);
  return sameBase ? sameBase.tag : null;
}

/**
 * The best shipped language for a browser's ordered preference list, else DEFAULT_LOCALE.
 * Order matters: the browser lists preferences most-wanted first, so the first match wins.
 */
export function resolveLocale(preferred: readonly string[]): Locale {
  for (const tag of preferred) {
    const match = matchLocale(tag);
    if (match) return match;
  }
  return DEFAULT_LOCALE;
}
