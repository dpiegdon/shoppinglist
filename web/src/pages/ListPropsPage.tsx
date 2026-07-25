import { useEffect, useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import * as api from "../api/client";
import { ApiError } from "../api/client";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue, nowMs } from "../hooks/useSync";
import { checkedItems } from "../lib/grouping";
import { canonicalCategoryNames, categoryKey, planCategoryRename } from "../lib/categories";
import { listKind, listKindLabel } from "../lib/listKind";
import type { ItemStatus, ListKind, MembersResponse } from "../api/contract";
import { LAST_LIST_STORAGE_KEY } from "./OverviewPage";

export default function ListPropsPage() {
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId, refresh } = useSyncContext();
  const navigate = useNavigate();
  const list = listId ? lists.get(listId) : undefined;

  const [name, setName] = useState(list ? listFieldValue(list, "name") ?? "" : "");
  const [categoryOrder, setCategoryOrder] = useState<string[]>(
    list ? listFieldValue(list, "category_order") ?? [] : [],
  );
  const [notes, setNotes] = useState(list ? listFieldValue(list, "notes") ?? "" : "");
  const [newCategory, setNewCategory] = useState("");
  // Inline category rename (T-108): the key being edited + its draft text.
  const [editingCategoryKey, setEditingCategoryKey] = useState<string | null>(null);
  const [categoryDraft, setCategoryDraft] = useState("");
  const [members, setMembers] = useState<MembersResponse | null>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [membersError, setMembersError] = useState<string | null>(null);
  const [savingName, setSavingName] = useState(false);
  const [savingNotes, setSavingNotes] = useState(false);
  // The most recently minted invite link (T-83) — mint returns it once; the members roster doesn't
  // carry tokens for older pending invites, so this only reflects an invite created this session.
  const [inviteLink, setInviteLink] = useState<{ url: string; email: string } | null>(null);
  const [linkCopied, setLinkCopied] = useState(false);

  useEffect(() => {
    if (!listId) return;
    api
      .getMembers(listId)
      .then(setMembers)
      .catch((err) => setMembersError(err instanceof ApiError ? err.message : "Failed to load members."));
  }, [listId]);

  if (!listId) return <Navigate to="/" replace />;
  if (!list) return <Navigate to="/" replace />;
  const id: string = listId;

  const liveItems = Array.from(items.values()).filter(
    (i) => i.list_id === id && !itemFieldValue(i, "deleted"),
  );
  const allChecked = checkedItems(liveItems);

  // The full set of categories in this list (T-108) — everything on an item PLUS anything the
  // user has explicitly ordered — so casing can be fixed here for any of them, not just ordered
  // ones. Canonical casing, ordered ones first (in order), the rest alphabetically.
  const canonicalNames = canonicalCategoryNames(
    liveItems.map((i) => itemFieldValue(i, "category") ?? ""),
    categoryOrder,
  );
  const orderedKeys = categoryOrder
    .map(categoryKey)
    .filter((k, i, arr) => k !== "" && arr.indexOf(k) === i);
  const categoryKeys = [
    ...orderedKeys.filter((k) => canonicalNames.has(k)),
    ...Array.from(canonicalNames.keys())
      .filter((k) => !orderedKeys.includes(k))
      .sort((a, b) => canonicalNames.get(a)!.localeCompare(canonicalNames.get(b)!)),
  ];
  const orderIndexOf = (key: string) => categoryOrder.findIndex((e) => categoryKey(e) === key);

  async function saveName(e: FormEvent) {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) return;
    setSavingName(true);
    try {
      await push({ lists: [{ id, fields: fieldPatch(deviceId, "name", trimmed) }] });
    } finally {
      setSavingName(false);
    }
  }

  async function saveNotes(e: FormEvent) {
    e.preventDefault();
    setSavingNotes(true);
    try {
      await push({ lists: [{ id, fields: fieldPatch(deviceId, "notes", notes.trim() || null) }] });
    } finally {
      setSavingNotes(false);
    }
  }

  /**
   * Convert between shopping list and checklist (T-110). Non-destructive: the item schema is the
   * same for both, so this only changes which fields the clients render — stores/price/quantity
   * survive a conversion and reappear if you switch back.
   */
  async function setKind(kind: ListKind) {
    await push({ lists: [{ id, fields: fieldPatch(deviceId, "kind", kind) }] });
  }

  async function saveCategoryOrder(next: string[]) {
    setCategoryOrder(next);
    await push({ lists: [{ id, fields: fieldPatch(deviceId, "category_order", next) }] });
  }

  function moveCategory(index: number, delta: number) {
    const next = [...categoryOrder];
    const target = index + delta;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    saveCategoryOrder(next);
  }

  function removeCategory(index: number) {
    saveCategoryOrder(categoryOrder.filter((_, i) => i !== index));
  }

  function addCategory(e: FormEvent) {
    e.preventDefault();
    const trimmed = newCategory.trim();
    // Case-insensitive dedup (T-108): don't add "Group" when "group" is already ordered.
    if (!trimmed || categoryOrder.some((c) => categoryKey(c) === categoryKey(trimmed))) return;
    saveCategoryOrder([...categoryOrder, trimmed]);
    setNewCategory("");
  }

  /**
   * The central "fix a category's casing / rename it" action (T-108): rewrites every item in the
   * `fromKey` category to `toRaw` and updates the matching category_order entry. Renaming onto a
   * different existing category merges them (confirmed first).
   */
  async function renameCategory(fromKey: string, toRaw: string) {
    const to = toRaw.trim();
    setEditingCategoryKey(null);
    if (!to) return;
    if (categoryKey(to) === fromKey && to === canonicalNames.get(fromKey)) return; // unchanged
    const toKey = categoryKey(to);
    if (toKey !== fromKey && canonicalNames.has(toKey)) {
      if (!confirm(`Merge into "${canonicalNames.get(toKey)}"? Both categories will become one.`)) return;
    }
    const plan = planCategoryRename(
      liveItems.map((i) => ({ id: i.id, category: itemFieldValue(i, "category") ?? "" })),
      categoryOrder,
      fromKey,
      to,
    );
    if (plan.orderChanged) setCategoryOrder(plan.nextCategoryOrder);
    await push({
      lists: plan.orderChanged
        ? [{ id, fields: fieldPatch(deviceId, "category_order", plan.nextCategoryOrder) }]
        : [],
      items: plan.itemIds.map((itemId) => ({
        id: itemId,
        list_id: id,
        fields: fieldPatch(deviceId, "category", to),
      })),
    });
  }

  function startRename(key: string) {
    setEditingCategoryKey(key);
    setCategoryDraft(canonicalNames.get(key) ?? "");
  }

  async function handleInvite(e: FormEvent) {
    e.preventDefault();
    const email = inviteEmail.trim();
    if (!email) return;
    try {
      const minted = await api.mintInvite(id, email);
      setInviteEmail("");
      // Surface the link so the inviter can pass it along (the server only returns the token at
      // mint time; nothing emails it for them).
      setInviteLink({ url: minted.url, email });
      setLinkCopied(false);
      const refreshed = await api.getMembers(id);
      setMembers(refreshed);
    } catch (err) {
      setMembersError(err instanceof ApiError ? err.message : "Failed to send invite.");
    }
  }

  async function copyInviteLink() {
    if (!inviteLink) return;
    try {
      await navigator.clipboard.writeText(inviteLink.url);
      setLinkCopied(true);
      setTimeout(() => setLinkCopied(false), 2000);
    } catch {
      // Clipboard unavailable (e.g. non-secure context) — the field stays selectable as a fallback.
    }
  }

  async function handleRevoke(inviteId: string) {
    await api.revokeInvite(inviteId);
    setMembers(await api.getMembers(id));
  }

  /**
   * Solo-owned snapshot copy (T-63): a new list with the source's name (suffixed), category order,
   * and notes, plus a fresh-id copy of every non-deleted item (status preserved as-is - this is a
   * template/snapshot duplicate, not a "reset for next week" action). No membership carries over.
   * Pushed as one sync batch, matching how the rest of this page mutates lists/items.
   */
  async function handleDuplicate() {
    if (!list) return;
    const newListId = crypto.randomUUID();
    const sourceItems = Array.from(items.values()).filter(
      (item) => item.list_id === id && !itemFieldValue(item, "deleted"),
    );
    await push({
      lists: [
        {
          id: newListId,
          created_at: nowMs(),
          fields: {
            ...fieldPatch(deviceId, "name", `${listFieldValue(list, "name")} (Copy)`),
            ...fieldPatch(deviceId, "category_order", listFieldValue(list, "category_order") ?? []),
            ...fieldPatch(deviceId, "notes", listFieldValue(list, "notes") ?? null),
          },
        },
      ],
      items: sourceItems.map((item) => ({
        id: crypto.randomUUID(),
        list_id: newListId,
        created_at: nowMs(),
        fields: {
          ...fieldPatch(deviceId, "name", itemFieldValue(item, "name") ?? ""),
          ...fieldPatch(deviceId, "category", itemFieldValue(item, "category") ?? null),
          ...fieldPatch(deviceId, "stores", itemFieldValue(item, "stores") ?? []),
          ...fieldPatch(deviceId, "quantity", itemFieldValue(item, "quantity") ?? null),
          ...fieldPatch(deviceId, "price", itemFieldValue(item, "price") ?? null),
          ...fieldPatch(deviceId, "note", itemFieldValue(item, "note") ?? null),
          ...fieldPatch(deviceId, "status", itemFieldValue(item, "status") ?? "todo"),
        },
      })),
    });
    navigate(`/list/${newListId}`);
  }

  /**
   * Moves every checked item back to the backlog (Spec: "clearing done items -> backlog"). Lives
   * here rather than on the list screen (T-75) so it can't be tapped by accident mid-shop.
   */
  async function handleClearChecked() {
    if (allChecked.length === 0) return;
    await push({
      items: allChecked.map((item) => ({
        id: item.id,
        list_id: id,
        fields: fieldPatch(deviceId, "status", "backlog" as ItemStatus),
      })),
    });
  }

  async function handleLeave() {
    if (!confirm("Leave this list? You will lose access to it.")) return;
    await api.leaveList(id);
    if (localStorage.getItem(LAST_LIST_STORAGE_KEY) === id) {
      localStorage.removeItem(LAST_LIST_STORAGE_KEY);
    }
    await refresh();
    navigate("/", { replace: true });
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <Link to={`/list/${listId}`} className="muted" style={{ fontSize: "0.85rem" }}>
        ← {listFieldValue(list, "name")}
      </Link>
      <h1 style={{ fontSize: "1.3rem" }}>List properties</h1>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Name</h2>
        <form onSubmit={saveName} style={{ display: "flex", gap: "0.5rem" }}>
          <input value={name} onChange={(e) => setName(e.target.value)} style={{ flex: 1 }} />
          <button type="submit" className="btn" disabled={savingName}>
            Save
          </button>
        </form>
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Type</h2>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "1rem" }}>
          <div>
            <div>{listKindLabel(listKind(list))}</div>
            <p className="muted" style={{ margin: "0.2rem 0 0", fontSize: "0.85rem" }}>
              {listKind(list) === "checklist"
                ? "Items have a name, category and note."
                : "Items also have stores, quantity and price."}
            </p>
          </div>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => setKind(listKind(list) === "checklist" ? "shopping" : "checklist")}
          >
            {listKind(list) === "checklist" ? "Make shopping list" : "Make checklist"}
          </button>
        </div>
        <p className="muted" style={{ margin: "0.5rem 0 0", fontSize: "0.8rem" }}>
          Switching only changes which fields are shown — nothing is deleted, so you can switch back.
        </p>
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Categories</h2>
        <p className="muted" style={{ margin: "0 0 0.6rem", fontSize: "0.85rem" }}>
          Rename to fix casing or merge; use the arrows to set the order items are grouped in.
        </p>
        {categoryKeys.length === 0 && <p className="muted">No categories yet.</p>}
        <div style={{ display: "flex", flexDirection: "column", gap: "0.3rem" }}>
          {categoryKeys.map((key) => {
            const index = orderIndexOf(key);
            const inOrder = index >= 0;
            if (editingCategoryKey === key) {
              return (
                <form
                  key={key}
                  onSubmit={(e) => {
                    e.preventDefault();
                    renameCategory(key, categoryDraft);
                  }}
                  style={{ display: "flex", alignItems: "center", gap: "0.4rem" }}
                >
                  <input
                    autoFocus
                    value={categoryDraft}
                    onChange={(e) => setCategoryDraft(e.target.value)}
                    style={{ flex: 1 }}
                    aria-label={`Rename ${canonicalNames.get(key)}`}
                  />
                  <button type="submit" className="btn btn-secondary">
                    Save
                  </button>
                  <button type="button" className="btn-icon" onClick={() => setEditingCategoryKey(null)} aria-label="Cancel">
                    ✕
                  </button>
                </form>
              );
            }
            return (
              <div key={key} style={{ display: "flex", alignItems: "center", gap: "0.4rem" }}>
                <span style={{ flex: 1 }}>{canonicalNames.get(key)}</span>
                <button type="button" className="btn-icon" onClick={() => startRename(key)} aria-label={`Rename ${canonicalNames.get(key)}`}>
                  ✎
                </button>
                <button
                  type="button"
                  className="btn-icon"
                  disabled={!inOrder}
                  onClick={() => moveCategory(index, -1)}
                  aria-label="Move up"
                >
                  ↑
                </button>
                <button
                  type="button"
                  className="btn-icon"
                  disabled={!inOrder}
                  onClick={() => moveCategory(index, 1)}
                  aria-label="Move down"
                >
                  ↓
                </button>
                {inOrder ? (
                  <button type="button" className="btn-icon" onClick={() => removeCategory(index)} aria-label="Remove from order">
                    ✕
                  </button>
                ) : (
                  <button
                    type="button"
                    className="btn-icon"
                    onClick={() => saveCategoryOrder([...categoryOrder, canonicalNames.get(key)!])}
                    aria-label="Add to order"
                  >
                    +
                  </button>
                )}
              </div>
            );
          })}
        </div>
        <form onSubmit={addCategory} style={{ display: "flex", gap: "0.5rem", marginTop: "0.6rem" }}>
          <input
            placeholder="Add category…"
            value={newCategory}
            onChange={(e) => setNewCategory(e.target.value)}
            style={{ flex: 1 }}
          />
          <button type="submit" className="btn btn-secondary">
            Add
          </button>
        </form>
      </section>

      {/* Relocated here from the list screen (T-75): too easy to hit by accident there. Only shown
          when there's something to clear. */}
      {allChecked.length > 0 && (
        <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
          <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Clear checked</h2>
          <p className="muted" style={{ margin: "0 0 0.6rem" }}>
            Move every checked item back to the backlog.
          </p>
          <button type="button" className="btn btn-danger" onClick={handleClearChecked}>
            Clear checked ({allChecked.length})
          </button>
        </section>
      )}

      {/* Free-text, not-regularly-needed info (T-62) — lives only here, not on the list/overview screens. */}
      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Notes</h2>
        <form onSubmit={saveNotes} style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
          <textarea
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Gate code, store hours, anything worth remembering…"
            rows={4}
            style={{ resize: "vertical", font: "inherit" }}
          />
          <button type="submit" className="btn" disabled={savingNotes} style={{ alignSelf: "flex-start" }}>
            Save notes
          </button>
        </form>
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Members</h2>
        {membersError && <p className="error-text">{membersError}</p>}
        {members && (
          <ul style={{ listStyle: "none", padding: 0, margin: "0 0 0.75rem" }}>
            {members.members.map((m) => (
              <li key={m.email}>{m.email}</li>
            ))}
          </ul>
        )}
        {members && members.invites.length > 0 && (
          <>
            <h3 className="muted" style={{ fontSize: "0.85rem" }}>
              Pending invites
            </h3>
            <ul style={{ listStyle: "none", padding: 0 }}>
              {members.invites.map((inv) => (
                <li key={inv.id} style={{ display: "flex", justifyContent: "space-between", padding: "0.2rem 0" }}>
                  <span>{inv.invited_email}</span>
                  <button type="button" className="btn-icon" onClick={() => handleRevoke(inv.id)}>
                    Revoke
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
        <form onSubmit={handleInvite} style={{ display: "flex", gap: "0.5rem", marginTop: "0.6rem" }}>
          <input
            type="email"
            placeholder="Invite by email…"
            value={inviteEmail}
            onChange={(e) => setInviteEmail(e.target.value)}
            style={{ flex: 1 }}
          />
          <button type="submit" className="btn btn-secondary">
            Invite
          </button>
        </form>

        {inviteLink && (
          <div
            style={{
              marginTop: "0.75rem",
              padding: "0.6rem",
              border: "1px solid var(--color-border)",
              borderRadius: "var(--radius)",
            }}
          >
            <p className="muted" style={{ margin: "0 0 0.4rem", fontSize: "0.85rem" }}>
              Invite link for <strong>{inviteLink.email}</strong> — send it to them. Only that email
              can redeem it, and it expires in 7 days.
            </p>
            <div style={{ display: "flex", gap: "0.5rem" }}>
              <input
                readOnly
                value={inviteLink.url}
                onFocus={(e) => e.target.select()}
                style={{ flex: 1, fontSize: "0.8rem" }}
                aria-label="Invite link"
              />
              <button type="button" className="btn" onClick={copyInviteLink}>
                {linkCopied ? "Copied!" : "Copy"}
              </button>
            </div>
          </div>
        )}
      </section>

      <button type="button" className="btn btn-secondary" onClick={handleDuplicate} style={{ marginRight: "0.5rem" }}>
        Duplicate
      </button>
      <button type="button" className="btn btn-danger" onClick={handleLeave}>
        Leave list
      </button>
    </main>
  );
}
