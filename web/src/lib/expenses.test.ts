import { describe, expect, it } from "vitest";
import cases from "../../../shared-test-cases/expense-arithmetic.json";
import {
  aliasesOf,
  balancesFor,
  fold,
  frozenReasonOf,
  resolveParticipant,
  rosterIds,
  distribute,
  entryEffectCents,
  entryType,
  expenseTotalCents,
  formerMemberNumbers,
  fromCents,
  settle,
  sharesToWire,
  splitEqually,
  spentTotals,
  toCents,
  type ShareEntry,
} from "./expenses";
import type { Expense, ItemObject, ListGuest, ListMember } from "../api/contract";

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

  it.each(cases.to_cents_invalid)("%s is not a number", (bad) => {
    expect(toCents(bad)).toBeNaN();
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
          settled: fromCents(balance.settledCents),
          balance: fromCents(balance.balanceCents),
        },
      ]),
    );
    expect(rendered).toEqual(testCase.expect);
    // The table lists people in the order balancesFor must return them: largest credit first,
    // then by plain id comparison rather than the viewer's locale (T-204).
    expect(balances.map((balance) => balance.accountId)).toEqual(Object.keys(testCase.expect));

    // The property that makes the screen trustworthy: nothing is owed to nobody.
    expect(balances.reduce((sum, balance) => sum + balance.balanceCents, 0)).toBe(0);
    expect(expenses.reduce((sum, expense) => sum + expenseTotalCents(expense), 0)).toBe(
      toCents(testCase.total),
    );
    // The three figures are the balance broken down, whatever mix of types produced it.
    for (const balance of balances) {
      expect(balance.paidCents - balance.shareCents + balance.settledCents).toBe(balance.balanceCents);
    }
    // And a balance is exactly what every entry did to that account, one by one.
    for (const balance of balances) {
      const summed = expenses.reduce(
        (sum, expense) => sum + entryEffectCents(expense, balance.accountId),
        0,
      );
      expect(summed).toBe(balance.balanceCents);
    }
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

  it("keeps a settlement out of what people paid and consumed", () => {
    const transfer: Expense = {
      type: "transfer",
      paid_by: { b: "15.00" },
      equal_by: true,
      paid_for: { a: "15.00" },
      equal_for: true,
      date: "2026-09-17",
    };
    const [first, second] = balancesFor([transfer], ["a", "b"]);
    expect(first).toEqual({ accountId: "b", paidCents: 0, shareCents: 0, settledCents: 1500, balanceCents: 1500 });
    expect(second).toEqual({ accountId: "a", paidCents: 0, shareCents: 0, settledCents: -1500, balanceCents: -1500 });
  });
});

describe("entry types", () => {
  const entry = (type?: string): Expense =>
    ({
      type,
      paid_by: { a: "10.00" },
      equal_by: true,
      paid_for: { b: "10.00" },
      equal_for: true,
      date: "2026-09-17",
    }) as Expense;

  it("reads the three types it knows", () => {
    expect(entryType(entry("expense"))).toBe("expense");
    expect(entryType(entry("income"))).toBe("income");
    expect(entryType(entry("transfer"))).toBe("transfer");
  });

  it("treats an absent type as an expense — every entry written before types existed is one", () => {
    expect(entryType(entry(undefined))).toBe("expense");
  });

  it("treats a word it does not know as an expense rather than dropping the entry", () => {
    // The server refuses these, so one arriving is a newer client's vocabulary; the screen still
    // has to add up.
    expect(entryType(entry("Income"))).toBe("expense");
    expect(entryType(entry("refund"))).toBe("expense");
  });
});

describe("net spent", () => {
  it.each(cases.spent)("$name", (testCase) => {
    const totals = spentTotals(testCase.expenses as unknown as Expense[]);
    expect({
      expenses: fromCents(totals.expensesCents),
      income: fromCents(totals.incomeCents),
      net: fromCents(totals.netCents),
    }).toEqual(testCase.expect);
  });
});

describe("what one entry does to one account", () => {
  it.each(cases.effect)("$name", (testCase) => {
    const expense = testCase.expense as unknown as Expense;
    expect(fromCents(entryEffectCents(expense, testCase.account))).toBe(testCase.expect);
  });
});

describe("a ledger of random entries (property test)", () => {
  /** A seeded generator: the same ledgers every run, so a failure can be reproduced. */
  function rng(seed: number): () => number {
    let state = seed >>> 0;
    return () => {
      // xorshift32 — small, deterministic and identical on every engine.
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      state >>>= 0;
      return state / 0x100000000;
    };
  }

  /** An equal-ish split of `totalCents` over `ids`, in the wire's positive decimal strings. */
  function wireSplit(totalCents: number, ids: string[]): Record<string, string> {
    return sharesToWire(splitEqually(totalCents, ids));
  }

  it("balances sum to zero, settling zeroes them, and net is expenses minus income", () => {
    const random = rng(20260920);
    const pick = (n: number) => Math.floor(random() * n);
    for (let round = 0; round < 300; round += 1) {
      const people = 2 + pick(4);
      const ids = Array.from({ length: people }, (_, index) => `p${index}`);
      const expenses: Expense[] = [];
      const entries = 1 + pick(6);
      for (let index = 0; index < entries; index += 1) {
        const type = (["expense", "income", "transfer"] as const)[pick(3)];
        const totalCents = 1 + pick(20000);
        if (type === "transfer") {
          const from = pick(people);
          const to = (from + 1 + pick(people - 1)) % people;
          expenses.push({
            type,
            paid_by: { [ids[from]]: fromCents(totalCents) },
            equal_by: true,
            paid_for: { [ids[to]]: fromCents(totalCents) },
            equal_for: true,
            date: "2026-09-17",
          });
          continue;
        }
        const payers = ids.filter(() => random() < 0.5);
        const forWhom = ids.filter(() => random() < 0.7);
        expenses.push({
          type,
          paid_by: wireSplit(totalCents, payers.length ? payers : [ids[pick(people)]]),
          equal_by: true,
          paid_for: wireSplit(totalCents, forWhom.length ? forWhom : [ids[pick(people)]]),
          equal_for: true,
          date: "2026-09-17",
        });
      }

      const balances = balancesFor(expenses, ids);
      expect(balances.reduce((sum, balance) => sum + balance.balanceCents, 0)).toBe(0);
      for (const balance of balances) {
        expect(balance.paidCents - balance.shareCents + balance.settledCents).toBe(balance.balanceCents);
      }

      // Settling the suggested transfers really does leave everyone square.
      const remaining = new Map(balances.map((b) => [b.accountId, b.balanceCents]));
      for (const transfer of settle(balances)) {
        expect(transfer.cents).toBeGreaterThan(0);
        remaining.set(transfer.from, (remaining.get(transfer.from) ?? 0) + transfer.cents);
        remaining.set(transfer.to, (remaining.get(transfer.to) ?? 0) - transfer.cents);
      }
      expect([...remaining.values()].every((cents) => cents === 0)).toBe(true);

      const totals = spentTotals(expenses);
      expect(totals.netCents).toBe(totals.expensesCents - totals.incomeCents);
      const gross = expenses
        .filter((expense) => entryType(expense) !== "transfer")
        .reduce((sum, expense) => sum + expenseTotalCents(expense), 0);
      expect(totals.expensesCents + totals.incomeCents).toBe(gross);
    }
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
  // Typed loosely on purpose: the JSON import infers a union of object shapes with optional keys,
  // which is not a Record — at runtime every case is a plain id-to-amount map.
  const balancesOf = (balances: object) =>
    Object.entries(balances as Record<string, string>).map(([accountId, amount]) => ({
      accountId,
      balanceCents: signedCents(amount),
    }));

  it.each(cases.settle)("$name", (testCase) => {
    const transfers = settle(balancesOf(testCase.balances)).map((t) => ({
      from: t.from,
      to: t.to,
      amount: fromCents(t.cents),
    }));
    expect(transfers).toEqual(testCase.expect);
  });

  it.each(cases.settle)("$name — zeroes every balance in at most n-1 positive transfers", (testCase) => {
    const balances = balancesOf(testCase.balances);
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

describe("the shared case table", () => {
  it("covers every group this test drives", () => {
    // Mirrors Android's ExpenseMathTest (T-279): a renamed or emptied group would otherwise make
    // a whole `it.each` block silently iterate nothing instead of failing.
    const groups = [
      "to_cents",
      "to_cents_invalid",
      "from_cents",
      "equal_split",
      "distribute",
      "balances",
      "spent",
      "effect",
      "settle",
      "fold",
    ] as const;
    for (const name of groups) {
      expect(cases[name].length, name).toBeGreaterThan(0);
    }
  });
});

// ---- guests and the fold (T-364) ---------------------------------------------

/** A wire expense as the case table writes it: type optional, the date and flags left out. */
function asExpense(entry: object): Expense {
  return { equal_by: false, equal_for: false, date: "2026-10-31", ...entry } as Expense;
}

/** The parts of an entry the fold group pins: the read type and both maps as wire strings. */
function wireOf(expense: Expense) {
  return { type: expense.type, paid_by: expense.paid_by, paid_for: expense.paid_for };
}

describe("the fold (shared cases)", () => {
  it.each(cases.fold)("$name", (testCase) => {
    const aliases = new Map(Object.entries(testCase.aliases as Record<string, string>));
    const folded = testCase.expenses.map((entry) => fold(asExpense(entry), aliases));
    expect(folded.map(wireOf)).toEqual(testCase.folded);

    const balances = balancesFor(folded, testCase.participants);
    const rendered = Object.fromEntries(
      balances.map((balance) => [
        balance.accountId,
        {
          paid: fromCents(balance.paidCents),
          share: fromCents(balance.shareCents),
          settled: fromCents(balance.settledCents),
          balance: fromCents(balance.balanceCents),
        },
      ]),
    );
    expect(rendered).toEqual(testCase.expect);
    expect(balances.map((balance) => balance.accountId)).toEqual(Object.keys(testCase.expect));
    expect(folded.reduce((sum, expense) => sum + expenseTotalCents(expense), 0)).toBe(toCents(testCase.total));
    expect(
      settle(balances).map((t) => ({ from: t.from, to: t.to, amount: fromCents(t.cents) })),
    ).toEqual(testCase.settle);
  });

  it("copies the date and both equal flags", () => {
    const entry: Expense = {
      paid_by: { "g:1": "5.00" },
      equal_by: true,
      paid_for: { a: "5.00" },
      equal_for: false,
      date: "2026-01-02",
    };
    expect(fold(entry, new Map([["g:1", "a"]]))).toEqual({
      type: "expense",
      paid_by: { a: "5.00" },
      equal_by: true,
      paid_for: { a: "5.00" },
      equal_for: false,
      date: "2026-01-02",
    });
  });
});

describe("the fold (property tests)", () => {
  /** Seeded, as the ledger property test above: the same ledgers every run. */
  function rng(seed: number): () => number {
    let state = seed >>> 0;
    return () => {
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      state >>>= 0;
      return state / 0x100000000;
    };
  }

  /** A random ledger over accounts and guests, and a random alias map onto the accounts. */
  function randomLedger(random: () => number) {
    const pick = (n: number) => Math.floor(random() * n);
    const accounts = Array.from({ length: 1 + pick(3) }, (_, index) => `a${index}`);
    const guests = Array.from({ length: 1 + pick(3) }, (_, index) => `g:${index}`);
    const ids = [...accounts, ...guests];
    const aliases = new Map<string, string>();
    for (const guest of guests) if (random() < 0.5) aliases.set(guest, accounts[pick(accounts.length)]);
    const expenses: Expense[] = [];
    for (let index = 0, count = 1 + pick(6); index < count; index += 1) {
      const type = (["expense", "income", "transfer"] as const)[pick(3)];
      const totalCents = 1 + pick(20000);
      if (type === "transfer") {
        const from = pick(ids.length);
        const to = (from + 1 + pick(ids.length - 1)) % ids.length;
        expenses.push(asExpense({
          type,
          paid_by: { [ids[from]]: fromCents(totalCents) },
          paid_for: { [ids[to]]: fromCents(totalCents) },
        }));
        continue;
      }
      const payers = ids.filter(() => random() < 0.4);
      const forWhom = ids.filter(() => random() < 0.7);
      expenses.push(asExpense({
        type,
        paid_by: sharesToWire(splitEqually(totalCents, payers.length ? payers : [ids[pick(ids.length)]])),
        paid_for: sharesToWire(splitEqually(totalCents, forWhom.length ? forWhom : [ids[pick(ids.length)]])),
      }));
    }
    // What the screens give balancesFor: the members and the guests nobody has claimed.
    const participants = [...accounts, ...guests.filter((guest) => !aliases.has(guest))];
    return { expenses, aliases, participants };
  }

  it("balances over folded entries sum to zero, whatever the alias map", () => {
    const random = rng(20261031);
    for (let round = 0; round < 300; round += 1) {
      const { expenses, aliases, participants } = randomLedger(random);
      const balances = balancesFor(expenses.map((e) => fold(e, aliases)), participants);
      expect(balances.reduce((sum, b) => sum + b.balanceCents, 0)).toBe(0);
      // No claimed guest keeps a row of its own: its numbers are its claimant's.
      for (const guest of aliases.keys()) expect(balances.some((b) => b.accountId === guest)).toBe(false);
      // And the total does not move: folding only renames who.
      const total = (list: Expense[]) => list.reduce((sum, e) => sum + expenseTotalCents(e), 0);
      expect(total(expenses.map((e) => fold(e, aliases)))).toBe(total(expenses));
    }
  });

  it("with no aliases is the identity", () => {
    const random = rng(7);
    for (let round = 0; round < 200; round += 1) {
      const { expenses } = randomLedger(random);
      for (const expense of expenses) {
        expect(fold(expense, new Map())).toEqual({ ...expense, type: expense.type ?? "expense" });
      }
    }
  });

  it("is idempotent", () => {
    const random = rng(11);
    for (let round = 0; round < 200; round += 1) {
      const { expenses, aliases } = randomLedger(random);
      for (const expense of expenses) {
        const once = fold(expense, aliases);
        expect(fold(once, aliases)).toEqual(once);
      }
    }
  });
});

const guestOf = (id: string, extra: Partial<ListGuest> = {}): ListGuest => ({
  id,
  name: `Guest ${id}`,
  initials: "GU",
  locked: false,
  claimed_by: null,
  created_at: 0,
  ...extra,
});

describe("guests on a ledger", () => {
  const members: ListMember[] = [
    { account_id: "a", email: "a@example.com", initials: "A" },
    { account_id: "b", email: "b@example.com", initials: "B" },
  ];
  const dave = guestOf("g:dave", { name: "Dave" });
  const eve = guestOf("g:eve", { name: "Eve", claimed_by: "b" });
  const ghost = guestOf("g:ghost", { name: "Ghost", claimed_by: "gone" });

  it("aliases only the claimed guests", () => {
    expect([...aliasesOf([dave, eve, ghost])]).toEqual([
      ["g:eve", "b"],
      ["g:ghost", "gone"],
    ]);
    expect(aliasesOf(undefined).size).toBe(0);
  });

  it("puts the members first, then the guests nobody has claimed", () => {
    expect(rosterIds(members, [eve, dave])).toEqual(["a", "b", "g:dave"]);
  });

  it("resolves an id the way the label table reads", () => {
    const former = new Map([["gone", 1], ["g:removed", 2]]);
    expect(resolveParticipant("a", members, [dave, eve], former)).toMatchObject({ kind: "member" });
    expect(resolveParticipant("g:dave", members, [dave, eve], former)).toMatchObject({ kind: "guest", guest: dave });
    expect(resolveParticipant("g:eve", members, [dave, eve], former)).toMatchObject({
      kind: "claimed",
      guest: eve,
      claimant: { kind: "member", id: "b" },
    });
    // A claimant who has left reads as the former member they are.
    expect(resolveParticipant("g:ghost", members, [ghost], former)).toMatchObject({
      kind: "claimed",
      claimant: { kind: "former", number: 1 },
    });
    expect(resolveParticipant("g:removed", members, [dave], former)).toEqual({
      kind: "former",
      id: "g:removed",
      number: 2,
    });
  });

  it("freezes stored keys as the server does", () => {
    const locked = guestOf("g:locked", { locked: true });
    const guests = [dave, eve, locked];
    expect(frozenReasonOf("a", members, guests, [])).toBeNull();
    expect(frozenReasonOf("a", members, guests, ["a"])).toBe("voter");
    expect(frozenReasonOf("gone", members, guests, [])).toBe("former");
    expect(frozenReasonOf("g:dave", members, guests, [])).toBeNull();
    expect(frozenReasonOf("g:locked", members, guests, [])).toBe("locked");
    // A guest the list no longer has is frozen like someone who left.
    expect(frozenReasonOf("g:removed", members, guests, [])).toBe("former");
    // A claimed guest is whatever its claimant is — and its own lock no longer counts.
    expect(frozenReasonOf("g:eve", members, guests, [])).toBeNull();
    expect(frozenReasonOf("g:eve", members, guests, ["b"])).toBe("voter");
    expect(frozenReasonOf("g:eve", members, [{ ...eve, locked: true }], [])).toBeNull();
    expect(frozenReasonOf("g:ghost", members, [ghost], [])).toBe("former");
  });

  it("numbers former participants over effective ids", () => {
    const items = [
      expenseItem("e1", "2026-09-01", ["a", "g:removed", "g:dave", "g:eve", "g:ghost"]),
    ];
    const numbers = formerMemberNumbers(items, members, [dave, eve, ghost]);
    // The removed guest and the departed claimant get one number each; live guests none.
    expect([...numbers]).toEqual([
      ["g:removed", 1],
      ["gone", 2],
    ]);
  });
});
