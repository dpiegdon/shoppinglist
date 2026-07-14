import { useEffect, useMemo, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import * as api from "../api/client";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue, nowMs } from "../hooks/useSync";
import { checkedItems, groupVisibleItems } from "../lib/grouping";
import ItemRow from "../components/ItemRow";
import ItemDialog, { type ItemDialogSaveValues } from "../components/ItemDialog";
import { useDefaultCurrency } from "../hooks/useDefaultCurrency";
import type { ItemObject, ItemStatus, Member } from "../api/contract";

export default function ListPage() {
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId } = useSyncContext();
  const defaultCurrency = useDefaultCurrency();
  const [showChecked, setShowChecked] = useState(false);
  const [dialogItem, setDialogItem] = useState<ItemObject | "new" | null>(null);
  const [undo, setUndo] = useState<{ itemId: string; previousStatus: ItemStatus } | null>(null);
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

  const list = listId ? lists.get(listId) : undefined;

  const listItems = useMemo(
    () => (listId ? Array.from(items.values()).filter((i) => i.list_id === listId) : []),
    [items, listId],
  );

  if (!listId) return <Navigate to="/" replace />;
  if (!list) {
    return (
      <main style={{ padding: "1rem" }}>
        <p className="muted">List not found (or you no longer have access).</p>
        <Link to="/">Back to overview</Link>
      </main>
    );
  }

  const categoryOrder = listFieldValue(list, "category_order") ?? [];
  const groups = groupVisibleItems(listItems, categoryOrder, showChecked);
  const allChecked = checkedItems(listItems);

  async function setItemStatus(itemId: string, status: ItemStatus) {
    await push({
      items: [{ id: itemId, list_id: listId!, fields: fieldPatch(deviceId, "status", status) }],
    });
  }

  async function handleToggle(item: ItemObject) {
    const current = itemFieldValue(item, "status") ?? "todo";
    const next: ItemStatus = current === "checked" ? "todo" : "checked";
    await setItemStatus(item.id, next);
    if (next === "checked") {
      setUndo({ itemId: item.id, previousStatus: current });
      setTimeout(() => setUndo((u) => (u?.itemId === item.id ? null : u)), 5000);
    }
  }

  async function handleUndo() {
    if (!undo) return;
    await setItemStatus(undo.itemId, undo.previousStatus);
    setUndo(null);
  }

  /** Moves every checked item back to the backlog (Spec: "clearing done items -> backlog"). */
  async function handleClearChecked() {
    if (allChecked.length === 0) return;
    await push({
      items: allChecked.map((item) => ({
        id: item.id,
        list_id: listId!,
        fields: fieldPatch(deviceId, "status", "backlog" as ItemStatus),
      })),
    });
  }

  async function handleSave(values: ItemDialogSaveValues) {
    await push({
      items: [
        {
          id: values.itemId,
          list_id: listId!,
          created_at: nowMs(),
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
      <Link to="/" className="muted" style={{ fontSize: "0.85rem" }}>
        ← All lists
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
        {listFieldValue(list, "name")}
      </h1>

      {/* Top controls row mirrors the Android app: show-checked (+ clear-checked) on the
          left, all-items/settings icons on the right; add-item gets its own full-width row. */}
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
            onClick={() => setShowChecked((v) => !v)}
          >
            {showChecked ? "✓ " : ""}Show checked
          </button>
          {allChecked.length > 0 && (
            <button type="button" className="btn btn-danger btn-sm" onClick={handleClearChecked}>
              Clear checked ({allChecked.length})
            </button>
          )}
        </div>
        <div style={{ display: "flex", gap: "0.4rem" }}>
          <Link to={`/list/${listId}/registry`} className="btn-icon" aria-label="All items" title="All items">
            ☰
          </Link>
          <Link
            to={`/list/${listId}/properties`}
            className="btn-icon"
            aria-label="List properties"
            title="List properties"
          >
            ⚙
          </Link>
        </div>
      </div>

      <button
        type="button"
        className="btn"
        style={{ width: "100%", marginBottom: "0.75rem" }}
        onClick={() => setDialogItem("new")}
      >
        + Add item
      </button>

      {groups.length === 0 && (
        <p className="muted">Nothing on this list yet. Add an item to get started.</p>
      )}

      {groups.map((group) => (
        <section key={group.category} style={{ marginBottom: "1rem" }}>
          <h2 className="muted" style={{ fontSize: "0.85rem", textTransform: "uppercase", margin: "0 0 0.4rem" }}>
            {group.category}
          </h2>
          <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
            {group.items.map((item) => (
              <ItemRow
                key={item.id}
                item={item}
                authorMember={
                  members.length >= 2 ? members.find((m) => m.account_id === item.last_touched_by) : undefined
                }
                onToggle={() => handleToggle(item)}
                onEdit={() => setDialogItem(item)}
              />
            ))}
          </div>
        </section>
      ))}

      {undo && (
        <div
          className="card"
          style={{
            position: "fixed",
            bottom: "1rem",
            left: "50%",
            transform: "translateX(-50%)",
            padding: "0.6rem 1rem",
            display: "flex",
            alignItems: "center",
            gap: "0.75rem",
            boxShadow: "var(--shadow)",
          }}
        >
          <span>Item checked off.</span>
          <button type="button" className="btn-secondary btn" onClick={handleUndo}>
            Undo
          </button>
        </div>
      )}

      {dialogItem && (
        <ItemDialog
          listId={listId}
          registryItems={listItems}
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
