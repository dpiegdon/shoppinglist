// Category identity is case-insensitive (T-108): "Group" and "group" are one category. Grouping,
// the settings registry, and the item-dialog autocomplete all key on this. The rule here MUST match
// the Android client (see docs/superpowers/specs/client-ui-notes.md) or the two would show
// different canonical casings for the same data.

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
      if (count > bestCount || (count === bestCount && casing.localeCompare(best) < 0)) {
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
  return Array.from(canonicalCategoryNames(rawCategories, categoryOrder).values()).sort((a, b) =>
    a.localeCompare(b),
  );
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

  let orderChanged = false;
  const seen = new Set<string>();
  const nextCategoryOrder: string[] = [];
  for (const entry of categoryOrder) {
    const replaced = categoryKey(entry) === fromKey ? to : entry;
    const replacedKey = categoryKey(replaced);
    if (!replacedKey) {
      orderChanged = true; // dropped an empty entry
      continue;
    }
    if (seen.has(replacedKey)) {
      orderChanged = true; // de-duplicated a case-insensitive collision (merge)
      continue;
    }
    seen.add(replacedKey);
    if (replaced !== entry) orderChanged = true;
    nextCategoryOrder.push(replaced);
  }
  return { itemIds, nextCategoryOrder, orderChanged };
}
