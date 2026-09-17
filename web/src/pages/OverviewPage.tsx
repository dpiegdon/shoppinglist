import { useEffect, useState, type FormEvent } from "react";
import { Navigate, Link } from "react-router-dom";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch } from "../hooks/useSync";
import { itemFieldValue, listFieldValue } from "../hooks/useSync";
import { DEFAULT_LIST_KIND, isExpenses, listKind, listKindIcon, listKindLabelKey } from "../lib/listKind";
import { balancesFor, expenseTotalCents, fromCents } from "../lib/expenses";
import { useDefaultCurrency } from "../hooks/useDefaultCurrency";
import { useAuth } from "../auth/AuthContext";
import type { Expense } from "../api/contract";
import type { ListKind } from "../api/contract";
import { useT } from "../i18n";

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
  const t = useT();
  const { lists, items, loading, push, deviceId } = useSyncContext();
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  // Shopping is preselected so creating a list behaves exactly as it always has (T-110).
  const [newKind, setNewKind] = useState<ListKind>(DEFAULT_LIST_KIND);
  // An expenses list needs a currency up front, and it is fixed afterwards (T-151).
  const defaultCurrency = useDefaultCurrency();
  const [newCurrency, setNewCurrency] = useState("");
  const { account } = useAuth();
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
    const currency = (newCurrency.trim() || defaultCurrency).trim();
    if (isExpenses(newKind) && !currency) return;
    const id = crypto.randomUUID();
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
    setNewName("");
    setNewKind(DEFAULT_LIST_KIND);
    setNewCurrency("");
    setCreating(false);
  }

  const listArray = Array.from(lists.values()).sort((a, b) =>
    (listFieldValue(a, "name") ?? "").localeCompare(listFieldValue(b, "name") ?? ""),
  );

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
    } else if (itemFieldValue(item, "status") === "todo") {
      openCounts.set(item.list_id, (openCounts.get(item.list_id) ?? 0) + 1);
    }
  }

  function expenseSummary(list: (typeof listArray)[number]) {
    const expenses = expensesByList.get(list.id) ?? [];
    const currency = listFieldValue(list, "currency") ?? "";
    const total = expenses.reduce((sum, expense) => sum + expenseTotalCents(expense), 0);
    const members = list.members ?? [];
    const mine = balancesFor(expenses, members.map((m) => m.account_id)).find(
      (balance) => balance.accountId === account?.id,
    );
    const showBalance = members.length > 1 && mine;
    return (
      <span style={{ textAlign: "end", fontWeight: 400, fontSize: "0.85rem" }}>
        <span className="muted" style={{ display: "block" }}>
          {(list.closed_at ?? null) !== null && `${t("expense.closed")} · `}
          {fromCents(total)} {currency}
        </span>
        {showBalance && (
          <span style={{ color: mine.balanceCents < 0 ? "var(--color-danger)" : "var(--color-accent)" }}>
            {fromCents(mine.balanceCents)} {currency}
          </span>
        )}
      </span>
    );
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ fontSize: "1.3rem" }}>{t("overview.title")}</h1>
        <button type="button" className="btn" onClick={() => setCreating(true)}>
          {t("overview.newListButton")}
        </button>
      </div>

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
              onClick={() => localStorage.setItem(LAST_LIST_STORAGE_KEY, list.id)}
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
              {isExpenses(listKind(list))
                ? expenseSummary(list)
                : openCount > 0 && (
                    <span style={{ color: "var(--color-accent)", fontWeight: 700 }}>{openCount}</span>
                  )}
            </Link>
          );
        })}
      </div>

      {creating && (
        <div className="dialog-overlay" onClick={() => setCreating(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={handleCreate}>
            <h2 style={{ marginTop: 0, fontSize: "1.1rem" }}>{t("overview.newList")}</h2>
            <div className="form-field">
              <label htmlFor="new-list-name">{t("overview.name")}</label>
              <input
                id="new-list-name"
                autoFocus
                required
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
              />
            </div>
            {/* Kind is chosen up front (T-110) but isn't permanent — it can be changed later in
                list properties, and converting never touches item data. */}
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
                  required
                  list="currency-suggestions"
                  value={newCurrency}
                  onChange={(e) => setNewCurrency(e.target.value)}
                />
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
              <button type="button" className="btn btn-secondary" onClick={() => setCreating(false)}>
                {t("action.cancel")}
              </button>
              <button type="submit" className="btn">
                {t("action.create")}
              </button>
            </div>
          </form>
        </div>
      )}
    </main>
  );
}
