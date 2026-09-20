import { describe, expect, it } from "vitest";
import {
  balanceColor,
  formatCalendarDate,
  formatDay,
  formatMoney,
  formatNumber,
  formatSignedMoney,
} from "./format";

// Intl output varies in its spaces between ICU versions (a no-break space in one, a narrow one in
// the next), so these compare with every kind of space folded to a plain one.
const plain = (s: string) => s.replace(/[\s  ]/g, " ");

describe("amounts in the app's language (T-187)", () => {
  it("formats a currency code the way the language does", () => {
    expect(plain(formatMoney(6400, "EUR", "de"))).toBe("64,00 €");
    expect(formatMoney(6400, "EUR", "en")).toBe("€64.00");
    expect(formatMoney(-3200, "EUR", "en")).toBe("-€32.00");
  });

  it("keeps a free-text unit as a label after the number", () => {
    expect(formatMoney(1200, "pizza slices", "en")).toBe("12.00 pizza slices");
    expect(plain(formatMoney(1250, "Tokens", "de"))).toBe("12,50 Tokens");
  });

  it("drops cents a currency does not use, unless the amount has some", () => {
    expect(formatMoney(150000, "JPY", "en")).toBe("¥1,500");
    expect(formatMoney(150050, "JPY", "en")).toBe("¥1,500.50");
  });

  it("formats a bare number with two decimals", () => {
    expect(formatNumber(6400, "de")).toBe("64,00");
    expect(formatNumber(6400, "en")).toBe("64.00");
  });
});

describe("dates in the app's language (T-180)", () => {
  it("writes a calendar date as the language does, never a day early", () => {
    expect(formatCalendarDate("2026-09-17", "en")).toBe("Sep 17, 2026");
    expect(formatCalendarDate("2026-09-17", "de")).toBe("17.09.2026");
  });

  it("formats a moment in the same style", () => {
    expect(formatDay(Date.UTC(2026, 8, 17, 12), "en")).toBe("Sep 17, 2026");
  });
});

describe("signed amounts (T-241)", () => {
  it("marks a credit with a plus and leaves a debt its minus", () => {
    expect(formatSignedMoney(3200, "EUR", "en")).toBe("+€32.00");
    expect(formatSignedMoney(-3200, "EUR", "en")).toBe("-€32.00");
    expect(plain(formatSignedMoney(3200, "EUR", "de"))).toBe("+32,00 €");
    expect(plain(formatSignedMoney(-3200, "EUR", "de"))).toBe("-32,00 €");
  });

  it("leaves a square balance unsigned", () => {
    expect(formatSignedMoney(0, "EUR", "en")).toBe("€0.00");
    expect(formatSignedMoney(0, "EUR", "en")).toBe(formatMoney(0, "EUR", "en"));
    expect(formatSignedMoney(0, "EUR", "ar")).toBe(formatMoney(0, "EUR", "ar"));
  });

  it("keeps each language's own formatting, currency precision included", () => {
    expect(formatSignedMoney(150000, "JPY", "ja")).toBe("+￥1,500");
    expect(formatSignedMoney(150050, "JPY", "en")).toBe("+¥1,500.50");
    expect(formatSignedMoney(1200, "pizza slices", "en")).toBe("+12.00 pizza slices");
    expect(plain(formatSignedMoney(1250, "Tokens", "de"))).toBe("+12,50 Tokens");
  });

  it("puts Arabic's plus where Arabic puts its minus, bidi marks and all", () => {
    // Not "+" glued to the front: in an RTL line the sign belongs where the language writes it,
    // with the marks that keep the digits and the currency in order.
    const credit = formatSignedMoney(6400, "EUR", "ar");
    const debt = formatMoney(-6400, "EUR", "ar");
    expect(credit).toBe(debt.replace("-", "+"));
    expect(credit.indexOf("+")).toBe(debt.indexOf("-"));
    expect(credit.indexOf("+")).toBeGreaterThan(0);
  });
});

describe("balance colours (T-182, T-241)", () => {
  it("shows a credit as green, a debt as red and square as grey", () => {
    expect(balanceColor(0)).toBe("var(--color-text-muted)");
    expect(balanceColor(1)).toBe("var(--color-positive)");
    expect(balanceColor(-1)).toBe("var(--color-danger)");
  });
});
