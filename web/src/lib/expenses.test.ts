import { describe, expect, it } from "vitest";
import cases from "../../../shared-test-cases/expense-arithmetic.json";
import {
  balancesFor,
  distribute,
  expenseTotalCents,
  formerMemberNumbers,
  fromCents,
  settle,
  sharesToWire,
  splitEqually,
  toCents,
  type ShareEntry,
} from "./expenses";
import type { Expense, ItemObject, ListMember } from "../api/contract";

/**
 * The case table is shared with Android's ExpenseMathTest (T-148's lesson: one copy, not two), so
 * these tests are mostly a driver over it. Cases that are genuinely web-only — the helpers the
 * pages use — are written out below the table-driven blocks.
 */

describe("cents conversion", () => {
  it.each(cases.to_cents)("$amount is $cents cents", ({ amount, cents }) => {
    expect(toCents(amount)).toBe(cents);
  });

  it.each(cases.from_cents)("$cents cents renders as $amount", ({ cents, amount }) => {
    expect(fromCents(cents)).toBe(amount);
  });

  it("rejects anything the wire format would not accept", () => {
    for (const bad of ["", "1.234", "1,50", "-1", "abc", "٥"]) {
      expect(toCents(bad)).toBeNaN();
    }
  });

  it("renders a negative balance with its sign", () => {
    expect(fromCents(-2133)).toBe("-21.33");
  });
});

describe("equal split", () => {
  it.each(cases.equal_split)("$name", ({ total, participants, expect: expected }) => {
    const shares = splitEqually(toCents(total), participants);
    expect(sharesToWire(shares)).toEqual(expected);
  });
});

describe("distribute", () => {
  it.each(cases.distribute)("$name", (testCase) => {
    const entries: ShareEntry[] = testCase.entries.map((entry) => ({
      id: entry.id,
      fixed: "fixed" in entry && entry.fixed != null ? toCents(entry.fixed) : null,
    }));
    const result = distribute(toCents(testCase.total), entries);

    if ("error" in testCase && testCase.error) {
      expect(result).toEqual({ ok: false, error: testCase.error });
    } else {
      expect(result.ok).toBe(true);
      if (result.ok) expect(sharesToWire(result.shares)).toEqual(testCase.expect);
    }
  });

  it("never loses or invents a cent, whatever the split", () => {
    for (let total = 1; total <= 400; total += 1) {
      for (let people = 1; people <= 7; people += 1) {
        const ids = Array.from({ length: people }, (_, index) => `p${index}`);
        const result = distribute(total, ids.map((id) => ({ id, fixed: null })));
        expect(result.ok).toBe(true);
        if (!result.ok) continue;
        const sum = Object.values(result.shares).reduce((a, b) => a + b, 0);
        expect(sum).toBe(total);
        expect(Object.values(result.shares).every((cents) => cents > 0)).toBe(true);
      }
    }
  });
});

describe("balances", () => {
  it.each(cases.balances)("$name", (testCase) => {
    const expenses = testCase.expenses as unknown as Expense[];
    const balances = balancesFor(expenses, testCase.participants);

    const rendered = Object.fromEntries(
      balances.map((balance) => [
        balance.accountId,
        {
          paid: fromCents(balance.paidCents),
          share: fromCents(balance.shareCents),
          balance: fromCents(balance.balanceCents),
        },
      ]),
    );
    expect(rendered).toEqual(testCase.expect);

    // The property that makes the screen trustworthy: nothing is owed to nobody.
    expect(balances.reduce((sum, balance) => sum + balance.balanceCents, 0)).toBe(0);
    expect(expenses.reduce((sum, expense) => sum + expenseTotalCents(expense), 0)).toBe(
      toCents(testCase.total),
    );
  });

  it("puts the largest credit first and the deepest debt last", () => {
    const expense = (paidBy: string, amount: string): Expense => ({
      paid_by: { [paidBy]: amount },
      equal_by: false,
      paid_for: { a: "10.00", b: "10.00", c: "10.00" },
      equal_for: true,
      date: "2026-09-17",
    });

    const balances = balancesFor([expense("a", "30.00"), expense("b", "30.00")], ["a", "b", "c"]);
    expect(balances.map((balance) => balance.accountId)).toEqual(["a", "b", "c"]);
    expect(balances.map((balance) => balance.balanceCents)).toEqual([1000, 1000, -2000]);
  });
});

// ---- helpers the pages use --------------------------------------------------

function expenseItem(id: string, date: string, participants: string[], createdAt = 0): ItemObject {
  const clock = { updated_at: 1, updated_by: "dev" };
  const shares = Object.fromEntries(participants.map((p) => [p, "1.00"]));
  return {
    id,
    list_id: "list-1",
    created_at: createdAt,
    fields: {
      name: { value: id, ...clock },
      expense: {
        value: {
          paid_by: { [participants[0]]: fromCents(participants.length * 100) },
          equal_by: false,
          paid_for: shares,
          equal_for: true,
          date,
        },
        ...clock,
      },
    },
  };
}

describe("former members", () => {
  const members: ListMember[] = [{ account_id: "a", email: "a@example.com", initials: "A" }];

  it("numbers everyone who is no longer a member, by first appearance", () => {
    const items = [
      expenseItem("e1", "2026-09-01", ["a", "gone-two"]),
      expenseItem("e2", "2026-09-02", ["a", "gone-one"]),
    ];

    // e2 is the newer expense and comes first on screen, so its stranger is Former member 1.
    const numbers = formerMemberNumbers([items[1], items[0]], members);
    expect(numbers.get("gone-one")).toBe(1);
    expect(numbers.get("gone-two")).toBe(2);
    expect(numbers.has("a")).toBe(false);
  });

  it("is empty when everyone involved is still on the list", () => {
    expect(formerMemberNumbers([expenseItem("e1", "2026-09-01", ["a"])], members).size).toBe(0);
  });
});

describe("settling up", () => {
  // Balances in the table are signed; the wire amount format is not, so the sign is peeled off.
  const signedCents = (amount: string) =>
    amount.startsWith("-") ? -toCents(amount.slice(1)) : toCents(amount);
  const balancesOf = (testCase: { balances: Record<string, string> }) =>
    Object.entries(testCase.balances).map(([accountId, amount]) => ({
      accountId,
      balanceCents: signedCents(amount),
    }));

  it.each(cases.settle)("$name", (testCase) => {
    const transfers = settle(balancesOf(testCase)).map((t) => ({
      from: t.from,
      to: t.to,
      amount: fromCents(t.cents),
    }));
    expect(transfers).toEqual(testCase.expect);
  });

  it.each(cases.settle)("$name — zeroes every balance in at most n-1 positive transfers", (testCase) => {
    const balances = balancesOf(testCase);
    const remaining = new Map(balances.map((b) => [b.accountId, b.balanceCents]));
    const transfers = settle(balances);
    for (const t of transfers) {
      expect(t.cents).toBeGreaterThan(0);
      remaining.set(t.from, (remaining.get(t.from) ?? 0) + t.cents);
      remaining.set(t.to, (remaining.get(t.to) ?? 0) - t.cents);
    }
    expect([...remaining.values()].every((cents) => cents === 0)).toBe(true);
    const withBalance = balances.filter((b) => b.balanceCents !== 0).length;
    expect(transfers.length).toBeLessThanOrEqual(Math.max(withBalance - 1, 0));
  });
});
