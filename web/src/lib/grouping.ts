import type { ItemObject } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";
import { canonicalCategoryNames, categoryKey, UNCATEGORIZED_LABEL } from "./categories";

export interface CategoryGroup {
  category: string;
  items: ItemObject[];
}

const UNCATEGORIZED_KEY = "";

/**
 * Groups the list's visible items by category, ordered per `categoryOrder`;
 * categories not present in it are appended afterward, alphabetically. `todo`
 * and (when `showChecked`) `checked` items are mixed into one alphabetically
 * sorted list per category - a checked item stays in its category, in the
 * same position it would occupy as `todo`, rather than being pulled into a
 * separate "Checked" section. `backlog` items are never visible here.
 * (Spec: client-ui-notes.md List view.)
 *
 * Categories are grouped case-insensitively (T-108): "Group" and "group" merge
 * into one bucket, labelled with the canonical casing (a `category_order` match,
 * else the most-common casing among the items).
 */
export function groupVisibleItems(
  items: ItemObject[],
  categoryOrder: string[],
  showChecked: boolean,
): CategoryGroup[] {
  const visible = items.filter((item) => {
    const status = itemFieldValue(item, "status");
    return status === "todo" || (showChecked && status === "checked");
  });

  const names = canonicalCategoryNames(
    visible.map((item) => itemFieldValue(item, "category") ?? ""),
    categoryOrder,
  );

  const byKey = new Map<string, ItemObject[]>();
  for (const item of visible) {
    const key = categoryKey(itemFieldValue(item, "category") ?? "");
    const bucket = byKey.get(key);
    if (bucket) {
      bucket.push(item);
    } else {
      byKey.set(key, [item]);
    }
  }

  for (const bucket of byKey.values()) {
    bucket.sort((a, b) => (itemFieldValue(a, "name") ?? "").localeCompare(itemFieldValue(b, "name") ?? ""));
  }

  const displayName = (key: string) =>
    key === UNCATEGORIZED_KEY ? UNCATEGORIZED_LABEL : names.get(key) ?? key;

  const orderedKeys: string[] = [];
  const seen = new Set<string>();
  for (const entry of categoryOrder) {
    const key = categoryKey(entry);
    if (key !== UNCATEGORIZED_KEY && byKey.has(key) && !seen.has(key)) {
      orderedKeys.push(key);
      seen.add(key);
    }
  }
  const remaining = Array.from(byKey.keys())
    .filter((key) => key !== UNCATEGORIZED_KEY && !seen.has(key))
    .sort((a, b) => displayName(a).localeCompare(displayName(b)));
  orderedKeys.push(...remaining);
  if (byKey.has(UNCATEGORIZED_KEY)) orderedKeys.push(UNCATEGORIZED_KEY);

  return orderedKeys.map((key) => ({ category: displayName(key), items: byKey.get(key)! }));
}

/** Every `checked` item regardless of visibility - used for the "Clear checked" count/bulk action. */
export function checkedItems(items: ItemObject[]): ItemObject[] {
  return items.filter((item) => itemFieldValue(item, "status") === "checked");
}
