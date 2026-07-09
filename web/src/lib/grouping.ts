import type { ItemObject } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";

export interface CategoryGroup {
  category: string;
  items: ItemObject[];
}

const UNCATEGORIZED = "—";

/**
 * Groups `todo` items by category, ordered per `categoryOrder`; categories not
 * present in it are appended afterward, alphabetically. Items are sorted
 * alphabetically within each group. Uncategorized items (no category, or an
 * empty string) form a trailing "—" group. (Spec: client-ui-notes.md List view.)
 */
export function groupTodoItems(items: ItemObject[], categoryOrder: string[]): CategoryGroup[] {
  const todo = items.filter((item) => itemFieldValue(item, "status") === "todo");

  const byCategory = new Map<string, ItemObject[]>();
  for (const item of todo) {
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

/** `checked` items, alphabetical — shown only when the show-checked toggle is on. */
export function checkedItems(items: ItemObject[]): ItemObject[] {
  return items
    .filter((item) => itemFieldValue(item, "status") === "checked")
    .sort((a, b) => (itemFieldValue(a, "name") ?? "").localeCompare(itemFieldValue(b, "name") ?? ""));
}
