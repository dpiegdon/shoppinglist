import { useMemo, useRef, useState, type FormEvent } from "react";
import type { ItemObject, ItemStatus } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";

export interface ItemDialogSaveValues {
  itemId: string;
  name: string;
  category: string;
  stores: string[];
  quantity: string;
  priceAmount: string;
  priceCurrency: string;
  note: string;
  status: ItemStatus;
}

interface ItemDialogProps {
  listId: string;
  /** All live items in this list (any status) — used for name suggestions. */
  registryItems: ItemObject[];
  /** Present for edit mode; absent for add mode. */
  editingItem?: ItemObject;
  defaultCurrency: string;
  onClose: () => void;
  onSave: (values: ItemDialogSaveValues) => Promise<void>;
  onDelete?: (itemId: string) => Promise<void>;
}

function emptyValues(defaultCurrency: string): Omit<ItemDialogSaveValues, "itemId" | "status"> {
  return {
    name: "",
    category: "",
    stores: [],
    quantity: "",
    priceAmount: "",
    priceCurrency: defaultCurrency,
    note: "",
  };
}

function valuesFromItem(item: ItemObject): Omit<ItemDialogSaveValues, "itemId" | "status"> {
  const price = itemFieldValue(item, "price");
  return {
    name: itemFieldValue(item, "name") ?? "",
    category: itemFieldValue(item, "category") ?? "",
    stores: itemFieldValue(item, "stores") ?? [],
    quantity: itemFieldValue(item, "quantity") ?? "",
    priceAmount: price?.amount ?? "",
    priceCurrency: price?.currency ?? "",
    note: itemFieldValue(item, "note") ?? "",
  };
}

export default function ItemDialog({
  registryItems,
  editingItem,
  defaultCurrency,
  onClose,
  onSave,
  onDelete,
}: ItemDialogProps) {
  const isEdit = Boolean(editingItem);
  const [matchedExisting, setMatchedExisting] = useState<ItemObject | null>(editingItem ?? null);
  const [values, setValues] = useState(() =>
    editingItem ? valuesFromItem(editingItem) : emptyValues(defaultCurrency),
  );
  const [status, setStatus] = useState<ItemStatus>(
    editingItem ? itemFieldValue(editingItem, "status") ?? "todo" : "todo",
  );
  const [saving, setSaving] = useState(false);
  const [storesInput, setStoresInput] = useState(values.stores.join(", "));
  const nameInputRef = useRef<HTMLInputElement>(null);

  const suggestions = useMemo(() => {
    if (isEdit || !values.name.trim()) return [];
    const query = values.name.trim().toLowerCase();
    return registryItems
      .filter((it) => (itemFieldValue(it, "name") ?? "").toLowerCase().includes(query))
      .slice(0, 8);
  }, [values.name, registryItems, isEdit]);

  function pickSuggestion(item: ItemObject) {
    setMatchedExisting(item);
    const next = valuesFromItem(item);
    setValues(next);
    setStoresInput(next.stores.join(", "));
    setStatus("todo");
  }

  function handleNameChange(name: string) {
    setValues((v) => ({ ...v, name }));
    if (matchedExisting && itemFieldValue(matchedExisting, "name") !== name) {
      setMatchedExisting(null);
    }
  }

  /**
   * closeAfter=false is "Add another" (T-53, parity with Android's T-41): saves, then resets to a
   * blank add form and refocuses Name instead of closing, for adding several items in a burst
   * without reopening the dialog each time. Add mode only.
   */
  async function performSave(closeAfter: boolean) {
    const name = values.name.trim();
    if (!name) return;
    setSaving(true);
    try {
      const itemId = matchedExisting?.id ?? editingItem?.id ?? crypto.randomUUID();
      await onSave({
        itemId,
        name,
        category: values.category.trim(),
        stores: storesInput
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean),
        quantity: values.quantity.trim(),
        priceAmount: values.priceAmount.trim(),
        priceCurrency: values.priceCurrency.trim(),
        note: values.note.trim(),
        status,
      });
      if (closeAfter) {
        onClose();
      } else {
        setMatchedExisting(null);
        setValues(emptyValues(defaultCurrency));
        setStoresInput("");
        setStatus("todo");
        nameInputRef.current?.focus();
      }
    } finally {
      setSaving(false);
    }
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    performSave(true);
  }

  function handleSaveAndAddAnother() {
    performSave(false);
  }

  return (
    <div className="dialog-overlay" onClick={onClose}>
      <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
        <h2 style={{ marginTop: 0, fontSize: "1.1rem" }}>{isEdit ? "Edit item" : "Add item"}</h2>

        <div className="form-field" style={{ position: "relative" }}>
          <label htmlFor="item-name">Name</label>
          <input
            id="item-name"
            ref={nameInputRef}
            autoFocus
            required
            autoComplete="off"
            value={values.name}
            onChange={(e) => handleNameChange(e.target.value)}
          />
          {suggestions.length > 0 && (
            <ul
              className="card"
              style={{
                listStyle: "none",
                margin: 0,
                padding: "0.25rem",
                position: "absolute",
                top: "100%",
                left: 0,
                right: 0,
                zIndex: 10,
                boxShadow: "var(--shadow)",
              }}
            >
              {suggestions.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    onClick={() => pickSuggestion(item)}
                    style={{
                      display: "block",
                      width: "100%",
                      textAlign: "left",
                      background: "transparent",
                      border: "none",
                      padding: "0.4rem 0.5rem",
                      color: "var(--color-text)",
                    }}
                  >
                    {itemFieldValue(item, "name")}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="form-field">
          <label htmlFor="item-category">Category</label>
          <input
            id="item-category"
            value={values.category}
            onChange={(e) => setValues((v) => ({ ...v, category: e.target.value }))}
            list="category-suggestions"
          />
        </div>

        <div className="form-field">
          <label htmlFor="item-stores">Stores (comma-separated)</label>
          <input id="item-stores" value={storesInput} onChange={(e) => setStoresInput(e.target.value)} />
        </div>

        <div className="form-field">
          <label htmlFor="item-quantity">Quantity</label>
          <input
            id="item-quantity"
            value={values.quantity}
            onChange={(e) => setValues((v) => ({ ...v, quantity: e.target.value }))}
          />
        </div>

        <div style={{ display: "flex", gap: "0.5rem" }}>
          <div className="form-field" style={{ flex: 1, minWidth: 0 }}>
            <label htmlFor="item-price">Price</label>
            <input
              id="item-price"
              inputMode="decimal"
              placeholder="0.00"
              value={values.priceAmount}
              onChange={(e) => setValues((v) => ({ ...v, priceAmount: e.target.value }))}
            />
          </div>
          <div className="form-field" style={{ flex: "0 0 6rem", minWidth: 0 }}>
            <label htmlFor="item-currency">Currency</label>
            <input
              id="item-currency"
              placeholder={defaultCurrency}
              maxLength={3}
              value={values.priceCurrency}
              onChange={(e) => setValues((v) => ({ ...v, priceCurrency: e.target.value.toUpperCase() }))}
            />
          </div>
        </div>

        <div className="form-field">
          <label htmlFor="item-note">Note</label>
          <textarea
            id="item-note"
            rows={2}
            value={values.note}
            onChange={(e) => setValues((v) => ({ ...v, note: e.target.value }))}
          />
        </div>

        {isEdit && (
          <div className="form-field">
            <label htmlFor="item-status">Status</label>
            <select id="item-status" value={status} onChange={(e) => setStatus(e.target.value as ItemStatus)}>
              <option value="todo">Todo</option>
              <option value="checked">Checked</option>
              <option value="backlog">Backlog (not on list)</option>
            </select>
          </div>
        )}

        <div style={{ display: "flex", gap: "0.5rem", justifyContent: "space-between", marginTop: "0.5rem" }}>
          <div>
            {isEdit && onDelete && editingItem && (
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => onDelete(editingItem.id).then(onClose)}
              >
                Delete
              </button>
            )}
          </div>
          <div style={{ display: "flex", gap: "0.5rem" }}>
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancel
            </button>
            {!isEdit && (
              <button type="button" className="btn btn-secondary" disabled={saving} onClick={handleSaveAndAddAnother}>
                Add another
              </button>
            )}
            <button type="submit" className="btn" disabled={saving}>
              Save
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
