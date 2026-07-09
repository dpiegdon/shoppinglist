import type { ItemObject } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";

export interface CategoryGroup {
  category: string;
  items: ItemObject[];
}

const UNCATEGORIZED = "—";

/**
 * Groups the list's visible items by category, ordered per `categoryOrder`;
 * categories not present in it are appended afterward, alphabetically. `todo`
 * and (when `showChecked`) `checked` items are mixed into one alphabetically
 * sorted list per category - a checked item stays in its category, in the
 * same position it would occupy as `todo`, rather than being pulled into a
 * separate "Checked" section. `backlog` items are never visible here.
 * (Spec: client-ui-notes.md List view.)
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

  const byCategory = new Map<string, ItemObject[]>();
  for (const item of visible) {
    const category = itemFieldValue(item, "category")?.trim() || UNCATEGORIZED;
    const bucket = byCategory.get(category);
    if (bucket) {
      bucket.push(item);
    } else {
      byCategory.set(category, [item]);
    }
  }

  for (const bucket of byCategory.values()) {
    bucket.sort((a, b) => (itemFieldValue(a, "name") ?? "").localeCompare(itemFieldValue(b, "name") ?? ""));
  }

  const ordered: string[] = [];
  for (const category of categoryOrder) {
    if (byCategory.has(category)) ordered.push(category);
  }
  const remaining = Array.from(byCategory.keys())
    .filter((c) => !ordered.includes(c) && c !== UNCATEGORIZED)
    .sort((a, b) => a.localeCompare(b));
  ordered.push(...remaining);
  if (byCategory.has(UNCATEGORIZED)) ordered.push(UNCATEGORIZED);

  return ordered.map((category) => ({ category, items: byCategory.get(category)! }));
}

/** Every `checked` item regardless of visibility - used for the "Clear checked" count/bulk action. */
export function checkedItems(items: ItemObject[]): ItemObject[] {
  return items.filter((item) => itemFieldValue(item, "status") === "checked");
}
