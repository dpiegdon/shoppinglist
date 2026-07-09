import { useEffect, useState, type FormEvent } from "react";
import { Navigate, Link } from "react-router-dom";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch } from "../hooks/useSync";
import { listFieldValue } from "../hooks/useSync";

export const LAST_LIST_STORAGE_KEY = "shoppinglist_last_list_id";

// Resuming the last-opened list must happen once, on first entry into the
// app (Spec: "on login, open the list the user last had open") - NOT on
// every visit to "/", or the overview would become unreachable once any
// list has been opened. Module-level so it resets on a real page reload
// (fresh entry) but stays put across in-app navigation.
let didInitialResume = false;

/** Test-only: restores the module-level flag to its fresh-page-load state. */
export function _resetInitialResumeForTests() {
  didInitialResume = false;
}

export default function OverviewPage() {
  const { lists, loading, push, deviceId } = useSyncContext();
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const [redirectTo, setRedirectTo] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    if (loading) return;
    if (!didInitialResume) {
      didInitialResume = true;
      const lastId = localStorage.getItem(LAST_LIST_STORAGE_KEY);
      if (lastId && lists.has(lastId)) {
        setRedirectTo(`/list/${lastId}`);
        return;
      }
    }
    setRedirectTo(null);
    // Only decide once, right after the first load completes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading]);

  if (redirectTo) {
    return <Navigate to={redirectTo} replace />;
  }

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    const name = newName.trim();
    if (!name) return;
    const id = crypto.randomUUID();
    await push({
      lists: [{ id, fields: fieldPatch(deviceId, "name", name) }],
    });
    setNewName("");
    setCreating(false);
  }

  const listArray = Array.from(lists.values()).sort((a, b) =>
    (listFieldValue(a, "name") ?? "").localeCompare(listFieldValue(b, "name") ?? ""),
  );

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ fontSize: "1.3rem" }}>Your lists</h1>
        <button type="button" className="btn" onClick={() => setCreating(true)}>
          + New list
        </button>
      </div>

      {loading && listArray.length === 0 && <p className="muted">Loading…</p>}
      {!loading && listArray.length === 0 && <p className="muted">No lists yet. Create one to get started.</p>}

      <div style={{ display: "flex", flexDirection: "column", gap: "0.6rem", marginTop: "1rem" }}>
        {listArray.map((list) => (
          <Link
            key={list.id}
            to={`/list/${list.id}`}
            className="card"
            onClick={() => localStorage.setItem(LAST_LIST_STORAGE_KEY, list.id)}
            style={{
              padding: "1rem",
              textDecoration: "none",
              color: "var(--color-text)",
              fontWeight: 600,
            }}
          >
            {listFieldValue(list, "name")}
          </Link>
        ))}
      </div>

      {creating && (
        <div className="dialog-overlay" onClick={() => setCreating(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={handleCreate}>
            <h2 style={{ marginTop: 0, fontSize: "1.1rem" }}>New list</h2>
            <div className="form-field">
              <label htmlFor="new-list-name">Name</label>
              <input
                id="new-list-name"
                autoFocus
                required
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
              />
            </div>
            <div style={{ display: "flex", gap: "0.5rem", justifyContent: "flex-end" }}>
              <button type="button" className="btn btn-secondary" onClick={() => setCreating(false)}>
                Cancel
              </button>
              <button type="submit" className="btn">
                Create
              </button>
            </div>
          </form>
        </div>
      )}
    </main>
  );
}
