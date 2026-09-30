import { useEffect, useMemo, useRef, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import AddFab from "../components/AddFab";
import ListIcon from "../components/ListIcon";
import * as api from "../api/client";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue, nowMs } from "../hooks/useSync";
import { groupVisibleItems } from "../lib/grouping";
import {
  categoryKey,
  distinctCanonicalCategories,
  distinctCanonicalStores,
  planCategoryRename,
} from "../lib/categories";
import { listKind, showsDueDate, showsShoppingFields } from "../lib/listKind";
import ItemRow from "../components/ItemRow";
import ItemDialog, { type ItemDialogSaveValues } from "../components/ItemDialog";
import { useDefaultCurrency } from "../hooks/useDefaultCurrency";
import { useShowChecked } from "../hooks/useShowChecked";
import { useLiveListSync } from "../hooks/useLiveListSync";
import { useExitingItems } from "../hooks/useExitingItems";
import type { ItemObject, ItemStatus, Member } from "../api/contract";
import { useT } from "../i18n";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { errorMessage } from "../i18n/apiErrors";

/** How long the "Checked off" toast offers Undo, unless the pointer or the focus holds it. */
const UNDO_TOAST_MS = 5000;

export default function ListPage() {
  const t = useT();
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId } = useSyncContext();
  const defaultCurrency = useDefaultCurrency();
  const [showChecked, toggleShowChecked] = useShowChecked();
  const [dialogItem, setDialogItem] = useState<ItemObject | "new" | null>(null);
  const [undo, setUndo] = useState<{ itemId: string; previousStatus: ItemStatus } | null>(null);
  const undoTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(undoTimer.current), []);
  // Transient confirmation for a category recase-all (T-108), since one item edit rewrites many.
  const [categoryToast, setCategoryToast] = useState<string | null>(null);
  // A failed toggle/undo (T-266): neither goes through a dialog, so without this a rejected push
  // was an unhandled rejection that left the row un-struck-through and said nothing at all.
  const [actionError, setActionError] = useState<string | null>(null);
  // Fetched once per list open, best-effort (T-64) — an empty roster on error/offline correctly
  // hides the last-touched-by indicator (fewer members shown is a safe default) rather than
  // erroring the whole page. Mirrors the Android ListViewModel's equivalent fetch.
  const [members, setMembers] = useState<Member[]>([]);

  useEffect(() => {
    if (!listId) return;
    let cancelled = false;
    api
      .getMembers(listId)
      .then((r) => { if (!cancelled) setMembers(r.members); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [listId]);

  // Five-second refresh while this list is open and the tab is visible (T-128), so two people
  // shopping together see each other's picks without either of them doing anything.
  useLiveListSync();

  const list = listId ? lists.get(listId) : undefined;
  useDocumentTitle(list && listFieldValue(list, "name"));

  const listItems = useMemo(
    () => (listId ? Array.from(items.values()).filter((i) => i.list_id === listId) : []),
    [items, listId],
  );

  const categoryOrder = list ? listFieldValue(list, "category_order") ?? [] : [];
  // Checklists hide the shopping-only item fields (T-110).
  const showShopping = showsShoppingFields(listKind(list));
  // Checklists alone offer and show a due date (T-323).
  const showDue = showsDueDate(listKind(list));
  const groups = useMemo(
    () => (list ? groupVisibleItems(listItems, categoryOrder, showChecked) : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [list, listItems, showChecked, JSON.stringify(categoryOrder)],
  );
  // Ids that just left the visible set stay rendered briefly so they can animate away (T-128).
  // With "show checked" ON nothing ever leaves on a check-off, so this is naturally empty and the
  // row simply gains its strike-through in place — which is what that setting asks for.
  //
  // Computed BEFORE the early returns below: these are hooks, so they have to run on every render
  // or React sees a different hook count between renders ("Rendered more hooks than during the
  // previous render") the first time a list fails to resolve.
  const visibleIds = useMemo(
    () => groups.flatMap((group) => group.items.map((item) => item.id)),
    [groups],
  );
  // showChecked is the reset key: flipping it changes WHICH rows this view shows, so the rows it
  // removes were not checked off and must not animate away.
  const exitingIds = useExitingItems(visibleIds, showChecked);

  if (!listId) return <Navigate to="/" replace />;
  if (!list) {
    return (
      <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
        <p className="muted">{t("list.notFound")}</p>
        <Link to="/">{t("list.backToOverview")}</Link>
      </main>
    );
  }

  const exitingItems = listItems.filter((item) => exitingIds.has(item.id));
  // Existing categories (canonical casing) for the item dialog's autocomplete (T-108).
  const categorySuggestions = distinctCanonicalCategories(
    listItems.map((i) => itemFieldValue(i, "category") ?? ""),
    categoryOrder,
  );
  // Existing stores (canonical casing) for the item dialog's store chips (T-139). Every item's
  // stores array flattened, so a store used once anywhere in the list is offered everywhere in it.
  const storeSuggestions = distinctCanonicalStores(
    listItems.flatMap((i) => itemFieldValue(i, "stores") ?? []),
  );

  async function setItemStatus(itemId: string, status: ItemStatus) {
    await push({
      items: [{ id: itemId, list_id: listId!, fields: fieldPatch(deviceId, "status", status) }],
    });
  }

  async function handleToggle(item: ItemObject) {
    const current = itemFieldValue(item, "status") ?? "todo";
    const next: ItemStatus = current === "checked" ? "todo" : "checked";
    try {
      await setItemStatus(item.id, next);
    } catch (err) {
      // Nothing was applied — items only ever change via a successfully-applied sync response —
      // so there's nothing to revert, only something to say (T-266).
      setActionError(errorMessage(t, err, "item.saveFailed"));
      return;
    }
    setActionError(null);
    if (next === "checked") {
      setUndo({ itemId: item.id, previousStatus: current });
      scheduleUndoDismiss(item.id);
    }
  }

  function scheduleUndoDismiss(itemId: string) {
    clearTimeout(undoTimer.current);
    undoTimer.current = setTimeout(() => setUndo((u) => (u?.itemId === itemId ? null : u)), UNDO_TOAST_MS);
  }

  function holdUndo() {
    clearTimeout(undoTimer.current);
  }

  function releaseUndo() {
    if (undo) scheduleUndoDismiss(undo.itemId);
  }

  async function handleUndo() {
    if (!undo) return;
    try {
      await setItemStatus(undo.itemId, undo.previousStatus);
    } catch (err) {
      setActionError(errorMessage(t, err, "item.saveFailed"));
      return;
    }
    setActionError(null);
    setUndo(null);
  }

  // Builds the LWW field patch for a single item from the dialog's values, spreading only the
  // fields the user actually changed (T-88) so an edit can't stomp a collaborator's concurrent
  // edit to an untouched field. `includeCategory: false` omits the category — used by the
  // recase-all path, which stamps category across the whole group separately.
  function itemFieldsFromValues(values: ItemDialogSaveValues, includeCategory: boolean) {
    const changed = values.changedFields;
    return {
      ...(changed.has("name") ? fieldPatch(deviceId, "name", values.name) : {}),
      ...(includeCategory && changed.has("category")
        ? fieldPatch(deviceId, "category", values.category || null)
        : {}),
      ...(changed.has("stores") ? fieldPatch(deviceId, "stores", values.stores) : {}),
      ...(changed.has("quantity") ? fieldPatch(deviceId, "quantity", values.quantity || null) : {}),
      ...(changed.has("price")
        ? fieldPatch(
            deviceId,
            "price",
            values.priceAmount ? { amount: values.priceAmount, currency: values.priceCurrency || null } : null,
          )
        : {}),
      ...(changed.has("note") ? fieldPatch(deviceId, "note", values.note || null) : {}),
      ...(changed.has("due") ? fieldPatch(deviceId, "due", values.due || null) : {}),
      ...(changed.has("status") ? fieldPatch(deviceId, "status", values.status) : {}),
    };
  }

  async function handleSave(values: ItemDialogSaveValues) {
    const changed = values.changedFields;
    // Zero-change edit (T-88): nothing to stamp, so push nothing — the dialog still closes.
    if (changed.size === 0) return;

    // Category recase-all (T-108): editing an existing item's category to the SAME word with
    // different casing is the "fix the whole category's casing" gesture, not a one-item change —
    // recasing just this item would be a visual no-op under case-insensitive grouping. Rewrite
    // every item in the category to the new casing (folding in this item's other edits), and fix
    // the category_order entry so the canonical label sticks.
    const editing = dialogItem !== "new" ? dialogItem : null;
    const oldCategory = editing ? (itemFieldValue(editing, "category") ?? "").trim() : "";
    const newCategory = values.category.trim();
    if (
      editing &&
      changed.has("category") &&
      oldCategory &&
      newCategory &&
      categoryKey(oldCategory) === categoryKey(newCategory) &&
      oldCategory !== newCategory
    ) {
      const plan = planCategoryRename(
        listItems.map((i) => ({ id: i.id, category: itemFieldValue(i, "category") ?? "" })),
        categoryOrder,
        categoryKey(oldCategory),
        newCategory,
      );
      const otherFields = itemFieldsFromValues(values, false);
      await push({
        lists: plan.orderChanged
          ? [{ id: listId!, fields: fieldPatch(deviceId, "category_order", plan.nextCategoryOrder) }]
          : [],
        items: plan.itemIds.map((id) => ({
          id,
          list_id: listId!,
          created_at: nowMs(),
          fields: {
            ...fieldPatch(deviceId, "category", newCategory),
            ...(id === editing.id ? otherFields : {}),
          },
        })),
      });
      // Count after the label (T-123) so the string needs no plural agreement. The auto-dismiss
      // guard now compares the whole message rather than its tail: it only ever meant "don't clear
      // a toast that has since been replaced", and an exact match says that directly — and does
      // not quietly stop working when the message no longer ends with the category name.
      const categoryMessage = t("list.categoryFixed", {
        category: newCategory,
        count: plan.itemIds.length,
      });
      setCategoryToast(categoryMessage);
      // `prev`, not `t` — `t` is now the translate function in this scope, and shadowing it here
      // would read as a bug even though it isn't.
      setTimeout(() => setCategoryToast((prev) => (prev === categoryMessage ? null : prev)), 4000);
      return;
    }

    await push({
      items: [
        {
          id: values.itemId,
          list_id: listId!,
          created_at: nowMs(),
          fields: itemFieldsFromValues(values, true),
        },
      ],
    });
  }

  async function handleDelete(itemId: string) {
    await push({
      items: [{ id: itemId, list_id: listId!, fields: fieldPatch(deviceId, "deleted", true) }],
    });
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <Link to="/" className="muted" style={{ fontSize: "0.85rem" }}>
        {t("list.allListsLink")}
      </Link>
      <h1
        style={{
          fontSize: "1.3rem",
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
          margin: "0.25rem 0",
        }}
      >
        {/* Tapping the title also returns to the overview, mirroring the Android app's tappable
            top-bar title (T-109). The "← All lists" link above stays; this is a second, larger
            target. Link inherits the heading's type styles rather than looking like body-text. */}
        <Link
          to="/"
          title={t("list.backToAllLists")}
          style={{ color: "inherit", textDecoration: "none" }}
        >
          {listFieldValue(list, "name")}
        </Link>
      </h1>

      {/* Top controls row mirrors the Android app: show-checked on the left, all-items/settings
          icons on the right; add-item gets its own full-width row. Clear-checked lives in List
          properties (T-75), out of accidental-tap range. */}
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          margin: "0.75rem 0",
          flexWrap: "wrap",
          gap: "0.5rem",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "0.75rem" }}>
          <button
            type="button"
            className="btn-toggle"
            aria-pressed={showChecked}
            onClick={toggleShowChecked}
          >
            {showChecked ? "✓ " : ""}
            {t("list.showChecked")}
          </button>
        </div>
        <div style={{ display: "flex", gap: "0.4rem" }}>
          <Link to={`/list/${listId}/registry`} className="btn-icon" aria-label={t("list.allItems")} title={t("list.allItems")}>
            <ListIcon />
          </Link>
          <Link
            to={`/list/${listId}/properties`}
            className="btn-icon"
            aria-label={t("listProps.title")}
            title={t("listProps.title")}
          >
            ⚙
          </Link>
        </div>
      </div>

      {actionError && (
        <p className="error-text" role="alert">
          {actionError}
        </p>
      )}

      {groups.length === 0 && (
        <p className="muted">{t("list.empty")}</p>
      )}

      {groups.map((group) => (
        <section key={group.category} style={{ marginBottom: "1rem" }}>
          {/* Not uppercased (T-108): the category's casing is user-controlled now (fixable in the
              item dialog / list settings), so render it verbatim like the Android app does. */}
          {/* Coloured and centred, as the app draws them (T-183). The uncategorised group (T-339)
              has no heading when it is the only group, and otherwise a muted dash that a screen
              reader reads as "No category" rather than "em dash". */}
          {group.key !== "" ? (
            <h2 className="group-heading" dir="auto">
              {group.category}
            </h2>
          ) : groups.length > 1 ? (
            <h2 className="group-heading" style={{ color: "var(--color-text-muted)" }}>
              <span aria-hidden="true">{group.category}</span>
              <span className="visually-hidden">{t("list.noCategory")}</span>
            </h2>
          ) : null}
          <div className="rows">
            {group.items.map((item) => (
              <ItemRow
                key={item.id}
                item={item}
                defaultCurrency={defaultCurrency}
                showShoppingFields={showShopping}
                showDue={showDue}
                authorMember={
                  members.length >= 2 ? members.find((m) => m.account_id === item.last_touched_by) : undefined
                }
                onToggle={() => handleToggle(item)}
                onEdit={() => setDialogItem(item)}
              />
            ))}
            {/* Rows on their way out (T-128). Rendered in their old category so the animation
                happens where the row actually was, rather than jumping to the end of the list. */}
            {exitingItems
              .filter((item) => categoryKey(itemFieldValue(item, "category") ?? "") === group.key)
              .map((item) => (
                <ItemRow key={item.id} item={item} showShoppingFields={showShopping} showDue={showDue} exiting
                  onToggle={() => {}} onEdit={() => {}} />
              ))}
          </div>
        </section>
      ))}

      <div className="fab-spacer" aria-hidden="true" />

      {/* The toasts (T-339): one live region that is always there, so a screen reader announces
          what appears in it; stacked, so two never cover each other; and before the Add button,
          so Tab from the last row reaches Undo first. */}
      <div
        role="status"
        style={{
          position: "fixed",
          bottom: "calc(1rem + env(safe-area-inset-bottom, 0px))",
          left: "50%",
          transform: "translateX(-50%)",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: "0.5rem",
          width: "max-content",
          maxWidth: "calc(100vw - 2rem)",
          zIndex: 60,
        }}
      >
        {categoryToast && (
          <div className="card" style={{ padding: "0.6rem 1rem", boxShadow: "var(--shadow)" }}>
            {categoryToast}
          </div>
        )}
        {undo && (
          <div
            className="card"
            // Held while the pointer or the focus is on it (T-339): the five seconds must not run
            // out on someone who has just tabbed to Undo.
            onMouseEnter={holdUndo}
            onMouseLeave={releaseUndo}
            onFocus={holdUndo}
            onBlur={(e) => {
              if (!e.currentTarget.contains(e.relatedTarget as Node | null)) releaseUndo();
            }}
            style={{
              padding: "0.6rem 1rem",
              display: "flex",
              alignItems: "center",
              gap: "0.75rem",
              boxShadow: "var(--shadow)",
            }}
          >
            <span>{t("list.checkedOff")}</span>
            <button type="button" className="btn-secondary btn" onClick={handleUndo}>
              {t("action.undo")}
            </button>
          </div>
        )}
      </div>

      {/* Bottom right on every list kind (T-168), matching the app. */}
      <AddFab label={t("list.addItem")} onClick={() => setDialogItem("new")} raised={Boolean(undo || categoryToast)} />

      {dialogItem && (
        <ItemDialog
          listId={listId}
          registryItems={listItems}
          categorySuggestions={categorySuggestions}
          storeSuggestions={storeSuggestions}
          showShoppingFields={showShopping}
          showDue={showDue}
          editingItem={dialogItem === "new" ? undefined : dialogItem}
          defaultCurrency={defaultCurrency}
          onClose={() => setDialogItem(null)}
          onSave={handleSave}
          onDelete={dialogItem !== "new" ? handleDelete : undefined}
        />
      )}
    </main>
  );
}
