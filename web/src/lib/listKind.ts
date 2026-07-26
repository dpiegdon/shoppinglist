import type { ListKind, ListObject } from "../api/contract";
import { listFieldValue } from "../hooks/useSync";

/**
 * List kind helpers (T-110). A checklist hides the shopping-only item fields; everything else
 * (categories, notes, statuses, registry, sharing, sync) is identical. The visibility rule lives
 * here so the dialog, the item row and the settings switch can't drift — and must match the
 * Android client (docs/archive/specs/client-ui-notes.md).
 */

export const DEFAULT_LIST_KIND: ListKind = "shopping";

/** A list's kind, defaulting to shopping — absent for pre-T-110 rows and older servers. */
export function listKind(list: ListObject | undefined): ListKind {
  if (!list) return DEFAULT_LIST_KIND;
  return listFieldValue(list, "kind") ?? DEFAULT_LIST_KIND;
}

/** Whether the shopping-only item fields (stores, price, quantity) are shown for this kind. */
export function showsShoppingFields(kind: ListKind): boolean {
  return kind === "shopping";
}

export function listKindLabel(kind: ListKind): string {
  return kind === "checklist" ? "Checklist" : "Shopping list";
}

/** Overview glyph — a cart for shopping, a check for a plain checklist. */
export function listKindIcon(kind: ListKind): string {
  return kind === "checklist" ? "✓" : "🛒";
}
