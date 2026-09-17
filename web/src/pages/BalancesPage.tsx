import { useMemo } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { useSyncContext } from "../hooks/SyncContext";
import { itemFieldValue, listFieldValue } from "../hooks/useSync";
import {
  balancesFor,
  expenseTotalCents,
  formerMemberNumbers,
  fromCents,
  sortedExpenses,
} from "../lib/expenses";
import type { Expense } from "../api/contract";
import { useT } from "../i18n";

/** Who is up and who is down on an expenses list (T-155). Always sums to zero. */
export default function BalancesPage() {
  const t = useT();
  const { listId } = useParams<{ listId: string }>();
  const { lists, items } = useSyncContext();

  const list = listId ? lists.get(listId) : undefined;
  const members = useMemo(() => list?.members ?? [], [list]);
  const currency = (list ? listFieldValue(list, "currency") : null) ?? "";

  const expenseItems = useMemo(
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
  const expenses = useMemo(
    () => expenseItems.map((item) => itemFieldValue(item, "expense") as Expense),
    [expenseItems],
  );
  const formerNumbers = useMemo(
    () => formerMemberNumbers(expenseItems, members),
    [expenseItems, members],
  );
  const balances = useMemo(
    () => balancesFor(expenses, members.map((member) => member.account_id)),
    [expenses, members],
  );

  if (!listId) return <Navigate to="/" replace />;
  if (!list) {
    return (
      <main style={{ padding: "1rem" }}>
        <p className="muted">{t("list.notFound")}</p>
        <Link to="/">{t("list.backToOverview")}</Link>
      </main>
    );
  }

  const totalCents = expenses.reduce((sum, expense) => sum + expenseTotalCents(expense), 0);

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <Link to={`/list/${listId}`} className="muted" style={{ fontSize: "0.85rem" }}>
        ← {listFieldValue(list, "name")}
      </Link>
      <h1 style={{ fontSize: "1.3rem", margin: "0.25rem 0 1rem" }}>{t("expense.balances")}</h1>

      <p className="muted" style={{ marginTop: 0 }}>
        {t("expense.totalSpent")}: <strong>{fromCents(totalCents)} {currency}</strong>
      </p>

      <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
        {balances.map((balance) => {
          const member = members.find((m) => m.account_id === balance.accountId);
          const label =
            member?.email ??
            t("expense.formerMember", { number: formerNumbers.get(balance.accountId) ?? 0 });
          return (
            <div
              key={balance.accountId}
              className="card"
              style={{ display: "flex", alignItems: "center", gap: "0.75rem", padding: "0.6rem 0.75rem" }}
            >
              <span aria-hidden="true" style={{ fontWeight: 700, minWidth: "2.2rem" }}>
                {member?.initials ?? "—"}
              </span>
              <span style={{ flex: 1, minWidth: 0 }}>
                <span dir="auto" style={{ display: "block", overflow: "hidden", textOverflow: "ellipsis" }}>
                  {label}
                </span>
                <span className="muted" style={{ fontSize: "0.8rem" }}>
                  {t("expense.paidAndShare", {
                    paid: fromCents(balance.paidCents),
                    share: fromCents(balance.shareCents),
                  })}
                </span>
              </span>
              <strong
                style={{
                  whiteSpace: "nowrap",
                  color:
                    balance.balanceCents === 0
                      ? "var(--color-text-muted)"
                      : balance.balanceCents < 0
                        ? "var(--color-danger)"
                        : "var(--color-accent)",
                }}
              >
                {fromCents(balance.balanceCents)} {currency}
              </strong>
            </div>
          );
        })}
      </div>
    </main>
  );
}
