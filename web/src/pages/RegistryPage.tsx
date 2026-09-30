import { useEffect, useMemo, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue } from "../hooks/useSync";
import { distinctCanonicalCategories, distinctCanonicalStores } from "../lib/categories";
import { listKind, showsDueDate, showsShoppingFields } from "../lib/listKind";
import ItemDialog, { type ItemDialogSaveValues } from "../components/ItemDialog";
import { useDefaultCurrency } from "../hooks/useDefaultCurrency";
import type { ItemObject } from "../api/contract";
import { useT } from "../i18n";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { byName } from "../lib/nameOrder";
import { errorMessage } from "../i18n/apiErrors";

/** How long "Milk deleted · Undo" stays up, as the list's own undo toast does. */
const UNDO_MS = 5000;

export default function RegistryPage() {
  const t = useT();
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId, loading } = useSyncContext();
  const defaultCurrency = useDefaultCurrency();
  const [query, setQuery] = useState("");
  const [editingItem, setEditingItem] = useState<ItemObject | null>(null);
  // The row just deleted from here, offered back for a few seconds, as Android's snackbar does.
  const [undo, setUndo] = useState<{ itemId: string; name: string } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    if (!undo) return;
    const timer = setTimeout(() => setUndo(null), UNDO_MS);
    return () => clearTimeout(timer);
  }, [undo]);

  const list = listId ? lists.get(listId) : undefined;
  useDocumentTitle(t("list.allItems"), list && listFieldValue(list, "name"));

  const listItems = useMemo(
    () => (listId ? Array.from(items.values()).filter((i) => i.list_id === listId) : []),
    [items, listId],
  );

  // Same autocomplete data the list screen's dialog gets: store chips (T-139), and the category
  // chips this screen was silently rendering without (T-145).
  const categorySuggestions = useMemo(
    () =>
      distinctCanonicalCategories(
        listItems.map((i) => itemFieldValue(i, "category") ?? ""),
        (list ? listFieldValue(list, "category_order") : null) ?? [],
      ),
    [listItems, list],
  );
  const storeSuggestions = useMemo(
    () => distinctCanonicalStores(listItems.flatMap((i) => itemFieldValue(i, "stores") ?? [])),
    [listItems],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const matching = q
      ? listItems.filter((i) => (itemFieldValue(i, "name") ?? "").toLowerCase().includes(q))
      : listItems;
    return [...matching].sort(byName((item) => itemFieldValue(item, "name") ?? "", (item) => item.id));
  }, [listItems, query]);

  if (!listId) return <Navigate to="/" replace />;
  // Not a redirect (T-146): `list` is undefined on the FIRST render of every visit, because the
  // web client has no local mirror and lists only exist once the first sync response lands.
  // Navigating away here therefore bounced anyone who reloaded or deep-linked this URL out to the
  // overview. Rendering a placeholder instead lets the page survive the wait, exactly as ListPage
  // already does — and still says something sensible if the list really is gone.
  //
  // While that first sync is still in flight it says so, rather than flashing "not found" (T-342).
  if (!list) {
    return (
      <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
        <p className="muted">{loading ? t("common.loading") : t("list.notFound")}</p>
        {!loading && <Link to="/">{t("list.backToOverview")}</Link>}
      </main>
    );
  }

  async function handleSave(values: ItemDialogSaveValues) {
    const changed = values.changedFields;
    // Zero-change edit (T-88): nothing to stamp, so push nothing — the dialog still closes.
    if (changed.size === 0) return;
    await push({
      items: [
        {
          id: values.itemId,
          list_id: listId!,
          // Spread only the fields the user actually changed, so an edit stamps a fresh LWW clock
          // on those alone and can't stomp a collaborator's concurrent edit to an untouched field.
          fields: {
            ...(changed.has("name") ? fieldPatch(deviceId, "name", values.name) : {}),
            ...(changed.has("category") ? fieldPatch(deviceId, "category", values.category || null) : {}),
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
          },
        },
      ],
    });
  }

  async function handleDelete(itemId: string) {
    await push({
      items: [{ id: itemId, list_id: listId!, fields: fieldPatch(deviceId, "deleted", true) }],
    });
  }

  /**
   * The row's own delete (T-342): at once, with an undo, as on Android. The dialog's Delete still
   * confirms instead, since it has no undo of its own.
   */
  async function handleRowDelete(item: ItemObject) {
    try {
      await handleDelete(item.id);
    } catch (err) {
      setActionError(errorMessage(t, err, "item.saveFailed"));
      return;
    }
    setActionError(null);
    setUndo({ itemId: item.id, name: itemFieldValue(item, "name") ?? "" });
  }

  /** Undo is the tombstone cleared again, with a fresh clock, as Android's restore does. */
  async function handleUndo() {
    if (!undo) return;
    try {
      await push({
        items: [{ id: undo.itemId, list_id: listId!, fields: fieldPatch(deviceId, "deleted", false) }],
      });
    } catch (err) {
      setActionError(errorMessage(t, err, "item.saveFailed"));
      return;
    }
    setActionError(null);
    setUndo(null);
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      {/* From the catalogue, so the arrow points back in Arabic too (T-342). */}
      <Link to={`/list/${listId}`} className="muted" style={{ fontSize: "0.85rem" }}>
        {t("list.backToList", { name: listFieldValue(list, "name") ?? "" })}
      </Link>
      <h1 style={{ fontSize: "1.3rem" }}>{t("list.allItems")}</h1>
      <input
        type="search"
        aria-label={t("list.search")}
        placeholder={t("list.search")}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        style={{ width: "100%", marginBottom: "1rem" }}
      />

      {actionError && (
        <p className="error-text" role="alert">
          {actionError}
        </p>
      )}

      {filtered.length === 0 && <p className="muted">{t("list.registry.empty")}</p>}

      <div className="rows">
        {filtered.map((item) => {
          const name = itemFieldValue(item, "name") ?? "";
          return (
            <div key={item.id} className="row" style={{ display: "flex", alignItems: "center" }}>
              <button
                type="button"
                className="row"
                onClick={() => setEditingItem(item)}
                style={{
                  flex: 1,
                  minWidth: 0,
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  gap: "0.75rem",
                  padding: "0.6rem 0.75rem",
                  textAlign: "start",
                }}
              >
                <span dir="auto" style={{ minWidth: 0, overflowWrap: "anywhere" }}>
                  {name}
                </span>
                {/* The translated status (T-186); it showed the raw wire value, in English, whatever
                    the language. */}
                <span className="muted" style={{ fontSize: "0.8rem", whiteSpace: "nowrap" }}>
                  {t(`item.status.${itemFieldValue(item, "status") ?? "backlog"}` as "item.status.backlog")}
                </span>
              </button>
              <button
                type="button"
                className="btn-icon"
                aria-label={t("list.registry.deleteItem", { name })}
                title={t("list.registry.deleteItem", { name })}
                onClick={() => handleRowDelete(item)}
                style={{ minWidth: "2.75rem", minHeight: "2.75rem", display: "inline-flex", alignItems: "center", justifyContent: "center" }}
              >
                {/* Material's delete glyph, as Android's row shows. */}
                <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
                </svg>
              </button>
            </div>
          );
        })}
      </div>

      {undo && (
        <div
          className="card"
          role="status"
          style={{
            position: "fixed",
            bottom: "calc(1rem + env(safe-area-inset-bottom, 0px))",
            left: "50%",
            transform: "translateX(-50%)",
            padding: "0.6rem 1rem",
            display: "flex",
            alignItems: "center",
            gap: "0.75rem",
            maxWidth: "calc(100% - 2rem)",
            boxShadow: "var(--shadow)",
          }}
        >
          <span dir="auto" style={{ minWidth: 0, overflowWrap: "anywhere" }}>
            {t("list.registry.itemDeleted", { name: undo.name })}
          </span>
          <button type="button" className="btn-secondary btn" onClick={handleUndo}>
            {t("action.undo")}
          </button>
        </div>
      )}

      {editingItem && (
        <ItemDialog
          listId={listId}
          registryItems={listItems}
          categorySuggestions={categorySuggestions}
          storeSuggestions={storeSuggestions}
          showShoppingFields={showsShoppingFields(listKind(list))}
          showDue={showsDueDate(listKind(list))}
          editingItem={editingItem}
          defaultCurrency={defaultCurrency}
          onClose={() => setEditingItem(null)}
          onSave={handleSave}
          onDelete={handleDelete}
        />
      )}
    </main>
  );
}
