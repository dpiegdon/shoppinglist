import { useMemo, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue } from "../hooks/useSync";
import ItemDialog, { type ItemDialogSaveValues } from "../components/ItemDialog";
import { useDefaultCurrency } from "../hooks/useDefaultCurrency";
import type { ItemObject } from "../api/contract";

export default function RegistryPage() {
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId } = useSyncContext();
  const defaultCurrency = useDefaultCurrency();
  const [query, setQuery] = useState("");
  const [editingItem, setEditingItem] = useState<ItemObject | null>(null);

  const list = listId ? lists.get(listId) : undefined;

  const listItems = useMemo(
    () => (listId ? Array.from(items.values()).filter((i) => i.list_id === listId) : []),
    [items, listId],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const matching = q
      ? listItems.filter((i) => (itemFieldValue(i, "name") ?? "").toLowerCase().includes(q))
      : listItems;
    return [...matching].sort((a, b) =>
      (itemFieldValue(a, "name") ?? "").localeCompare(itemFieldValue(b, "name") ?? ""),
    );
  }, [listItems, query]);

  if (!listId) return <Navigate to="/" replace />;
  if (!list) return <Navigate to="/" replace />;

  async function handleSave(values: ItemDialogSaveValues) {
    await push({
      items: [
        {
          id: values.itemId,
          list_id: listId!,
          fields: {
            ...fieldPatch(deviceId, "name", values.name),
            ...fieldPatch(deviceId, "category", values.category || null),
            ...fieldPatch(deviceId, "stores", values.stores),
            ...fieldPatch(deviceId, "quantity", values.quantity || null),
            ...fieldPatch(
              deviceId,
              "price",
              values.priceAmount ? { amount: values.priceAmount, currency: values.priceCurrency || null } : null,
            ),
            ...fieldPatch(deviceId, "note", values.note || null),
            ...fieldPatch(deviceId, "status", values.status),
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

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <Link to={`/list/${listId}`} className="muted" style={{ fontSize: "0.85rem" }}>
        ← {listFieldValue(list, "name")}
      </Link>
      <h1 style={{ fontSize: "1.3rem" }}>All items</h1>
      <input
        type="search"
        placeholder="Search items…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        style={{ width: "100%", marginBottom: "1rem" }}
      />

      {filtered.length === 0 && <p className="muted">No items found.</p>}

      <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
        {filtered.map((item) => (
          <button
            key={item.id}
            type="button"
            className="card"
            onClick={() => setEditingItem(item)}
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              padding: "0.6rem 0.75rem",
              textAlign: "left",
              border: "1px solid var(--color-border)",
              color: "var(--color-text)",
            }}
          >
            <span>{itemFieldValue(item, "name")}</span>
            <span className="muted" style={{ fontSize: "0.8rem", textTransform: "capitalize" }}>
              {itemFieldValue(item, "status")}
            </span>
          </button>
        ))}
      </div>

      {editingItem && (
        <ItemDialog
          listId={listId}
          registryItems={listItems}
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
