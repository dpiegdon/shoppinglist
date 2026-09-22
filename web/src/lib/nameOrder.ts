/**
 * The one name order both clients use (T-176) — lists on the overview, categories, items, the All
 * items screen. Pinned by shared-test-cases/name-order.json, which Android's NameOrder is tested
 * against too; the rules are spelled out there.
 *
 * Not Intl.Collator: that and java.text.Collator disagree in details, and before this the two
 * clients put accented, non-Latin and emoji-led names in different places.
 */

const FOLDS: ReadonlyArray<[RegExp, string]> = [
  [/ß/g, "ss"],
  [/æ/g, "ae"],
  [/œ/g, "oe"],
  [/ø/g, "o"],
  [/đ/g, "d"],
  [/ł/g, "l"],
  [/þ/g, "th"],
];

/** What a name sorts by: no accents, no case, nothing before its first letter or digit. */
export function nameSortKey(name: string): string {
  let key = name.normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase();
  for (const [from, to] of FOLDS) key = key.replace(from, to);
  return key.replace(/^[^\p{L}\p{Nd}]+/u, "");
}

/**
 * By UTF-16 code unit, which is how Kotlin compares strings too (`String.compareTo`/`<`). Never
 * `localeCompare`: that is locale-aware and disagrees with Kotlin's default order in detail, which
 * is exactly how name order (T-176) and category casing (T-274) drifted between the clients.
 */
export function byCodeUnits(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Two names in the shared order; equal keys fall back to the full name. */
export function compareNames(a: string, b: string): number {
  return byCodeUnits(nameSortKey(a), nameSortKey(b)) || byCodeUnits(a, b);
}

/** A comparator for things with a name and an id, total even when two names are identical. */
export function byName<T>(name: (t: T) => string, id: (t: T) => string): (a: T, b: T) => number {
  return (a, b) => compareNames(name(a), name(b)) || byCodeUnits(id(a), id(b));
}
