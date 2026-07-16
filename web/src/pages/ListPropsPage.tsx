import { useEffect, useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import * as api from "../api/client";
import { ApiError } from "../api/client";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue, nowMs } from "../hooks/useSync";
import { checkedItems } from "../lib/grouping";
import type { ItemStatus, MembersResponse } from "../api/contract";
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
  const [members, setMembers] = useState<MembersResponse | null>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [membersError, setMembersError] = useState<string | null>(null);
  const [savingName, setSavingName] = useState(false);
  const [savingNotes, setSavingNotes] = useState(false);

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

  const allChecked = checkedItems(Array.from(items.values()).filter((i) => i.list_id === id));

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
    if (!trimmed || categoryOrder.includes(trimmed)) return;
    saveCategoryOrder([...categoryOrder, trimmed]);
    setNewCategory("");
  }

  async function handleInvite(e: FormEvent) {
    e.preventDefault();
    if (!inviteEmail.trim()) return;
    try {
      await api.mintInvite(id, inviteEmail.trim());
      setInviteEmail("");
      const refreshed = await api.getMembers(id);
      setMembers(refreshed);
    } catch (err) {
      setMembersError(err instanceof ApiError ? err.message : "Failed to send invite.");
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
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Category order</h2>
        {categoryOrder.length === 0 && <p className="muted">No custom order set; categories sort alphabetically.</p>}
        <div style={{ display: "flex", flexDirection: "column", gap: "0.3rem" }}>
          {categoryOrder.map((category, index) => (
            <div key={category} style={{ display: "flex", alignItems: "center", gap: "0.4rem" }}>
              <span style={{ flex: 1 }}>{category}</span>
              <button type="button" className="btn-icon" onClick={() => moveCategory(index, -1)} aria-label="Move up">
                ↑
              </button>
              <button
                type="button"
                className="btn-icon"
                onClick={() => moveCategory(index, 1)}
                aria-label="Move down"
              >
                ↓
              </button>
              <button type="button" className="btn-icon" onClick={() => removeCategory(index)} aria-label="Remove">
                ✕
              </button>
            </div>
          ))}
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
