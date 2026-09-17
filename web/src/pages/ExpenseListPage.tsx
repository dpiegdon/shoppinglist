import { useMemo, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import ExpenseDialog, { type ExpenseSaveValues } from "../components/ExpenseDialog";
import { useAuth } from "../auth/AuthContext";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue, nowMs } from "../hooks/useSync";
import { useLiveListSync } from "../hooks/useLiveListSync";
import {
  balancesFor,
  expenseTotalCents,
  formerMemberNumbers,
  fromCents,
  sortedExpenses,
} from "../lib/expenses";
import type { Expense, ItemObject } from "../api/contract";
import { useT } from "../i18n";

/**
 * An expenses list (T-155): who paid what, for whom. None of the shopping apparatus applies —
 * no statuses, categories, backlog or stores — so this is a screen of its own rather than a
 * branch inside ListPage.
 */
export default function ExpenseListPage() {
  const t = useT();
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId } = useSyncContext();
  const { account } = useAuth();
  const [dialogItem, setDialogItem] = useState<ItemObject | "new" | null>(null);

  useLiveListSync();

  const list = listId ? lists.get(listId) : undefined;
  const members = useMemo(() => list?.members ?? [], [list]);
  const currency = (list ? listFieldValue(list, "currency") : null) ?? "";

  const expenses = useMemo(
    () =>
      sortedExpenses(
        Array.from(items.values()).filter(
          (item) =>
            item.list_id === listId &&
            !itemFieldValue(item, "deleted") &&
            itemFieldValue(item, "expense"),
        ),
      ),
    [items, listId],
  );

  const formerNumbers = useMemo(() => formerMemberNumbers(expenses, members), [expenses, members]);
  const balances = useMemo(
    () =>
      balancesFor(
        expenses.map((item) => itemFieldValue(item, "expense") as Expense),
        members.map((member) => member.account_id),
      ),
    [expenses, members],
  );

  if (!listId) return <Navigate to="/" replace />;
  // Not a redirect: `list` is undefined on the first render of every visit (T-146).
  if (!list) {
    return (
      <main style={{ padding: "1rem" }}>
        <p className="muted">{t("list.notFound")}</p>
        <Link to="/">{t("list.backToOverview")}</Link>
      </main>
    );
  }

  const totalCents = expenses.reduce(
    (sum, item) => sum + expenseTotalCents(itemFieldValue(item, "expense") as Expense),
    0,
  );
  const myBalance = balances.find((balance) => balance.accountId === account?.id);

  function nameOf(id: string): string {
    const member = members.find((m) => m.account_id === id);
    if (member) return member.initials;
    return t("expense.formerMember", { number: formerNumbers.get(id) ?? 0 });
  }

  /** "for everyone" beats naming every member, which is the usual case and the longest string. */
  function forWhom(expense: Expense): string {
    const ids = Object.keys(expense.paid_for);
    const everyone =
      members.length > 0 &&
      ids.length === members.length &&
      members.every((member) => ids.includes(member.account_id));
    return everyone ? t("expense.forEveryone") : ids.map(nameOf).join(", ");
  }

  async function handleSave(values: ExpenseSaveValues) {
    await push({
      items: [
        {
          id: values.itemId,
          list_id: listId!,
          created_at: nowMs(),
          fields: {
            ...fieldPatch(deviceId, "name", values.name),
            ...fieldPatch(deviceId, "note", values.note || null),
            ...fieldPatch(deviceId, "expense", values.expense),
          },
        },
      ],
    });
  }

  async function handleDelete(itemId: string) {
    await push({
      items: [
        { id: itemId, list_id: listId!, fields: fieldPatch(deviceId, "deleted", true) },
      ],
    });
  }

  const byDate = new Map<string, ItemObject[]>();
  for (const item of expenses) {
    const date = (itemFieldValue(item, "expense") as Expense).date;
    byDate.set(date, [...(byDate.get(date) ?? []), item]);
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <Link to="/" className="muted" style={{ fontSize: "0.85rem" }}>
        {t("list.allListsLink")}
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
        <Link to="/" style={{ color: "inherit", textDecoration: "none" }}>
          {listFieldValue(list, "name")}
        </Link>
      </h1>

      <Link
        to={`/list/${listId}/balances`}
        className="card"
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          padding: "0.75rem 1rem",
          margin: "0.75rem 0",
          textDecoration: "none",
          color: "var(--color-text)",
        }}
      >
        <span>
          <span className="muted" style={{ fontSize: "0.8rem", display: "block" }}>
            {t("expense.totalSpent")}
          </span>
          <strong>
            {fromCents(totalCents)} {currency}
          </strong>
        </span>
        {members.length > 1 && myBalance && (
          <span style={{ textAlign: "end" }}>
            <span className="muted" style={{ fontSize: "0.8rem", display: "block" }}>
              {t("expense.yourBalance")}
            </span>
            <strong style={{ color: myBalance.balanceCents < 0 ? "var(--color-danger)" : "var(--color-accent)" }}>
              {fromCents(myBalance.balanceCents)} {currency}
            </strong>
          </span>
        )}
      </Link>

      <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: "0.5rem" }}>
        <Link
          to={`/list/${listId}/properties`}
          className="btn-icon"
          aria-label={t("listProps.title")}
          title={t("listProps.title")}
        >
          ⚙
        </Link>
      </div>

      <button
        type="button"
        className="btn"
        style={{ width: "100%", marginBottom: "0.75rem" }}
        onClick={() => setDialogItem("new")}
      >
        {t("expense.add")}
      </button>

      {expenses.length === 0 && <p className="muted">{t("expense.empty")}</p>}

      {[...byDate.entries()].map(([date, rows]) => (
        <section key={date} style={{ marginBottom: "1rem" }}>
          <h2 className="muted" style={{ fontSize: "0.8rem", margin: "0 0 0.3rem" }}>
            {date}
          </h2>
          <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
            {rows.map((item) => {
              const expense = itemFieldValue(item, "expense") as Expense;
              return (
                <button
                  key={item.id}
                  type="button"
                  className="card"
                  onClick={() => setDialogItem(item)}
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    gap: "0.75rem",
                    padding: "0.6rem 0.75rem",
                    textAlign: "start",
                    border: "1px solid var(--color-border)",
                    color: "var(--color-text)",
                  }}
                >
                  <span style={{ minWidth: 0 }}>
                    <span dir="auto" style={{ display: "block" }}>
                      {itemFieldValue(item, "name")}
                    </span>
                    <span className="muted" style={{ fontSize: "0.8rem" }}>
                      {t("expense.rowBy", {
                        by: Object.keys(expense.paid_by).map(nameOf).join(", "),
                        for: forWhom(expense),
                      })}
                    </span>
                  </span>
                  <strong style={{ whiteSpace: "nowrap" }}>
                    {fromCents(expenseTotalCents(expense))} {currency}
                  </strong>
                </button>
              );
            })}
          </div>
        </section>
      ))}

      {dialogItem && account && (
        <ExpenseDialog
          members={members}
          currency={currency}
          myAccountId={account.id}
          editingItem={dialogItem === "new" ? undefined : dialogItem}
          onClose={() => setDialogItem(null)}
          onSave={handleSave}
          onDelete={handleDelete}
        />
      )}
    </main>
  );
}
