import { useT } from "../i18n";
import { balanceColor, useFormat } from "../lib/format";

interface LedgerSummaryProps {
  currency: string;
  /** What the ledger has spent, income taken off it and settlements counting for nothing (T-245). */
  netCents: number;
  /** The signed-in member's balance, or null where there is none worth showing. */
  myBalanceCents?: number | null;
  /** What went out and what came in, where income exists: the net alone hides half the story. */
  breakdown?: { expensesCents: number; incomeCents: number } | null;
}

/**
 * The figures at the top of both tabs of a ledger, in one style on both (T-342): a card, a muted
 * caption over each figure. Android's LedgerSummaryCard draws the same.
 */
export default function LedgerSummary({ currency, netCents, myBalanceCents, breakdown }: LedgerSummaryProps) {
  const t = useT();
  const fmt = useFormat();
  return (
    <div className="card" style={{ padding: "0.75rem 1rem", margin: "0 0 0.75rem" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.75rem" }}>
        <span>
          <span className="muted" style={{ fontSize: "0.8rem", display: "block" }}>
            {t("expense.totalSpent")}
          </span>
          {/* Never coloured: what a group spent is not a position anyone is up or down (T-245). */}
          <strong>{fmt.money(netCents, currency)}</strong>
        </span>
        {myBalanceCents != null && (
          <span style={{ textAlign: "end" }}>
            <span className="muted" style={{ fontSize: "0.8rem", display: "block" }}>
              {t("expense.yourBalance")}
            </span>
            <strong style={{ color: balanceColor(myBalanceCents) }}>
              {fmt.signedMoney(myBalanceCents, currency)}
            </strong>
          </span>
        )}
      </div>
      {breakdown && breakdown.incomeCents !== 0 && (
        <p className="muted" style={{ margin: "0.25rem 0 0", fontSize: "0.8rem" }}>
          {t("expense.spentBreakdown", {
            spent: fmt.money(breakdown.expensesCents, currency),
            income: fmt.money(breakdown.incomeCents, currency),
          })}
        </p>
      )}
    </div>
  );
}
