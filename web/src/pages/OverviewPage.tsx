import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Navigate, Link, useNavigate } from "react-router-dom";
import * as api from "../api/client";
import AddFab from "../components/AddFab";
import { safeLocalStorage } from "../lib/safeStorage";
import { useSyncContext } from "../hooks/SyncContext";
import ServerMessage from "../components/ServerMessage";
import { ModalDialog } from "../components/ModalDialog";
import { fieldPatch, nowMs } from "../hooks/useSync";
import { itemFieldValue, listFieldValue } from "../hooks/useSync";
import { errorMessage } from "../i18n/apiErrors";
import { formatExpiresIn } from "../lib/relativeTime";
import { DEFAULT_LIST_KIND, isExpenses, listKind, listKindIcon, listKindLabelKey } from "../lib/listKind";
import { balancesFor, spentTotals } from "../lib/expenses";
import { IGNORED_INVITES_STORAGE_KEY, LAST_LIST_STORAGE_KEY } from "../lib/storageKeys";
import { useDefaultCurrency } from "../hooks/useDefaultCurrency";
import { useAuth } from "../auth/AuthContext";
import type { Expense, InviteForMe } from "../api/contract";
import type { ListKind } from "../api/contract";
import { useT } from "../i18n";
import { byName } from "../lib/nameOrder";
import { balanceColor, useFormat } from "../lib/format";

// Re-exported for existing importers (ListPropsPage, RedeemPage) — the canonical definitions now
// live in lib/storageKeys.ts (T-272), so AuthContext can clear them on logout without importing a
// page component (and its own useAuth() call, which would cycle back here).
export { IGNORED_INVITES_STORAGE_KEY, LAST_LIST_STORAGE_KEY };

function readIgnoredInvites(): Set<string> {
  try {
    const raw = safeLocalStorage.getItem(IGNORED_INVITES_STORAGE_KEY);
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

function writeIgnoredInvites(ids: Set<string>) {
  safeLocalStorage.setItem(IGNORED_INVITES_STORAGE_KEY, JSON.stringify([...ids]));
}

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
  const t = useT();
  const fmt = useFormat();
  const { lists, items, loading, push, deviceId, lastSyncAt, serverMessage } = useSyncContext();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  // Shopping is preselected so creating a list behaves exactly as it always has (T-110).
  const [newKind, setNewKind] = useState<ListKind>(DEFAULT_LIST_KIND);
  // An expenses list needs a currency up front, and it is fixed afterwards (T-151).
  const defaultCurrency = useDefaultCurrency();
  const [newCurrency, setNewCurrency] = useState("");
  // Create tapped with a blank name, or a ledger with a blank currency (T-307): said inline, as on
  // Android, rather than by the browser's own "required" bubble. Typing clears it.
  const [nameMissing, setNameMissing] = useState(false);
  const [currencyMissing, setCurrencyMissing] = useState(false);
  const { account } = useAuth();
  const [redirectTo, setRedirectTo] = useState<string | null | undefined>(undefined);
  // Invites addressed to this account (T-233). Online only, like the members screen: when the
  // request fails the section is simply absent, and the last good answer stays up meanwhile.
  const [invites, setInvites] = useState<InviteForMe[]>([]);
  const [ignoredInvites, setIgnoredInvites] = useState<Set<string>>(readIgnoredInvites);
  const [joiningInviteId, setJoiningInviteId] = useState<string | null>(null);
  const [inviteError, setInviteError] = useState<string | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const closeCreateDialog = useCallback(() => {
    setCreateError(null);
    setNameMissing(false);
    setCurrencyMissing(false);
    setCreating(false);
  }, []);

  const loadInvites = useCallback(async () => {
    try {
      const response = await api.getPendingInvites();
      setInvites(response.invites);
      // An ignored id the server no longer offers is dead (used, withdrawn or expired): forget it,
      // so the stored set cannot grow without bound.
      setIgnoredInvites((current) => {
        const live = new Set([...current].filter((id) => response.invites.some((invite) => invite.id === id)));
        if (live.size !== current.size) writeIgnoredInvites(live);
        return live;
      });
    } catch {
      // Offline, or a server without the endpoint: nothing to show, nothing to say.
    }
  }, []);

  // Re-checked after every successful sync, so an invite minted while the tab sits on the
  // overview turns up within the sync interval rather than on the next page load.
  useEffect(() => {
    void loadInvites();
  }, [loadInvites, lastSyncAt]);

  function ignoreInvite(inviteId: string) {
    const next = new Set(ignoredInvites).add(inviteId);
    writeIgnoredInvites(next);
    setIgnoredInvites(next);
  }

  async function joinInvite(invite: InviteForMe) {
    setJoiningInviteId(invite.id);
    setInviteError(null);
    try {
      // The same path as a pasted link: redeem, pull the list's full state, open it.
      const { list_id } = await api.redeemInvite(invite.token);
      await push({}, [list_id]);
      safeLocalStorage.setItem(LAST_LIST_STORAGE_KEY, list_id);
      navigate(`/list/${list_id}`);
    } catch (err) {
      setInviteError(errorMessage(t, err, "redeem.error", { invalid_token: "apiError.inviteNotFound" }));
      // A used, withdrawn or expired invite drops out of the section on this reload.
      void loadInvites();
    } finally {
      setJoiningInviteId(null);
    }
  }

  useEffect(() => {
    if (loading) return;
    if (!didInitialResume) {
      didInitialResume = true;
      const lastId = safeLocalStorage.getItem(LAST_LIST_STORAGE_KEY);
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
    const currency = newCurrency.trim();
    const noCurrency = isExpenses(newKind) && !currency;
    if (!name || noCurrency) {
      setNameMissing(!name);
      setCurrencyMissing(noCurrency);
      return;
    }
    const id = crypto.randomUUID();
    setCreateError(null);
    try {
      await push({
        lists: [
          {
            id,
            fields: {
              ...fieldPatch(deviceId, "name", name),
              ...fieldPatch(deviceId, "kind", newKind),
              ...(isExpenses(newKind) ? fieldPatch(deviceId, "currency", currency) : {}),
            },
          },
        ],
      });
    } catch (err) {
      // Keep the dialog open with what was typed, same as the item/expense dialogs (T-266): a
      // rejected push used to be an unhandled rejection here, so the dialog just sat there with
      // nothing said and the new list never created.
      setCreateError(errorMessage(t, err, "item.saveFailed"));
      return;
    }
    setNewName("");
    setNewKind(DEFAULT_LIST_KIND);
    setNewCurrency("");
    setCreating(false);
  }

  // The shared name order (T-176): the same on Android, emoji-led and accented names included.
  const listArray = Array.from(lists.values()).sort(byName((list) => listFieldValue(list, "name") ?? "", (list) => list.id));

  // Open (todo) item count per list, for an at-a-glance "is a trip pending" hint (T-42).
  const openCounts = new Map<string, number>();
  // An expenses list has no open items; what it is worth saying there is what has been spent, and
  // where the signed-in account stands (T-155).
  const expensesByList = new Map<string, Expense[]>();
  for (const item of items.values()) {
    if (itemFieldValue(item, "deleted")) continue;
    const expense = itemFieldValue(item, "expense");
    if (expense) {
      expensesByList.set(item.list_id, [...(expensesByList.get(item.list_id) ?? []), expense]);
      // An expense list's count is its number of expenses (T-191), as the app shows it.
      openCounts.set(item.list_id, (openCounts.get(item.list_id) ?? 0) + 1);
    } else if (itemFieldValue(item, "status") === "todo") {
      openCounts.set(item.list_id, (openCounts.get(item.list_id) ?? 0) + 1);
    }
  }

  function expenseSummary(list: (typeof listArray)[number]) {
    const expenses = expensesByList.get(list.id) ?? [];
    const currency = listFieldValue(list, "currency") ?? "";
    // Net spent, as on the ledger itself: income off it, settlements counting for nothing (T-245).
    const total = spentTotals(expenses).netCents;
    const members = list.members ?? [];
    const mine = balancesFor(expenses, members.map((m) => m.account_id)).find(
      (balance) => balance.accountId === account?.id,
    );
    const showBalance = members.length > 1 && mine;
    return (
      <span style={{ textAlign: "end", fontWeight: 400, fontSize: "0.85rem" }}>
        <span className="muted" style={{ display: "block" }}>
          {(list.closed_at ?? null) !== null && `${t("expense.closed")} · `}
          {fmt.money(total, currency)}
        </span>
        {showBalance && (
          <span style={{ color: balanceColor(mine.balanceCents) }}>
            {fmt.signedMoney(mine.balanceCents, currency)}
          </span>
        )}
      </span>
    );
  }

  const openInvites = invites.filter((invite) => !ignoredInvites.has(invite.id));
  const shelvedInvites = invites.filter((invite) => ignoredInvites.has(invite.id));
  const now = nowMs();

  function inviteCard(invite: InviteForMe, ignored: boolean) {
    const kindLabel = t(listKindLabelKey(invite.list_kind));
    return (
      <div
        key={invite.id}
        className="card"
        style={{ padding: "1rem", display: "flex", alignItems: "center", gap: "0.5rem" }}
      >
        <span aria-label={kindLabel} title={kindLabel}>
          {listKindIcon(invite.list_kind)}
        </span>
        <span style={{ flex: 1, minWidth: 0 }}>
          <span dir="auto" style={{ display: "block", fontWeight: 600 }}>
            {invite.list_name}
          </span>
          <span className="muted" style={{ display: "block", fontSize: "0.85rem" }}>
            {t("overview.invite.from", { initials: invite.invited_by_initials })} ·{" "}
            {formatExpiresIn(invite.expires_at, now, t)}
          </span>
        </span>
        {!ignored && (
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => ignoreInvite(invite.id)}
            disabled={joiningInviteId !== null}
          >
            {t("action.ignore")}
          </button>
        )}
        <button type="button" className="btn" onClick={() => joinInvite(invite)} disabled={joiningInviteId !== null}>
          {t("action.join")}
        </button>
      </div>
    );
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      {/* "Overview", as the menus on both clients call it (T-186); it said "Your lists". */}
      <h1 style={{ fontSize: "1.3rem" }}>{t("nav.overview")}</h1>

      {/* The admin's server message (T-315), above the lists: refreshed by every sync. */}
      <ServerMessage message={serverMessage} />

      {loading && listArray.length === 0 && <p className="muted">{t("common.loading")}</p>}
      {!loading && listArray.length === 0 && <p className="muted">{t("overview.empty")}</p>}

      <div style={{ display: "flex", flexDirection: "column", gap: "0.6rem", marginTop: "1rem" }}>
        {listArray.map((list) => {
          const openCount = openCounts.get(list.id) ?? 0;
          return (
            <Link
              key={list.id}
              to={`/list/${list.id}`}
              className="card"
              onClick={() => safeLocalStorage.setItem(LAST_LIST_STORAGE_KEY, list.id)}
              style={{
                padding: "1rem",
                textDecoration: "none",
                color: "var(--color-text)",
                fontWeight: 600,
                display: "flex",
                alignItems: "center",
                gap: "0.5rem",
              }}
            >
              <span aria-label={t(listKindLabelKey(listKind(list)))} title={t(listKindLabelKey(listKind(list)))}>
                {listKindIcon(listKind(list))}
              </span>
              <span dir="auto" style={{ flex: 1, minWidth: 0 }}>{listFieldValue(list, "name")}</span>
              {isExpenses(listKind(list)) && expenseSummary(list)}
              {/* Every list kind shows its count, as the app does (T-191); beside an expense
                  summary it gets some room. */}
              {openCount > 0 && (
                <span
                  style={{
                    color: "var(--color-accent)",
                    fontWeight: 700,
                    marginInlineStart: isExpenses(listKind(list)) ? "0.5rem" : undefined,
                  }}
                >
                  {openCount}
                </span>
              )}
            </Link>
          );
        })}
      </div>

      {/* Invites waiting for this account (T-233), below the lists so what you have comes first.
          Ignoring is this browser's choice alone: the card moves to the greyed section at the very
          bottom, where Join is still offered, so changing one's mind costs nothing. */}
      {openInvites.length > 0 && (
        <section aria-labelledby="invites-heading" style={{ marginTop: "1.5rem" }}>
          <h2 id="invites-heading" style={{ fontSize: "1rem", margin: "0 0 0.6rem" }}>
            {t("overview.invites")}
          </h2>
          <div style={{ display: "flex", flexDirection: "column", gap: "0.6rem" }}>
            {openInvites.map((invite) => inviteCard(invite, false))}
          </div>
        </section>
      )}
      {inviteError && (
        <p className="error-text" role="alert">
          {inviteError}
        </p>
      )}
      {shelvedInvites.length > 0 && (
        <section aria-labelledby="ignored-invites-heading" style={{ marginTop: "1.5rem", opacity: 0.6 }}>
          <h2 id="ignored-invites-heading" className="muted" style={{ fontSize: "1rem", margin: "0 0 0.6rem" }}>
            {t("overview.invitesIgnored")}
          </h2>
          <div style={{ display: "flex", flexDirection: "column", gap: "0.6rem" }}>
            {shelvedInvites.map((invite) => inviteCard(invite, true))}
          </div>
        </section>
      )}

      <div className="fab-spacer" aria-hidden="true" />
      {/* Bottom right, like Add on every list and like the app's overview (T-174). */}
      <AddFab
        label={t("overview.newList")}
        onClick={() => {
          setCreateError(null);
          setCreating(true);
        }}
      />

      {creating && (
        <ModalDialog as="form" onClose={closeCreateDialog} labelledBy="new-list-title" onSubmit={handleCreate}>
          <h2 id="new-list-title" style={{ marginTop: 0, fontSize: "1.1rem" }}>{t("overview.newList")}</h2>
          {createError && (
            <p className="error-text" role="alert">
              {createError}
            </p>
          )}
          <div className="form-field">
            <label htmlFor="new-list-name">{t("overview.name")}</label>
            <input
              id="new-list-name"
              autoFocus
              aria-required="true"
              aria-invalid={nameMissing ? true : undefined}
              aria-describedby={nameMissing ? "new-list-name-error" : undefined}
              value={newName}
              onChange={(e) => {
                setNewName(e.target.value);
                setNameMissing(false);
              }}
            />
            {nameMissing && (
              <p id="new-list-name-error" className="error-text" role="alert">
                {t("overview.nameRequired")}
              </p>
            )}
          </div>
          {/* Kind is chosen up front (T-110) but isn't permanent for shopping/checklist — it can
              be changed later in list properties, and converting never touches item data. An
              expenses list is the one exception: its kind is fixed for its whole life (T-151). */}
          <fieldset style={{ border: "none", padding: 0, margin: "0 0 0.75rem" }}>
            <legend className="muted" style={{ fontSize: "0.85rem", padding: 0 }}>
              {t("overview.type")}
            </legend>
            {(["shopping", "checklist", "expenses"] as const).map((kind) => (
              <label key={kind} style={{ display: "flex", alignItems: "center", gap: "0.5rem", padding: "0.15rem 0" }}>
                <input
                  type="radio"
                  name="new-list-kind"
                  value={kind}
                  checked={newKind === kind}
                  onChange={() => {
                    setNewKind(kind);
                    // Prefilled rather than placeheld: the field is required, and the account's
                    // own currency is the answer nearly every time.
                    if (kind === "expenses") setNewCurrency((current) => current || defaultCurrency);
                  }}
                />
                <span>
                  {listKindIcon(kind)} {t(listKindLabelKey(kind))}
                </span>
              </label>
            ))}
            <p className="muted" style={{ margin: "0.25rem 0 0", fontSize: "0.8rem" }}>
              {isExpenses(newKind)
                ? t("overview.kind.expenses")
                : newKind === "checklist"
                  ? t("overview.kind.checklist")
                  : t("overview.kind.shopping")}
            </p>
          </fieldset>
          {isExpenses(newKind) && (
            <div className="form-field">
              <label htmlFor="new-list-currency">{t("expense.currency")}</label>
              <input
                id="new-list-currency"
                aria-required="true"
                aria-invalid={currencyMissing ? true : undefined}
                aria-describedby={currencyMissing ? "new-list-currency-error" : undefined}
                list="currency-suggestions"
                value={newCurrency}
                onChange={(e) => {
                  setNewCurrency(e.target.value);
                  setCurrencyMissing(false);
                }}
              />
              {currencyMissing && (
                <p id="new-list-currency-error" className="error-text" role="alert">
                  {t("overview.currencyRequired")}
                </p>
              )}
              {/* Suggestions, not a constraint: the server takes free text, so a list can be
                  kept in pizza slices if that is what the group settles in. */}
              <datalist id="currency-suggestions">
                {[defaultCurrency, "EUR", "USD", "GBP", "CHF", "JPY"]
                  .filter((code, index, all) => code && all.indexOf(code) === index)
                  .map((code) => (
                    <option key={code} value={code} />
                  ))}
              </datalist>
              <p className="muted" style={{ margin: "0.25rem 0 0", fontSize: "0.8rem" }}>
                {t("overview.currencyHelp")}
              </p>
            </div>
          )}
          <div style={{ display: "flex", gap: "0.5rem", justifyContent: "flex-end" }}>
            <button type="button" className="btn btn-secondary" onClick={closeCreateDialog}>
              {t("action.cancel")}
            </button>
            <button type="submit" className="btn">
              {t("action.create")}
            </button>
          </div>
        </ModalDialog>
      )}
    </main>
  );
}
