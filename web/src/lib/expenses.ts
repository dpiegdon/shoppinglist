import type { Expense, ItemObject, ListMember } from "../api/contract";
import { itemFieldValue } from "../hooks/useSync";

/**
 * Expense arithmetic (T-155). Money is handled in whole cents everywhere below: a float would
 * make three-way splits disagree between two devices by a cent, and the server validates that
 * both share maps sum to the same value.
 *
 * Every rule here has a twin in Android's ExpenseMath.kt, and both are tested against the SAME
 * case table, shared-test-cases/expense-arithmetic.json, so they cannot drift.
 */

/** Cents of a wire amount ("12" -> 1200, "12.5" -> 1250). NaN for anything unparseable. */
export function toCents(amount: string): number {
  if (!/^[0-9]+(\.[0-9]{1,2})?$/.test(amount.trim())) return NaN;
  const [whole, fraction = ""] = amount.trim().split(".");
  return Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
}

/** A cent count back to the wire format, always with two decimals ("-2133" -> "-21.33"). */
export function fromCents(cents: number): string {
  const sign = cents < 0 ? "-" : "";
  const absolute = Math.abs(cents);
  return `${sign}${Math.floor(absolute / 100)}.${String(absolute % 100).padStart(2, "0")}`;
}

/** What the user typed for one participant: an amount they fixed, or nothing (auto). */
export interface ShareEntry {
  id: string;
  /** Cents the user typed for this participant; null (or absent) means "share what is left". */
  fixed?: number | null;
}

export type DistributeError =
  | "total_not_positive"
  | "no_participants"
  | "fixed_exceeds_total"
  | "fixed_sum_mismatch";

export type DistributeResult =
  | { ok: true; shares: Record<string, number> }
  | { ok: false; error: DistributeError };

/**
 * Split `totalCents` equally between `ids`, in cents.
 *
 * The leftover cents of an uneven division go one each to the first participants in the order
 * given — deterministic, so every client shows the same numbers. A participant whose share rounds
 * to nothing is left out entirely rather than carried at zero, which is also what the wire format
 * requires.
 */
export function splitEqually(totalCents: number, ids: string[]): Record<string, number> {
  const shares: Record<string, number> = {};
  if (ids.length === 0) return shares;
  const base = Math.floor(totalCents / ids.length);
  let leftover = totalCents - base * ids.length;
  for (const id of ids) {
    const share = base + (leftover > 0 ? 1 : 0);
    if (leftover > 0) leftover -= 1;
    if (share > 0) shares[id] = share;
  }
  return shares;
}

/**
 * The form's whole model: the total is the driver, fixed shares stay as typed, and everything
 * still auto absorbs the difference equally.
 *
 * Two states are refusals rather than silent corrections. Fixed shares above the total cannot be
 * made to add up at all. Fixed shares that are all fixed but miss the total could in principle
 * move the total instead — but a total that changes underneath you while you edit shares is how
 * wrong numbers get saved, so the form asks instead.
 */
export function distribute(totalCents: number, entries: ShareEntry[]): DistributeResult {
  if (!Number.isFinite(totalCents) || totalCents <= 0) return { ok: false, error: "total_not_positive" };
  if (entries.length === 0) return { ok: false, error: "no_participants" };

  const fixed = entries.filter((entry) => entry.fixed != null);
  const auto = entries.filter((entry) => entry.fixed == null);
  const fixedTotal = fixed.reduce((sum, entry) => sum + (entry.fixed ?? 0), 0);
  const remainder = totalCents - fixedTotal;
  if (remainder < 0) return { ok: false, error: "fixed_exceeds_total" };
  if (auto.length === 0 && remainder !== 0) return { ok: false, error: "fixed_sum_mismatch" };

  const shares: Record<string, number> = {};
  for (const entry of fixed) {
    if ((entry.fixed ?? 0) > 0) shares[entry.id] = entry.fixed ?? 0;
  }
  Object.assign(shares, splitEqually(remainder, auto.map((entry) => entry.id)));
  return { ok: true, shares };
}

/** Cents map back to the wire's decimal strings, for pushing. */
export function sharesToWire(shares: Record<string, number>): Record<string, string> {
  return Object.fromEntries(Object.entries(shares).map(([id, cents]) => [id, fromCents(cents)]));
}

/** Total of one share map, in cents. Either map of an expense gives the expense's total. */
export function mapTotalCents(shares: Record<string, string>): number {
  return Object.values(shares).reduce((sum, amount) => sum + toCents(amount), 0);
}

/** The total of an expense: the sum of either map, since the server guarantees they agree. */
export function expenseTotalCents(expense: Expense): number {
  return mapTotalCents(expense.paid_by);
}

export interface Balance {
  accountId: string;
  paidCents: number;
  shareCents: number;
  /** What the list owes them: positive is a credit, negative is a debt. */
  balanceCents: number;
}

/**
 * Per-participant totals over a list's expenses.
 *
 * Everyone named anywhere is included, whether or not they are still a member — their debts and
 * credits do not disappear when they leave, and neither does a member who has not spent anything
 * yet. Largest credit first, then alphabetical by id so the order is stable.
 *
 * The balances always sum to zero, because every expense's two maps sum to the same amount.
 */
export function balancesFor(expenses: Expense[], participantIds: string[]): Balance[] {
  const totals = new Map<string, { paid: number; share: number }>();
  const touch = (id: string) => {
    let entry = totals.get(id);
    if (!entry) {
      entry = { paid: 0, share: 0 };
      totals.set(id, entry);
    }
    return entry;
  };
  for (const id of participantIds) touch(id);
  for (const expense of expenses) {
    for (const [id, amount] of Object.entries(expense.paid_by)) touch(id).paid += toCents(amount);
    for (const [id, amount] of Object.entries(expense.paid_for)) touch(id).share += toCents(amount);
  }
  return [...totals.entries()]
    .map(([accountId, { paid, share }]) => ({
      accountId,
      paidCents: paid,
      shareCents: share,
      balanceCents: paid - share,
    }))
    .sort((a, b) => b.balanceCents - a.balanceCents || a.accountId.localeCompare(b.accountId));
}

/** The expenses of a list's items, newest date first, then newest created. */
export function sortedExpenses(items: ItemObject[]): ItemObject[] {
  return [...items].sort((a, b) => {
    const dateA = itemFieldValue(a, "expense")?.date ?? "";
    const dateB = itemFieldValue(b, "expense")?.date ?? "";
    if (dateA !== dateB) return dateB.localeCompare(dateA);
    return (b.created_at ?? 0) - (a.created_at ?? 0);
  });
}

/**
 * How to label a participant who is no longer a member (T-152): numbered by first appearance
 * across the list's expenses, so two of them stay distinguishable and the number is stable.
 */
export function formerMemberNumbers(expenses: ItemObject[], members: ListMember[]): Map<string, number> {
  const current = new Set(members.map((member) => member.account_id));
  const numbers = new Map<string, number>();
  for (const item of expenses) {
    const expense = itemFieldValue(item, "expense");
    if (!expense) continue;
    for (const id of [...Object.keys(expense.paid_by), ...Object.keys(expense.paid_for)]) {
      if (!current.has(id) && !numbers.has(id)) numbers.set(id, numbers.size + 1);
    }
  }
  return numbers;
}

/** One payment that settling up asks for: `from` pays `to`. */
export interface Transfer {
  from: string;
  to: string;
  cents: number;
}

/**
 * Who pays whom to bring every balance to zero (T-163). Greedy: the largest debtor pays the
 * largest creditor the smaller of the two amounts, whoever reaches zero drops out, repeat. Exact
 * in cents, and at most one transfer fewer than the number of people with a balance. Not always
 * the fewest transfers possible — that problem is NP-hard, and this is what every app in this
 * space shows. Ties in amount go by account id, so both clients list the same transfers in the
 * same order; the shared case table pins that.
 */
export function settle(balances: Pick<Balance, "accountId" | "balanceCents">[]): Transfer[] {
  const debts = new Map<string, number>();
  const credits = new Map<string, number>();
  for (const { accountId, balanceCents } of balances) {
    if (balanceCents < 0) debts.set(accountId, -balanceCents);
    else if (balanceCents > 0) credits.set(accountId, balanceCents);
  }
  // Plain string comparison, not localeCompare: ids are opaque and the order must not depend on
  // the viewer's locale.
  const largest = (side: Map<string, number>): [string, number] | undefined => {
    let best: [string, number] | undefined;
    for (const entry of side) {
      if (!best || entry[1] > best[1] || (entry[1] === best[1] && entry[0] < best[0])) best = entry;
    }
    return best;
  };
  const transfers: Transfer[] = [];
  for (;;) {
    const debtor = largest(debts);
    const creditor = largest(credits);
    if (!debtor || !creditor) break;
    const [debtorId, debt] = debtor;
    const [creditorId, credit] = creditor;
    const cents = Math.min(debt, credit);
    transfers.push({ from: debtorId, to: creditorId, cents });
    if (debt === cents) debts.delete(debtorId);
    else debts.set(debtorId, debt - cents);
    if (credit === cents) credits.delete(creditorId);
    else credits.set(creditorId, credit - cents);
  }
  return transfers;
}
