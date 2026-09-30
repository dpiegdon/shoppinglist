import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import * as api from "../api/client";
import { safeLocalStorage } from "../lib/safeStorage";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue, nowMs } from "../hooks/useSync";
import { checkedItems } from "../lib/grouping";
import {
  canonicalCategoryNames,
  categoryEditorList,
  categoryKey,
  normalizeCategoryOrder,
  planCategoryRename,
} from "../lib/categories";
import { isExpenses, listKind, listKindIcon, listKindLabelKey } from "../lib/listKind";
import CloseVoteBanner from "../components/CloseVoteBanner";
import ToggleSwitch from "../components/ToggleSwitch";
import { useAuth } from "../auth/AuthContext";
import type { ItemObject, ItemStatus, ListKind, MembersResponse } from "../api/contract";
import { LAST_LIST_STORAGE_KEY } from "./OverviewPage";
import { useT } from "../i18n";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { errorMessage } from "../i18n/apiErrors";
import { useDragReorder } from "../hooks/useDragReorder";

export default function ListPropsPage() {
  const t = useT();
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId, forgetList, loading } = useSyncContext();
  const { account } = useAuth();
  const navigate = useNavigate();
  const list = listId ? lists.get(listId) : undefined;
  useDocumentTitle(t("listProps.title"), list && listFieldValue(list, "name"));

  const [name, setName] = useState(list ? listFieldValue(list, "name") ?? "" : "");
  const [categoryOrder, setCategoryOrder] = useState<string[]>(
    list ? listFieldValue(list, "category_order") ?? [] : [],
  );
  const [notes, setNotes] = useState(list ? listFieldValue(list, "notes") ?? "" : "");
  // What the name and notes were when seeded or last saved (T-340): each Save is offered only while
  // its field differs, and "Saved." shows in its card until the field is edited again. As Android.
  const [savedName, setSavedName] = useState(list ? (listFieldValue(list, "name") ?? "").trim() : "");
  const [savedNotes, setSavedNotes] = useState<string | null>(
    list ? (listFieldValue(list, "notes") ?? "").trim() || null : null,
  );
  const [nameSaved, setNameSaved] = useState(false);
  const [notesSaved, setNotesSaved] = useState(false);
  const [members, setMembers] = useState<MembersResponse | null>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [membersError, setMembersError] = useState<string | null>(null);
  const [inviteError, setInviteError] = useState<string | null>(null);
  const [savingName, setSavingName] = useState(false);
  const [savingNotes, setSavingNotes] = useState(false);
  // Inline errors for the actions on this page that don't go through a dialog (T-266): a rejected
  // push used to be an unhandled rejection everywhere below, so the button just did nothing and
  // said nothing.
  const [nameError, setNameError] = useState<string | null>(null);
  const [notesError, setNotesError] = useState<string | null>(null);
  const [kindError, setKindError] = useState<string | null>(null);
  const [clearCheckedError, setClearCheckedError] = useState<string | null>(null);
  const [duplicateError, setDuplicateError] = useState<string | null>(null);
  const [leaveError, setLeaveError] = useState<string | null>(null);
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
    setSavedName((listFieldValue(list, "name") ?? "").trim());
    setCategoryOrder(listFieldValue(list, "category_order") ?? []);
    setNotes(listFieldValue(list, "notes") ?? "");
    setSavedNotes((listFieldValue(list, "notes") ?? "").trim() || null);
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
  const nameChanged = name.trim() !== "" && name.trim() !== savedName;
  const notesChanged = (notes.trim() || null) !== savedNotes;

  async function saveName(e: FormEvent) {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || trimmed === savedName) return;
    setSavingName(true);
    setNameError(null);
    try {
      await push({ lists: [{ id, fields: fieldPatch(deviceId, "name", trimmed) }] });
      setSavedName(trimmed);
      setNameSaved(true);
    } catch (err) {
      setNameError(errorMessage(t, err, "item.saveFailed"));
    } finally {
      setSavingName(false);
    }
  }

  async function saveNotes(e: FormEvent) {
    e.preventDefault();
    const next = notes.trim() || null;
    if (next === savedNotes) return;
    setSavingNotes(true);
    setNotesError(null);
    try {
      await push({ lists: [{ id, fields: fieldPatch(deviceId, "notes", next) }] });
      setSavedNotes(next);
      setNotesSaved(true);
    } catch (err) {
      setNotesError(errorMessage(t, err, "item.saveFailed"));
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
    setKindError(null);
    try {
      await push({ lists: [{ id, fields: fieldPatch(deviceId, "kind", kind) }] });
    } catch (err) {
      setKindError(errorMessage(t, err, "item.saveFailed"));
    }
  }

  async function handleInvite(e: FormEvent) {
    e.preventDefault();
    const email = inviteEmail.trim();
    if (!email) return;
    setInviteError(null);
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
      setInviteError(errorMessage(t, err, "listProps.inviteFailed"));
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
    setInviteError(null);
    try {
      await api.revokeInvite(inviteId);
      setMembers(await api.getMembers(id));
    } catch (err) {
      // Under the invite field, where an invite's own failure shows (T-266, T-340), as on Android.
      setInviteError(errorMessage(t, err, "item.saveFailed"));
    }
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
    setDuplicateError(null);
    try {
      await push({
        lists: [
          {
            id: newListId,
            created_at: nowMs(),
            fields: {
              // In the language of whoever made the copy: the name is synced data, not UI text.
              ...fieldPatch(deviceId, "name", `${listFieldValue(list, "name")} ${t("listProps.copySuffix")}`),
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
            ...fieldPatch(deviceId, "due", itemFieldValue(item, "due") ?? null),
            ...fieldPatch(deviceId, "status", itemFieldValue(item, "status") ?? "todo"),
          },
        })),
      });
    } catch (err) {
      // T-266: a rejected push here used to be an unhandled rejection — the button did nothing,
      // said nothing, and never navigated (which, at least, meant no half-made copy was shown).
      setDuplicateError(errorMessage(t, err, "item.saveFailed"));
      return;
    }
    navigate(`/list/${newListId}`);
  }

  /**
   * Moves every checked item back to the backlog (Spec: "clearing done items -> backlog"). Lives
   * here rather than on the list screen (T-75) so it can't be tapped by accident mid-shop.
   */
  async function handleClearChecked() {
    if (allChecked.length === 0) return;
    setClearCheckedError(null);
    try {
      await push({
        items: allChecked.map((item) => ({
          id: item.id,
          list_id: id,
          fields: fieldPatch(deviceId, "status", "backlog" as ItemStatus),
        })),
      });
    } catch (err) {
      setClearCheckedError(errorMessage(t, err, "item.saveFailed"));
    }
  }

  async function handleLeave() {
    if (!confirm(t("listProps.leaveConfirm"))) return;
    setLeaveError(null);
    try {
      await api.leaveList(id);
    } catch (err) {
      setLeaveError(errorMessage(t, err, "item.saveFailed"));
      return;
    }
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
      {/* The arrow comes with the translation, so it points back in Arabic too (T-340). */}
      <Link to={`/list/${listId}`} className="muted" style={{ fontSize: "0.85rem" }}>
        {t("listProps.backToList", { name: listFieldValue(list, "name") ?? "" })}
      </Link>
      <h1 style={{ fontSize: "1.3rem" }}>{t("listProps.title")}</h1>

      {/* Someone who has agreed to close an expense list changes nothing on it (T-193). */}
      {lockedByVote && (
        <p className="muted" style={{ margin: "0 0 1rem" }}>
          {t("apiError.votedToClose")}
        </p>
      )}

      {/* The sections sit in cards, as in Settings and on Android, in one order on both clients
          (T-337): the list itself, its categories, notes, who it is shared with, closing a ledger,
          the actions, and last, in red, leaving it. */}
      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.list")}</h2>
        {/* A field its card title does not name carries its own label (T-340), as on Android. */}
        <label htmlFor="list-props-name" style={{ display: "block", fontSize: "0.9rem", fontWeight: 600, margin: "0 0 0.5rem" }}>
          {t("listProps.name")}
        </label>
        <form onSubmit={saveName} style={{ display: "flex", gap: "0.5rem" }}>
          <input
            id="list-props-name"
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              setNameSaved(false);
            }}
            disabled={lockedByVote}
            style={{ flex: 1 }}
          />
          {/* Offered only while there is something to save, and it says so once saved (T-340). */}
          <button type="submit" className="btn" disabled={savingName || lockedByVote || !nameChanged}>
            {t("action.save")}
          </button>
        </form>
        {nameSaved && (
          <p className="muted" role="status" style={{ margin: "0.4rem 0 0" }}>
            {t("common.saved")}
          </p>
        )}
        {nameError && (
          <p className="error-text" role="alert">
            {nameError}
          </p>
        )}

        <h3 style={{ fontSize: "0.9rem", margin: "1rem 0 0.5rem" }}>{t("listProps.type")}</h3>
        {/* The type's icon and name, and a switch for a checklist, as Android shows it (T-340). */}
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "1rem" }}>
          <div>
            <div>
              <span aria-hidden="true">{listKindIcon(listKind(list))}</span> {t(listKindLabelKey(listKind(list)))}
            </div>
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
            <ToggleSwitch
              checked={listKind(list) === "checklist"}
              onChange={() => setKind(listKind(list) === "checklist" ? "shopping" : "checklist")}
              label={t("listKind.checklist")}
              onColor="var(--color-accent)"
              offColor="var(--color-text-muted)"
            />
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
        {kindError && (
          <p className="error-text" role="alert">
            {kindError}
          </p>
        )}

        {/* Relocated here from the list screen (T-75): too easy to hit by accident there. Only
            shown when there's something to clear; the last thing in the List card, directly under
            the type (T-337). */}
        {!isExpenses(listKind(list)) && allChecked.length > 0 && (
          <>
            {/* The button names the action; no heading repeats it (T-340). A card's closing
                action is full width. */}
            <p className="muted" style={{ margin: "1rem 0 0.6rem", fontSize: "0.85rem" }}>
              {t("listProps.clearCheckedHelp")}
            </p>
            <button type="button" className="btn btn-danger" onClick={handleClearChecked} style={{ width: "100%" }}>
              {t("listProps.clearCheckedCount", { count: allChecked.length })}
            </button>
            {clearCheckedError && (
              <p className="error-text" role="alert">
                {clearCheckedError}
              </p>
            )}
          </>
        )}
      </section>

      {!isExpenses(listKind(list)) && (
        <CategoriesCard listId={id} liveItems={liveItems} categoryOrder={categoryOrder} setCategoryOrder={setCategoryOrder} />
      )}

      {/* Free-text, not-regularly-needed info (T-62) — lives only here, not on the list/overview screens. */}
      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.notes")}</h2>
        <form onSubmit={saveNotes} style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
          {/* Named by its card's title, which a screen reader hears as its name too (T-340). */}
          <textarea
            value={notes}
            onChange={(e) => {
              setNotes(e.target.value);
              setNotesSaved(false);
            }}
            aria-label={t("listProps.notes")}
            placeholder={t("listProps.notesPlaceholder")}
            disabled={lockedByVote}
            rows={4}
            style={{ resize: "vertical", font: "inherit" }}
          />
          <button type="submit" className="btn" disabled={savingNotes || lockedByVote || !notesChanged}>
            {t("listProps.saveNotes")}
          </button>
        </form>
        {notesSaved && (
          <p className="muted" role="status" style={{ margin: "0.4rem 0 0" }}>
            {t("common.saved")}
          </p>
        )}
        {notesError && (
          <p className="error-text" role="alert">
            {notesError}
          </p>
        )}
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.members")}</h2>
        {membersError && (
          <p className="error-text" role="alert">
            {membersError}
          </p>
        )}
        {members && (
          <ul style={{ listStyle: "none", padding: 0, margin: "0 0 0.75rem" }}>
            {members.members.map((m) => (
              <li key={m.email}>{m.email}</li>
            ))}
          </ul>
        )}
        {/* A pending invite is a row of the roster marked "(pending)", as on Android (T-340).
            Revoke acts at once there too: an invite is cheap to send again. */}
        {members && members.invites.length > 0 && (
          <ul style={{ listStyle: "none", padding: 0, margin: "0 0 0.75rem" }}>
            {members.invites.map((inv) => (
              <li
                key={inv.id}
                style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.5rem", padding: "0.2rem 0" }}
              >
                <span>{t("listProps.invitePending", { email: inv.invited_email })}</span>
                <button type="button" className="btn btn-danger" onClick={() => handleRevoke(inv.id)}>
                  {t("action.revoke")}
                </button>
              </li>
            ))}
          </ul>
        )}
        <form onSubmit={handleInvite} style={{ display: "flex", gap: "0.5rem", marginTop: "0.6rem" }}>
          <input
            type="email"
            aria-label={t("listProps.inviteByEmail")}
            placeholder={t("listProps.inviteByEmail")}
            value={inviteEmail}
            onChange={(e) => {
              setInviteEmail(e.target.value);
              setInviteError(null);
            }}
            style={{ flex: 1 }}
          />
          <button type="submit" className="btn">
            {t("action.invite")}
          </button>
        </form>
        {inviteError && (
          <p className="error-text" role="alert">
            {inviteError}
          </p>
        )}

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
        <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
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
          nobody in particular, is never what someone means. So a ledger has no Actions card, and
          Closing stands directly above Leave. */}
      {!isExpenses(listKind(list)) && (
        <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
          <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.actions")}</h2>
          <button type="button" className="btn" onClick={handleDuplicate} style={{ width: "100%" }}>
            {t("action.duplicate")}
          </button>
          {duplicateError && (
            <p className="error-text" role="alert">
              {duplicateError}
            </p>
          )}
        </section>
      )}

      <section className="card" style={{ padding: "1rem", borderColor: "var(--color-danger)" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0, color: "var(--color-danger)" }}>{t("listProps.leaveList")}</h2>
        {/* An open expenses list cannot be left (T-157): the server refuses it, and saying why
            here beats letting the button fail. */}
        <button
          type="button"
          className="btn btn-danger"
          disabled={isExpenses(listKind(list)) && (list.closed_at ?? null) === null}
          onClick={handleLeave}
          style={{ width: "100%" }}
        >
          {t("listProps.leaveList")}
        </button>
        {isExpenses(listKind(list)) && (list.closed_at ?? null) === null && (
          <p className="muted" style={{ fontSize: "0.8rem", margin: "0.4rem 0 0" }}>
            {t("listProps.leaveBlocked")}
          </p>
        )}
        {leaveError && (
          <p className="error-text" role="alert">
            {leaveError}
          </p>
        )}
      </section>
    </main>
  );
}

/**
 * The Categories card (T-340), on Android's model: every category of the list is listed (the
 * stored order first, then the rest in name order, as categoryEditorList builds it on both
 * clients), each with a rename pencil and a drag handle, and "Save order" saves the order shown.
 * The handle is a button, so the keyboard reorders too: ArrowUp/ArrowDown move its row, and the
 * move is announced.
 */
function CategoriesCard({
  listId,
  liveItems,
  categoryOrder,
  setCategoryOrder,
}: {
  listId: string;
  liveItems: ItemObject[];
  categoryOrder: string[];
  setCategoryOrder: (order: string[]) => void;
}) {
  const t = useT();
  const { push, deviceId } = useSyncContext();
  // The order as the user has moved it, by key, until it is saved; null while nothing has moved.
  const [draft, setDraft] = useState<string[] | null>(null);
  // Inline category rename (T-108): the key being edited + its draft text.
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [orderSaved, setOrderSaved] = useState(false);
  const [announcement, setAnnouncement] = useState("");

  const rawCategories = liveItems.map((i) => itemFieldValue(i, "category") ?? "");
  const canonicalNames = canonicalCategoryNames(rawCategories, normalizeCategoryOrder(categoryOrder));
  const savedKeys = categoryEditorList(rawCategories, categoryOrder).map(categoryKey);
  // A category that appears meanwhile joins a moved order at the end; one that went is dropped.
  const withDraft = (moved: string[] | null) =>
    moved
      ? [...moved.filter((k) => canonicalNames.has(k)), ...savedKeys.filter((k) => !moved.includes(k))]
      : savedKeys;
  const keys = withDraft(draft);
  const orderChanged = keys.some((key, i) => key !== savedKeys[i]);
  const nameOf = (key: string) => canonicalNames.get(key) ?? "";

  function moveRow(from: number, to: number) {
    setDraft((previous) => {
      const next = [...withDraft(previous)];
      [next[from], next[to]] = [next[to], next[from]];
      return next;
    });
    setOrderSaved(false);
  }

  const reorder = useDragReorder(keys, moveRow, {
    onKeyboardMove: (key, index, count) =>
      setAnnouncement(t("listProps.categoryMoved", { category: nameOf(key), position: index + 1, count })),
  });

  async function saveOrder() {
    const names = keys.map(nameOf);
    setSaving(true);
    setError(null);
    try {
      await push({ lists: [{ id: listId, fields: fieldPatch(deviceId, "category_order", names) }] });
      setCategoryOrder(names);
      setDraft(null);
      setOrderSaved(true);
    } catch (err) {
      // The server never got this order (T-266): the moved order stays on screen, unsaved, and
      // Save order stays offered, so trying again is one press.
      setError(errorMessage(t, err, "item.saveFailed"));
    } finally {
      setSaving(false);
    }
  }

  /**
   * The central "fix a category's casing / rename it" action (T-108): rewrites every item in the
   * `fromKey` category to `toRaw` and saves the order shown with the new name in it, as Android
   * does. Renaming onto a different existing category merges them (confirmed first).
   */
  async function renameCategory(fromKey: string, toRaw: string) {
    const to = toRaw.trim();
    setEditingKey(null);
    if (!to || to === nameOf(fromKey)) return; // blank or unchanged
    const toKey = categoryKey(to);
    if (toKey !== fromKey && canonicalNames.has(toKey)) {
      if (!confirm(t("listProps.mergeCategoryConfirm", { category: nameOf(toKey) }))) return;
    }
    const plan = planCategoryRename(
      liveItems.map((i) => ({ id: i.id, category: itemFieldValue(i, "category") ?? "" })),
      keys.map(nameOf),
      fromKey,
      to,
    );
    const previousOrder = categoryOrder;
    const previousDraft = draft;
    const orderDiffers = JSON.stringify(plan.nextCategoryOrder) !== JSON.stringify(categoryOrder);
    setCategoryOrder(plan.nextCategoryOrder);
    setDraft(null);
    setError(null);
    setOrderSaved(false);
    try {
      await push({
        lists: orderDiffers ? [{ id: listId, fields: fieldPatch(deviceId, "category_order", plan.nextCategoryOrder) }] : [],
        items: plan.itemIds.map((itemId) => ({
          id: itemId,
          list_id: listId,
          fields: fieldPatch(deviceId, "category", to),
        })),
      });
    } catch (err) {
      // The server never got it (T-266): back to what was shown, not what it never received.
      setCategoryOrder(previousOrder);
      setDraft(previousDraft);
      setError(errorMessage(t, err, "item.saveFailed"));
    }
  }

  const iconButton = { minWidth: "2.75rem", minHeight: "2.75rem" };

  return (
    <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
      <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("listProps.categories")}</h2>
      <p className="muted" style={{ margin: "0 0 0.6rem", fontSize: "0.85rem" }}>
        {t("listProps.categoriesHelp")}
      </p>
      {keys.length === 0 ? (
        <p className="muted" style={{ margin: 0 }}>
          {t("listProps.noCategories")}
        </p>
      ) : (
        <>
          <div style={{ display: "flex", flexDirection: "column" }}>
            {keys.map((key) => {
              const row = reorder.rowProps(key);
              if (editingKey === key) {
                return (
                  <form
                    key={key}
                    ref={row.ref}
                    onSubmit={(e) => {
                      e.preventDefault();
                      renameCategory(key, renameDraft);
                    }}
                    style={{ display: "flex", alignItems: "center", gap: "0.4rem", padding: "0.2rem 0" }}
                  >
                    <input
                      autoFocus
                      value={renameDraft}
                      onChange={(e) => setRenameDraft(e.target.value)}
                      style={{ flex: 1, minWidth: 0 }}
                      aria-label={t("listProps.renameCategory", { category: nameOf(key) })}
                    />
                    <button type="submit" className="btn">
                      {t("action.save")}
                    </button>
                    <button type="button" className="btn btn-secondary" onClick={() => setEditingKey(null)}>
                      {t("action.cancel")}
                    </button>
                  </form>
                );
              }
              const dragging = reorder.isDragging(key);
              return (
                <div
                  key={key}
                  ref={row.ref}
                  data-testid="category-row"
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: "0.4rem",
                    background: dragging ? "var(--color-bg)" : undefined,
                    boxShadow: dragging ? "var(--shadow)" : undefined,
                    borderRadius: "var(--radius)",
                    ...row.style,
                  }}
                >
                  <span style={{ flex: 1, overflowWrap: "anywhere" }}>{nameOf(key)}</span>
                  <button
                    type="button"
                    className="btn-icon"
                    style={iconButton}
                    onClick={() => {
                      setEditingKey(key);
                      setRenameDraft(nameOf(key));
                    }}
                    aria-label={t("listProps.renameCategory", { category: nameOf(key) })}
                  >
                    ✎
                  </button>
                  <button
                    type="button"
                    className="btn-icon"
                    {...reorder.handleProps(key)}
                    style={{ ...iconButton, touchAction: "none", cursor: dragging ? "grabbing" : "grab", userSelect: "none" }}
                    aria-label={t("listProps.reorderCategory", { category: nameOf(key) })}
                  >
                    ≡
                  </button>
                </div>
              );
            })}
          </div>
          <button
            type="button"
            className="btn"
            style={{ width: "100%", marginTop: "0.6rem" }}
            disabled={!orderChanged || saving}
            onClick={saveOrder}
          >
            {t("listProps.saveOrder")}
          </button>
          {orderSaved && (
            <p className="muted" role="status" style={{ margin: "0.4rem 0 0" }}>
              {t("common.saved")}
            </p>
          )}
        </>
      )}
      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
      <p className="visually-hidden" aria-live="polite">
        {announcement}
      </p>
    </section>
  );
}
