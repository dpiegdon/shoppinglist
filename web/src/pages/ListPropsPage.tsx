import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import * as api from "../api/client";
import { safeLocalStorage } from "../lib/safeStorage";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue, nowMs } from "../hooks/useSync";
import { checkedItems } from "../lib/grouping";
import { canonicalCategoryNames, categoryKey, normalizeCategoryOrder, planCategoryRename } from "../lib/categories";
import { isExpenses, listKind, listKindLabelKey } from "../lib/listKind";
import CloseVoteBanner from "../components/CloseVoteBanner";
import { useAuth } from "../auth/AuthContext";
import type { ItemStatus, ListKind, MembersResponse } from "../api/contract";
import { LAST_LIST_STORAGE_KEY } from "./OverviewPage";
import { useT } from "../i18n";
import { compareNames } from "../lib/nameOrder";
import { errorMessage } from "../i18n/apiErrors";

export default function ListPropsPage() {
  const t = useT();
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId, forgetList, loading } = useSyncContext();
  const { account } = useAuth();
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

  // The fields above are seeded from `list` on the first render, but a reload or a deep link renders
  // first with no list at all — the web client has no local mirror, so lists only exist once the
  // first sync lands (T-188). Seed them once, when the list first arrives, and never again: after
  // that they hold what the user is typing.
  const seededFor = useRef<string | null>(list ? listId ?? null : null);
  useEffect(() => {
    if (!list || !listId || seededFor.current === listId) return;
    seededFor.current = listId;
    setName(listFieldValue(list, "name") ?? "");
    setCategoryOrder(listFieldValue(list, "category_order") ?? []);
    setNotes(listFieldValue(list, "notes") ?? "");
  }, [list, listId]);

  useEffect(() => {
    if (!listId) return;
    api
      .getMembers(listId)
      .then(setMembers)
      .catch((err) => setMembersError(errorMessage(t, err, "listProps.membersFailed")));
  }, [listId]);

  if (!listId) return <Navigate to="/" replace />;
  // Not a redirect (T-188), as on the All items screen (T-146): on a reload or a deep link the list
  // does not exist yet on the first render, and redirecting bounced everyone to the overview.
  if (!list) {
    return (
      <main style={{ padding: "1rem" }}>
        <p className="muted">{loading ? t("common.loading") : t("list.notFound")}</p>
        {!loading && <Link to="/">{t("list.backToOverview")}</Link>}
      </main>
    );
  }
  const id: string = listId;
  const lockedByVote =
    isExpenses(listKind(list)) &&
    (list.closed_at ?? null) === null &&
    !!account &&
    (list.close_votes ?? []).includes(account.id);

  const liveItems = Array.from(items.values()).filter(
    (i) => i.list_id === id && !itemFieldValue(i, "deleted"),
  );
  const allChecked = checkedItems(liveItems);

  // The full set of categories in this list (T-108) — everything on an item PLUS anything the
  // user has explicitly ordered — so casing can be fixed here for any of them, not just ordered
  // ones. Canonical casing, ordered ones first (in order), the rest alphabetically.
  // The order as the page shows and saves it (T-212): the stored array may still carry a blank or a
  // second casing from before categories were case-insensitive; moving by RAW position swapped
  // with such an invisible neighbour and a press did nothing visible.
  const order = normalizeCategoryOrder(categoryOrder);
  const canonicalNames = canonicalCategoryNames(
    liveItems.map((i) => itemFieldValue(i, "category") ?? ""),
    order,
  );
  const orderedKeys = order.map(categoryKey);
  const categoryKeys = [
    ...orderedKeys.filter((k) => canonicalNames.has(k)),
    ...Array.from(canonicalNames.keys())
      .filter((k) => !orderedKeys.includes(k))
      .sort((a, b) => compareNames(canonicalNames.get(a)!, canonicalNames.get(b)!) || (a < b ? -1 : a > b ? 1 : 0)),
  ];
  const orderIndexOf = (key: string) => order.findIndex((e) => categoryKey(e) === key);

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
    const clean = normalizeCategoryOrder(next);
    setCategoryOrder(clean);
    await push({ lists: [{ id, fields: fieldPatch(deviceId, "category_order", clean) }] });
  }

  /** Swap with the neighbouring ROW: indices are into `order`, never the stored array (T-212). */
  function moveCategory(index: number, delta: number) {
    const next = [...order];
    const target = index + delta;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    saveCategoryOrder(next);
  }

  function removeCategory(index: number) {
    saveCategoryOrder(order.filter((_, i) => i !== index));
  }

  function addCategory(e: FormEvent) {
    e.preventDefault();
    const trimmed = newCategory.trim();
    // Case-insensitive dedup (T-108): don't add "Group" when "group" is already ordered.
    if (!trimmed || order.some((c) => categoryKey(c) === categoryKey(trimmed))) return;
    saveCategoryOrder([...order, trimmed]);
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
      setMembersError(errorMessage(t, err, "listProps.inviteFailed"));
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
            // The duplicate must keep the source's kind (T-267): omitting it left the server to
            // apply its default (shopping), so a duplicated checklist came back showing the
            // stores/price/quantity fields the original hid. Not offered for expenses (see the
            // guard below), so this never needs to carry a currency along with it.
            ...fieldPatch(deviceId, "kind", listKind(list)),
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
    if (!confirm(t("listProps.leaveConfirm"))) return;
    await api.leaveList(id);
    if (safeLocalStorage.getItem(LAST_LIST_STORAGE_KEY) === id) {
      safeLocalStorage.removeItem(LAST_LIST_STORAGE_KEY);
    }
    // The server just deletes the membership row — a list other members still share gets no
    // tombstone — so a pull's delta says nothing about it and would leave the stale copy sitting
    // in the sync store until a reload (T-268). Forget it locally instead of pulling.
    forgetList(id);
    navigate("/", { replace: true });
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <Link to={`/list/${listId}`} className="muted" style={{ fontSize: "0.85rem" }}>
        ← {listFieldValue(list, "name")}
      </Link>
      <h1 style={{ fontSize: "1.3rem" }}>{t("listProps.title")}</h1>

      {/* Someone who has agreed to close an expense list changes nothing on it (T-193). */}
      {lockedByVote && (
        <p className="muted" style={{ margin: "0 0 1rem" }}>
          {t("apiError.votedToClose")}
        </p>
      )}

      <section style={{ marginBottom: "1.5rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.name")}</h2>
        <form onSubmit={saveName} style={{ display: "flex", gap: "0.5rem" }}>
          <input value={name} onChange={(e) => setName(e.target.value)} disabled={lockedByVote} style={{ flex: 1 }} />
          <button type="submit" className="btn" disabled={savingName || lockedByVote}>
            {t("action.save")}
          </button>
        </form>
      </section>

      <section style={{ marginBottom: "1.5rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.type")}</h2>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "1rem" }}>
          <div>
            <div>{t(listKindLabelKey(listKind(list)))}</div>
            <p className="muted" style={{ margin: "0.2rem 0 0", fontSize: "0.85rem" }}>
              {isExpenses(listKind(list))
                ? t("listProps.kind.expenses")
                : listKind(list) === "checklist"
                  ? t("listProps.kind.checklist")
                  : t("listProps.kind.shopping")}
            </p>
          </div>
          {/* An expenses list cannot be converted in either direction — the server refuses it,
              because its items have a different shape (T-151). So there is nothing to offer. */}
          {!isExpenses(listKind(list)) && (
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setKind(listKind(list) === "checklist" ? "shopping" : "checklist")}
            >
              {listKind(list) === "checklist" ? t("listProps.makeShopping") : t("listProps.makeChecklist")}
            </button>
          )}
        </div>
        <p className="muted" style={{ margin: "0.5rem 0 0", fontSize: "0.8rem" }}>
          {isExpenses(listKind(list)) ? t("listProps.kindFixed") : t("listProps.kindSwitchHelp")}
        </p>
        {isExpenses(listKind(list)) && (
          <p style={{ margin: "0.5rem 0 0" }}>
            {t("expense.currency")}: <strong>{listFieldValue(list, "currency")}</strong>
          </p>
        )}
      </section>

      {/* Relocated here from the list screen (T-75): too easy to hit by accident there. Only shown
          when there's something to clear. */}
      {!isExpenses(listKind(list)) && allChecked.length > 0 && (
        <section style={{ marginBottom: "1.5rem" }}>
          <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.clearChecked")}</h2>
          <p className="muted" style={{ margin: "0 0 0.6rem" }}>
            {t("listProps.clearCheckedHelp")}
          </p>
          <button type="button" className="btn btn-danger" onClick={handleClearChecked}>
            {t("listProps.clearCheckedCount", { count: allChecked.length })}
          </button>
        </section>
      )}

      <section style={{ marginBottom: "1.5rem", display: isExpenses(listKind(list)) ? "none" : undefined }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.categories")}</h2>
        <p className="muted" style={{ margin: "0 0 0.6rem", fontSize: "0.85rem" }}>
          {t("listProps.categoriesHelp")}
        </p>
        {categoryKeys.length === 0 && <p className="muted">{t("listProps.noCategories")}</p>}
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
                    aria-label={t("listProps.renameCategory", { category: canonicalNames.get(key) ?? "" })}
                  />
                  <button type="submit" className="btn btn-secondary">
                    {t("action.save")}
                  </button>
                  <button type="button" className="btn-icon" onClick={() => setEditingCategoryKey(null)} aria-label={t("action.cancel")}>
                    ✕
                  </button>
                </form>
              );
            }
            return (
              <div key={key} style={{ display: "flex", alignItems: "center", gap: "0.4rem" }}>
                <span style={{ flex: 1 }}>{canonicalNames.get(key)}</span>
                <button type="button" className="btn-icon" onClick={() => startRename(key)} aria-label={t("listProps.renameCategory", { category: canonicalNames.get(key) ?? "" })}>
                  ✎
                </button>
                <button
                  type="button"
                  className="btn-icon"
                  disabled={!inOrder || index === 0}
                  onClick={() => moveCategory(index, -1)}
                  aria-label={t("listProps.moveUp")}
                >
                  ↑
                </button>
                <button
                  type="button"
                  className="btn-icon"
                  disabled={!inOrder || index === order.length - 1}
                  onClick={() => moveCategory(index, 1)}
                  aria-label={t("listProps.moveDown")}
                >
                  ↓
                </button>
                {inOrder ? (
                  <button type="button" className="btn-icon" onClick={() => removeCategory(index)} aria-label={t("listProps.removeFromOrder")}>
                    ✕
                  </button>
                ) : (
                  <button
                    type="button"
                    className="btn-icon"
                    onClick={() => saveCategoryOrder([...order, canonicalNames.get(key)!])}
                    aria-label={t("listProps.addToOrder")}
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
            placeholder={t("listProps.addCategory")}
            value={newCategory}
            onChange={(e) => setNewCategory(e.target.value)}
            style={{ flex: 1 }}
          />
          <button type="submit" className="btn btn-secondary">
            {t("action.add")}
          </button>
        </form>
      </section>

      {/* Free-text, not-regularly-needed info (T-62) — lives only here, not on the list/overview screens. */}
      <section style={{ marginBottom: "1.5rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.notes")}</h2>
        <form onSubmit={saveNotes} style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
          <textarea
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder={t("listProps.notesPlaceholder")}
            disabled={lockedByVote}
            rows={4}
            style={{ resize: "vertical", font: "inherit" }}
          />
          <button type="submit" className="btn" disabled={savingNotes || lockedByVote} style={{ alignSelf: "flex-start" }}>
            {t("listProps.saveNotes")}
          </button>
        </form>
      </section>

      <section style={{ marginBottom: "1.5rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.members")}</h2>
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
              {t("listProps.pendingInvites")}
            </h3>
            <ul style={{ listStyle: "none", padding: 0 }}>
              {members.invites.map((inv) => (
                <li key={inv.id} style={{ display: "flex", justifyContent: "space-between", padding: "0.2rem 0" }}>
                  <span>{inv.invited_email}</span>
                  <button type="button" className="btn-icon" onClick={() => handleRevoke(inv.id)}>
                    {t("action.revoke")}
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
        <form onSubmit={handleInvite} style={{ display: "flex", gap: "0.5rem", marginTop: "0.6rem" }}>
          <input
            type="email"
            placeholder={t("listProps.inviteByEmail")}
            value={inviteEmail}
            onChange={(e) => setInviteEmail(e.target.value)}
            style={{ flex: 1 }}
          />
          <button type="submit" className="btn btn-secondary">
            {t("action.invite")}
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
              {t("listProps.inviteLinkFor", { email: inviteLink.email })}
            </p>
            <div style={{ display: "flex", gap: "0.5rem" }}>
              <input
                readOnly
                value={inviteLink.url}
                onFocus={(e) => e.target.select()}
                style={{ flex: 1, fontSize: "0.8rem" }}
                aria-label={t("listProps.inviteLink")}
              />
              <button type="button" className="btn" onClick={copyInviteLink}>
                {linkCopied ? t("action.copied") : t("action.copy")}
              </button>
            </div>
          </div>
        )}
      </section>

      {/* Closing sits directly above Leave (T-169): they are two stages of one thing — agree to
          close, then, once closed, leave. */}
      {isExpenses(listKind(list)) && (
        <section style={{ marginBottom: "1.5rem" }}>
          <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("expense.closing")}</h2>
          <p className="muted" style={{ margin: "0 0 0.6rem", fontSize: "0.85rem" }}>
            {t("expense.closingHelp")}
          </p>
          <CloseVoteBanner
            listId={id}
            members={list.members ?? []}
            closeVotes={list.close_votes ?? []}
            closedAt={list.closed_at ?? null}
            myAccountId={account?.id ?? null}
            alwaysShow
          />
        </section>
      )}

      {/* Not offered for expenses (T-155): a copy of a shared ledger, with the same debts owed to
          nobody in particular, is never what someone means. */}
      {!isExpenses(listKind(list)) && (
        <button type="button" className="btn btn-secondary" onClick={handleDuplicate} style={{ marginInlineEnd: "0.5rem" }}>
          {t("action.duplicate")}
        </button>
      )}
      {/* An open expenses list cannot be left (T-157): the server refuses it, and saying why
          here beats letting the button fail. */}
      <button
        type="button"
        className="btn btn-danger"
        disabled={isExpenses(listKind(list)) && (list.closed_at ?? null) === null}
        onClick={handleLeave}
      >
        {t("listProps.leaveList")}
      </button>
      {isExpenses(listKind(list)) && (list.closed_at ?? null) === null && (
        <p className="muted" style={{ fontSize: "0.8rem", marginTop: "0.4rem" }}>
          {t("listProps.leaveBlocked")}
        </p>
      )}
    </main>
  );
}
