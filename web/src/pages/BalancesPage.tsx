import { useMemo, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import ExpenseDialog, { today, type ExpenseSaveValues } from "../components/ExpenseDialog";
import ExpenseListHeader from "../components/ExpenseListHeader";
import LedgerSummary from "../components/LedgerSummary";
import { useAuth } from "../auth/AuthContext";
import { useSyncContext } from "../hooks/SyncContext";
import { fieldPatch, itemFieldValue, listFieldValue, nowMs } from "../hooks/useSync";
import {
  balancesFor,
  formerMemberNumbers,
  fromCents,
  settle,
  sortedExpenses,
  spentTotals,
  type Transfer,
} from "../lib/expenses";
import type { Expense } from "../api/contract";
import { useT } from "../i18n";
import { balanceColor, useFormat } from "../lib/format";
import { bidiIsolate } from "../lib/bidi";

/**
 * Who is up and who is down on an expenses list (T-155), always summing to zero — and below it,
 * who should pay whom to make it so (T-164). Reimburse on a transfer opens the ordinary expense form
 * pre-filled; what it saves is an ordinary expense, so nothing on this screen is stored or synced.
 */
export default function BalancesPage() {
  const t = useT();
  const fmt = useFormat();
  const { listId } = useParams<{ listId: string }>();
  const { lists, items, push, deviceId, loading } = useSyncContext();
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
  // Not "not found" while the first sync is still in flight: the web keeps no copy of the lists,
  // so a reload has none until it lands (T-342), as list properties already does.
  if (!list) {
    return (
      <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
        <p className="muted">{loading ? t("common.loading") : t("list.notFound")}</p>
        {!loading && <Link to="/">{t("list.backToOverview")}</Link>}
      </main>
    );
  }

  const spent = spentTotals(expenses);
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
      // Reimburse adds an expense, which someone who has agreed to close may not do (T-192).
      !closeVotes.includes(account.id) &&
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

      {/* The same card as the entries tab (T-342). Where income exists, the net alone hides half
          the story: it says what went out and what came in (T-245). */}
      <LedgerSummary
        currency={currency}
        netCents={spent.netCents}
        breakdown={{ expensesCents: spent.expensesCents, incomeCents: spent.incomeCents }}
      />

      <div className="rows">
        {balances.map((balance) => {
          const member = members.find((m) => m.account_id === balance.accountId);
          return (
            <div
              key={balance.accountId}
              className="row"
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
                  {/* Settled is what transfers moved, sent minus received — signed, because
                      which way it went is the whole of what it says, and left uncoloured: only
                      the balance itself is a position (T-245). Absent when nothing was settled,
                      which is every ledger that has not been paid back yet. */}
                  {balance.settledCents === 0
                    ? t("expense.paidAndShare", {
                        paid: fmt.number(balance.paidCents),
                        share: fmt.number(balance.shareCents),
                      })
                    : t("expense.paidShareSettled", {
                        paid: fmt.number(balance.paidCents),
                        share: fmt.number(balance.shareCents),
                        settled: fmt.signedNumber(balance.settledCents),
                      })}
                </span>
              </span>
              <strong
                style={{
                  whiteSpace: "nowrap",
                  color: balanceColor(balance.balanceCents),
                }}
              >
                {fmt.signedMoney(balance.balanceCents, currency)}
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
            <div className="rows">
              {transfers.map((transfer, index) => (
                <div
                  key={`${index}-${transfer.from}-${transfer.to}`}
                  className="row"
                  style={{ display: "flex", alignItems: "center", gap: "0.75rem", padding: "0.6rem 0.75rem" }}
                >
                  {/* The names isolated and the sentence in the page's direction, so it reads from
                      payer to payee in Arabic too, whatever script the names are in (T-342). */}
                  <span style={{ flex: 1, minWidth: 0 }}>
                    {t("expense.transfer", {
                      from: bidiIsolate(labelFor(transfer.from)),
                      to: bidiIsolate(labelFor(transfer.to)),
                    })}
                  </span>
                  <strong style={{ whiteSpace: "nowrap" }}>
                    {fmt.money(transfer.cents, currency)}
                  </strong>
                  {canRecord(transfer) && (
                    <button type="button" className="btn" onClick={() => setRecording(transfer)}>
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
          formerNumbers={formerNumbers}
          prefill={{
            name: t("expense.settlement"),
            // A settlement is a transfer (T-245): the debtor hands the creditor money, and the
            // ledger's net spending does not move. The total drives the amounts, so editing it is
            // how a partial settlement works.
            expense: {
              type: "transfer",
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
