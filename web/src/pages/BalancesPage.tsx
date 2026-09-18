import { useMemo, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import ExpenseDialog, { today, type ExpenseSaveValues } from "../components/ExpenseDialog";
import ExpenseListHeader from "../components/ExpenseListHeader";
import { useAuth } from "../auth/AuthContext";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue, nowMs } from "../hooks/useSync";
import {
  balancesFor,
  expenseTotalCents,
  formerMemberNumbers,
  fromCents,
  settle,
  sortedExpenses,
  type Transfer,
} from "../lib/expenses";
import type { Expense } from "../api/contract";
import { useT } from "../i18n";

/**
 * Who is up and who is down on an expenses list (T-155), always summing to zero — and below it,
 * who should pay whom to make it so (T-164). Reimburse on a transfer opens the ordinary expense form
 * pre-filled; what it saves is an ordinary expense, so nothing on this screen is stored or synced.
 */
export default function BalancesPage() {
  const t = useT();
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId } = useSyncContext();
  const { account } = useAuth();
  const [recording, setRecording] = useState<Transfer | null>(null);

  const list = listId ? lists.get(listId) : undefined;
  const members = useMemo(() => list?.members ?? [], [list]);
  const currency = (list ? listFieldValue(list, "currency") : null) ?? "";
  const closeVotes = useMemo(() => list?.close_votes ?? [], [list]);
  const closedAt = list?.closed_at ?? null;

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
  const transfers = useMemo(() => settle(balances), [balances]);

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
  const currentIds = new Set(members.map((member) => member.account_id));

  function labelFor(id: string): string {
    const member = members.find((m) => m.account_id === id);
    return member?.email ?? t("expense.formerMember", { number: formerNumbers.get(id) ?? 0 });
  }

  /**
   * Record is offered only where it can succeed: the list is open, both parties are current
   * members, and neither has agreed to close — the freeze rule would refuse the expense otherwise.
   * Everywhere else the row is display only, which on a closed list is the archive of what was owed.
   */
  function canRecord(transfer: Transfer): boolean {
    return (
      closedAt === null &&
      !!account &&
      currentIds.has(transfer.from) &&
      currentIds.has(transfer.to) &&
      !closeVotes.includes(transfer.from) &&
      !closeVotes.includes(transfer.to)
    );
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

  // One person cannot owe themselves, and with no expenses there is nothing to say either way.
  const showSettle = balances.length > 1 && expenses.length > 0;

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <ExpenseListHeader listId={listId} listName={listFieldValue(list, "name") ?? ""} view="balances" />

      <p className="muted" style={{ marginTop: 0 }}>
        {t("expense.totalSpent")}: <strong>{fromCents(totalCents)} {currency}</strong>
      </p>

      <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
        {balances.map((balance) => {
          const member = members.find((m) => m.account_id === balance.accountId);
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
                  {labelFor(balance.accountId)}
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

      {showSettle && (
        <section aria-labelledby="settle-up" style={{ marginTop: "1.25rem" }}>
          <h2 id="settle-up" style={{ fontSize: "1rem", margin: "0 0 0.5rem" }}>
            {t("expense.settleUp")}
          </h2>
          {transfers.length === 0 ? (
            <p className="muted" style={{ margin: 0 }}>
              {t("expense.allSettled")}
            </p>
          ) : (
            <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
              {transfers.map((transfer, index) => (
                <div
                  key={`${index}-${transfer.from}-${transfer.to}`}
                  className="card"
                  style={{ display: "flex", alignItems: "center", gap: "0.75rem", padding: "0.6rem 0.75rem" }}
                >
                  <span dir="auto" style={{ flex: 1, minWidth: 0 }}>
                    {t("expense.transfer", { from: labelFor(transfer.from), to: labelFor(transfer.to) })}
                  </span>
                  <strong style={{ whiteSpace: "nowrap" }}>
                    {fromCents(transfer.cents)} {currency}
                  </strong>
                  {canRecord(transfer) && (
                    <button type="button" className="btn btn-secondary" onClick={() => setRecording(transfer)}>
                      {t("expense.reimburse")}
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </section>
      )}

      {recording && account && (
        <ExpenseDialog
          members={members}
          closeVotes={closeVotes}
          currency={currency}
          myAccountId={account.id}
          prefill={{
            name: t("expense.settlement"),
            // Equal split of one on each side: the total drives the amounts, so editing it is how
            // a partial settlement works.
            expense: {
              paid_by: { [recording.from]: fromCents(recording.cents) },
              equal_by: true,
              paid_for: { [recording.to]: fromCents(recording.cents) },
              equal_for: true,
              date: today(),
            },
          }}
          onClose={() => setRecording(null)}
          onSave={handleSave}
        />
      )}
    </main>
  );
}
