import { byCodeUnits } from "./nameOrder";

// Category identity is case-insensitive (T-108): "Group" and "group" are one category. Grouping,
// the settings registry, and the item-dialog autocomplete all key on this. The rule here MUST match
// the Android client (see docs/archive/specs/client-ui-notes.md) or the two would show
// different canonical casings for the same data.
//
// Tie-breaks and the autocomplete order use byCodeUnits (T-274), never localeCompare: Android
// breaks ties with plain `<`/`sorted()`, which compare by UTF-16 code unit, not locale. The two
// looked equivalent for "unequal-count" cases and disagreed only on a genuine tie — "Obst" vs.
// "obst" canonicalised to "obst" here and "Obst" on Android. Pinned by
// shared-test-cases/category-canon.json, driven by both suites.

export const UNCATEGORIZED_LABEL = "—";

/** The case-insensitive identity of a category. Trimmed + lowercased; "" means uncategorized. */
export function categoryKey(raw: string): string {
  return raw.trim().toLowerCase();
}

/**
 * Canonical display casing for every category key present in `rawCategories` or `categoryOrder`.
 * A `category_order` entry's casing wins (case-insensitive match); otherwise the most-frequent
 * casing among the items, tie-broken lexicographically. Deterministic so every client agrees on
 * what to show for a merged bucket before anyone fixes the casing centrally.
 */
export function canonicalCategoryNames(
  rawCategories: string[],
  categoryOrder: string[],
): Map<string, string> {
  const votes = new Map<string, Map<string, number>>();
  for (const raw of rawCategories) {
    const trimmed = raw.trim();
    if (!trimmed) continue;
    const key = trimmed.toLowerCase();
    let byCasing = votes.get(key);
    if (!byCasing) {
      byCasing = new Map();
      votes.set(key, byCasing);
    }
    byCasing.set(trimmed, (byCasing.get(trimmed) ?? 0) + 1);
  }

  const names = new Map<string, string>();
  for (const [key, byCasing] of votes) {
    let best = "";
    let bestCount = -1;
    for (const [casing, count] of byCasing) {
      if (count > bestCount || (count === bestCount && byCodeUnits(casing, best) < 0)) {
        best = casing;
        bestCount = count;
      }
    }
    names.set(key, best);
  }
  // category_order casing is authoritative — it's what the user set in list settings.
  for (const entry of categoryOrder) {
    const trimmed = entry.trim();
    if (trimmed) names.set(trimmed.toLowerCase(), trimmed);
  }
  return names;
}

/** The distinct categories in use (canonical casing), sorted — for the item-dialog autocomplete. */
export function distinctCanonicalCategories(rawCategories: string[], categoryOrder: string[]): string[] {
  return Array.from(canonicalCategoryNames(rawCategories, categoryOrder).values()).sort(byCodeUnits);
}

/**
 * The distinct stores in use (canonical casing), sorted — for the item dialog's store chips
 * (T-139). Canonicalization is the category rule verbatim: same case-insensitive identity, same
 * most-frequent-casing vote, only the domain differs. Android reuses CategoryCanon here for
 * exactly the same reason, so the two clients canonicalize stores identically.
 */
export function distinctCanonicalStores(rawStores: string[]): string[] {
  return distinctCanonicalCategories(rawStores, []);
}

/**
 * A clean `category_order` (T-212): entries trimmed, blanks dropped, and a case-insensitive
 * duplicate collapsed onto its first occurrence, whose casing stays — it is the one the user set.
 * Every write of the order goes through this, and list settings renders exactly what it would
 * save: an entry that is stored but not shown (a blank, or a second casing left from before
 * categories were case-insensitive, T-108) once made the reorder arrows swap with an invisible
 * neighbour, so a press changed nothing on screen.
 */
export function normalizeCategoryOrder(order: string[]): string[] {
  const seen = new Set<string>();
  const clean: string[] = [];
  for (const entry of order) {
    const trimmed = entry.trim();
    const key = trimmed.toLowerCase();
    if (!key || seen.has(key)) continue;
    seen.add(key);
    clean.push(trimmed);
  }
  return clean;
}

export interface CategoryRenamePlan {
  /** Ids of items whose `category` should be rewritten to `toName` (excludes ones already equal). */
  itemIds: string[];
  /** `category_order` after replacing the renamed key and de-duplicating case-insensitively. */
  nextCategoryOrder: string[];
  /** Whether `nextCategoryOrder` differs from the input (so callers can skip a no-op list push). */
  orderChanged: boolean;
}

/**
 * Plan the shared "canonicalize a category" write (T-108): rewrite every item whose category
 * matches `fromKey` case-insensitively to `toName`, and update the matching `category_order` entry
 * to the new spelling (de-duplicating if the new name collides with another entry — a merge). Both
 * the item-dialog recase-all and the list-settings rename go through this.
 */
export function planCategoryRename(
  itemsInList: { id: string; category: string }[],
  categoryOrder: string[],
  fromKey: string,
  toName: string,
): CategoryRenamePlan {
  const to = toName.trim();
  const itemIds = itemsInList
    .filter((it) => categoryKey(it.category) === fromKey && it.category.trim() !== to)
    .map((it) => it.id);

  const nextCategoryOrder = normalizeCategoryOrder(
    categoryOrder.map((entry) => (categoryKey(entry) === fromKey ? to : entry)),
  );
  const orderChanged =
    nextCategoryOrder.length !== categoryOrder.length ||
    nextCategoryOrder.some((entry, i) => entry !== categoryOrder[i]);
  return { itemIds, nextCategoryOrder, orderChanged };
}
