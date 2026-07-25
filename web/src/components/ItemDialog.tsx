import { useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import type { ItemObject, ItemStatus, Price } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";
import { ApiError } from "../api/client";
import { parseCurrency, parsePriceAmount } from "../lib/priceParse";

/** The item fields the dialog can push, as LWW keys (matches the `fieldPatch` keys the pages spread). */
export type ItemChangedField = "name" | "category" | "stores" | "quantity" | "price" | "note" | "status";

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
  /**
   * The fields whose normalized value actually differs from the snapshot the form was seeded with
   * (T-88) — the pages spread only these fieldPatch entries, so an edit stamps a fresh LWW clock
   * only on what the user really changed and never stomps a collaborator's concurrent edit to an
   * untouched field. Empty means a zero-change edit: the pages push nothing at all.
   */
  changedFields: Set<ItemChangedField>;
}

/** Optional-string parity for diffing: "" and null are the same value (an absent optional field). */
function optEqual(a: string | null, b: string | null): boolean {
  return (a || null) === (b || null);
}

function storesEqual(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i]);
}

function priceEqual(a: Price | null, b: Price | null): boolean {
  if (a === null || b === null) return a === b;
  return a.amount === b.amount && (a.currency || null) === (b.currency || null);
}

interface NormalizedValues {
  name: string;
  category: string | null;
  stores: string[];
  quantity: string | null;
  price: Price | null;
  note: string | null;
  status: ItemStatus;
}

function snapshotOf(item: ItemObject): NormalizedValues {
  const price = itemFieldValue(item, "price") ?? null;
  return {
    name: (itemFieldValue(item, "name") ?? "").trim(),
    category: (itemFieldValue(item, "category") ?? "").trim() || null,
    stores: itemFieldValue(item, "stores") ?? [],
    quantity: (itemFieldValue(item, "quantity") ?? "").trim() || null,
    price,
    note: (itemFieldValue(item, "note") ?? "").trim() || null,
    status: itemFieldValue(item, "status") ?? "todo",
  };
}

/**
 * Diff the (already-normalized) form values against the snapshot the form was seeded from.
 * `snapshot === null` is a brand-new row: every non-empty field is a first write, and status is
 * always stamped so the fresh row lands with a status (T-88 grooming: "push all non-empty fields").
 */
function computeChangedFields(current: NormalizedValues, snapshot: ItemObject | null): Set<ItemChangedField> {
  const changed = new Set<ItemChangedField>();
  if (!snapshot) {
    if (current.name) changed.add("name");
    if (current.category) changed.add("category");
    if (current.stores.length) changed.add("stores");
    if (current.quantity) changed.add("quantity");
    if (current.price) changed.add("price");
    if (current.note) changed.add("note");
    changed.add("status");
    return changed;
  }
  const base = snapshotOf(snapshot);
  if (current.name !== base.name) changed.add("name");
  if (!optEqual(current.category, base.category)) changed.add("category");
  if (!storesEqual(current.stores, base.stores)) changed.add("stores");
  if (!optEqual(current.quantity, base.quantity)) changed.add("quantity");
  if (!priceEqual(current.price, base.price)) changed.add("price");
  if (!optEqual(current.note, base.note)) changed.add("note");
  if (current.status !== base.status) changed.add("status");
  return changed;
}

interface ItemDialogProps {
  listId: string;
  /** All live items in this list (any status) — used for name suggestions. */
  registryItems: ItemObject[];
  /** Existing categories in this list (canonical casing), for the category autocomplete (T-108). */
  categorySuggestions?: string[];
  /**
   * Whether to show the shopping-only fields — stores, quantity, price (T-110). False on a
   * checklist. The item schema is unchanged either way: hidden fields keep whatever they already
   * held (e.g. after converting a shopping list), they're just not rendered or edited here.
   */
  showShoppingFields?: boolean;
  /** Present for edit mode; absent for add mode. */
  editingItem?: ItemObject;
  defaultCurrency: string;
  onClose: () => void;
  onSave: (values: ItemDialogSaveValues) => Promise<void>;
  onDelete?: (itemId: string) => Promise<void>;
}

function emptyValues(defaultCurrency: string): Omit<ItemDialogSaveValues, "itemId" | "status" | "changedFields"> {
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

function valuesFromItem(item: ItemObject): Omit<ItemDialogSaveValues, "itemId" | "status" | "changedFields"> {
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
  categorySuggestions = [],
  showShoppingFields = true,
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
  // Store name entry (T-99): a single trimmed string is appended to values.stores as a chip on
  // Enter/Add, never comma-split — a store name containing a comma (e.g. "Costco, Inc") stays one
  // value. values.stores itself is the string[] compared element-wise by storesEqual (T-88), so
  // seeding chips from the snapshot and leaving them untouched keeps that diff invariant intact.
  const [storeInput, setStoreInput] = useState("");
  const [priceError, setPriceError] = useState<string | null>(null);
  const [currencyError, setCurrencyError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);

  const suggestions = useMemo(() => {
    if (isEdit || !values.name.trim()) return [];
    const query = values.name.trim().toLowerCase();
    return registryItems
      .filter((it) => (itemFieldValue(it, "name") ?? "").toLowerCase().includes(query))
      .slice(0, 8);
  }, [values.name, registryItems, isEdit]);

  // Existing categories to offer as clickable chips (T-108), mirroring Android's AssistChip row:
  // those containing the typed text (case-insensitive), minus an exact match (nothing to suggest
  // once you've typed it). Clicking a chip adopts its canonical casing.
  const categoryChips = useMemo(() => {
    const typed = values.category.trim().toLowerCase();
    return categorySuggestions
      .filter((c) => c.toLowerCase().includes(typed) && c.toLowerCase() !== typed)
      .slice(0, 8);
  }, [categorySuggestions, values.category]);

  function pickSuggestion(item: ItemObject) {
    setMatchedExisting(item);
    const next = valuesFromItem(item);
    setValues(next);
    setStoreInput("");
    setStatus("todo");
  }

  /** Commit the trimmed store-input text as a new chip; ignores empty/duplicate entries (T-99). */
  function addStore() {
    const trimmed = storeInput.trim();
    setStoreInput("");
    if (!trimmed) return;
    setValues((v) => (v.stores.includes(trimmed) ? v : { ...v, stores: [...v.stores, trimmed] }));
  }

  function removeStore(store: string) {
    setValues((v) => ({ ...v, stores: v.stores.filter((s) => s !== store) }));
  }

  /**
   * Enter commits a chip instead of submitting the whole form — a comma typed here is a literal
   * character of the store name, never a separator (T-99: the old comma-joined text input could
   * not represent a store name containing a comma).
   */
  function handleStoreInputKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter") {
      e.preventDefault();
      addStore();
    }
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

    setPriceError(null);
    setCurrencyError(null);
    setSaveError(null);

    // Validate/normalize price BEFORE pushing, so a bad value (e.g. "1,50", "2€", "1.999") is
    // caught with an inline error rather than pushed and 422'd by the server — which would abort
    // the whole /sync transaction and wedge the push queue (T-32). Mirrors Android's
    // ItemFormViewModel.performSave (T-91). Currency is validated even without an amount (parity
    // with Android) but is only meaningful — and only kept — alongside one.
    const amountParse = parsePriceAmount(values.priceAmount);
    if (!amountParse.valid) {
      setPriceError(amountParse.message);
      return;
    }
    const currencyParse = parseCurrency(values.priceCurrency);
    if (!currencyParse.valid) {
      setCurrencyError(currencyParse.message);
      return;
    }
    const normalizedAmount = amountParse.value;
    const normalizedCurrency = normalizedAmount ? currencyParse.value : null;

    setSaving(true);
    try {
      const itemId = matchedExisting?.id ?? editingItem?.id ?? crypto.randomUUID();
      const category = values.category.trim();
      // Fold any text still sitting in the add-store box (typed but not yet committed via
      // Enter/Add) into the pushed array, so clicking Save doesn't silently drop it (T-99 review).
      // Same dedup rule as addStore. When the box is empty (the normal case) stores stays
      // referentially identical to values.stores, so an untouched list still diffs equal and stays
      // out of changedFields (T-88 no re-stomp).
      const pendingStore = storeInput.trim();
      const stores =
        pendingStore && !values.stores.includes(pendingStore)
          ? [...values.stores, pendingStore]
          : values.stores;
      const quantity = values.quantity.trim();
      const note = values.note.trim();
      // Snapshot the form was seeded from: the item in edit mode (kept even across a rename, which
      // clears matchedExisting), the picked suggestion after an adopt, or null for a brand-new row.
      const snapshot = editingItem ?? matchedExisting;
      const changedFields = computeChangedFields(
        {
          name,
          category: category || null,
          stores,
          quantity: quantity || null,
          price: normalizedAmount ? { amount: normalizedAmount, currency: normalizedCurrency } : null,
          note: note || null,
          status,
        },
        snapshot,
      );
      await onSave({
        itemId,
        name,
        category,
        stores,
        quantity,
        priceAmount: normalizedAmount ?? "",
        priceCurrency: normalizedCurrency ?? "",
        note,
        status,
        changedFields,
      });
      // A pending store name was folded into the pushed payload above, so clear the add-store box
      // now that it's committed (consistent with adding a chip).
      setStoreInput("");
      if (closeAfter) {
        onClose();
      } else {
        setMatchedExisting(null);
        setValues(emptyValues(defaultCurrency));
        setStatus("todo");
        nameInputRef.current?.focus();
      }
    } catch (err) {
      // Surface the server's rejection inline and keep the dialog open (T-91) — previously this
      // escaped as an unhandled rejection, leaving the user with no idea what went wrong or that
      // nothing was saved.
      setSaveError(err instanceof ApiError ? err.message : "Failed to save. Please try again.");
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
        {saveError && (
          <p className="error-text" role="alert">
            {saveError}
          </p>
        )}

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
          />
          {categoryChips.length > 0 && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: "0.4rem", marginTop: "0.4rem" }}>
              {categoryChips.map((category) => (
                <button
                  key={category}
                  type="button"
                  className="chip-button"
                  onClick={() => setValues((v) => ({ ...v, category }))}
                >
                  {category}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Shopping-only fields (T-110): a checklist shows just name / category / note / status.
            Existing values are preserved, merely not rendered, so converting a list is reversible. */}
        {showShoppingFields && (
        <>
        <div className="form-field">
          <label htmlFor="item-stores">Stores</label>
          <div style={{ display: "flex", gap: "0.5rem" }}>
            <input
              id="item-stores"
              value={storeInput}
              onChange={(e) => setStoreInput(e.target.value)}
              onKeyDown={handleStoreInputKeyDown}
              placeholder="Add a store"
            />
            <button type="button" className="btn btn-secondary" onClick={addStore}>
              Add
            </button>
          </div>
          {values.stores.length > 0 && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: "0.4rem", marginTop: "0.4rem" }}>
              {values.stores.map((store) => (
                <span key={store} className="chip">
                  {store}
                  <button
                    type="button"
                    className="chip-remove"
                    aria-label={`Remove ${store}`}
                    onClick={() => removeStore(store)}
                  >
                    ×
                  </button>
                </span>
              ))}
            </div>
          )}
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
              onChange={(e) => {
                setValues((v) => ({ ...v, priceAmount: e.target.value }));
                setPriceError(null);
              }}
            />
            {priceError && <p className="error-text">{priceError}</p>}
          </div>
          <div className="form-field" style={{ flex: "0 0 6rem", minWidth: 0 }}>
            <label htmlFor="item-currency">Currency</label>
            <input
              id="item-currency"
              placeholder={defaultCurrency}
              maxLength={3}
              value={values.priceCurrency}
              onChange={(e) => {
                setValues((v) => ({ ...v, priceCurrency: e.target.value }));
                setCurrencyError(null);
              }}
            />
            {currencyError && <p className="error-text">{currencyError}</p>}
          </div>
        </div>
        </>
        )}

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
