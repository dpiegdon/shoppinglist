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

/** Whether this list holds expenses rather than things to buy (T-155). */
export function isExpenses(kind: ListKind): boolean {
  return kind === "expenses";
}

/**
 * The i18n key for a kind's name. A key rather than the text: these labels were English-only
 * before the expenses kind arrived, and shipping one untranslated word beside translated UI is
 * worse than translating all three.
 */
export function listKindLabelKey(kind: ListKind): "listKind.checklist" | "listKind.expenses" | "listKind.shopping" {
  if (kind === "checklist") return "listKind.checklist";
  if (kind === "expenses") return "listKind.expenses";
  return "listKind.shopping";
}

/** Overview glyph — a cart for shopping, a check for a checklist, a banknote for expenses. */
export function listKindIcon(kind: ListKind): string {
  if (kind === "checklist") return "✓";
  if (kind === "expenses") return "💶";
  return "🛒";
}
