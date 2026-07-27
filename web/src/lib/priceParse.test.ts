import { describe, expect, it } from "vitest";
import { parseCurrency, parsePriceAmount } from "./priceParse";

describe("parsePriceAmount", () => {
  it("accepts a plain amount unchanged", () => {
    expect(parsePriceAmount("1.99")).toEqual({ valid: true, value: "1.99" });
  });

  it("normalizes a comma decimal separator to a dot", () => {
    expect(parsePriceAmount("1,50")).toEqual({ valid: true, value: "1.50" });
  });

  it("strips whitespace and a trailing currency symbol", () => {
    expect(parsePriceAmount(" 2€ ")).toEqual({ valid: true, value: "2" });
  });

  it("strips a leading or trailing currency symbol but not an embedded one", () => {
    expect(parsePriceAmount("€1.50")).toEqual({ valid: true, value: "1.50" });
    expect(parsePriceAmount("1.50€")).toEqual({ valid: true, value: "1.50" });
    // Embedded symbol is left in place, so it fails the amount regex rather than becoming "15".
    expect(parsePriceAmount("1€5")).toEqual({ valid: false, message: "Enter an amount like 1.99" });
  });

  it("treats a blank value as valid with no price", () => {
    expect(parsePriceAmount("  ")).toEqual({ valid: true, value: null });
    expect(parsePriceAmount("")).toEqual({ valid: true, value: null });
  });

  it("rejects trailing garbage with an inline message", () => {
    expect(parsePriceAmount("1,50abc")).toEqual({ valid: false, message: "Enter an amount like 1.99" });
  });

  it("rejects more than two decimal places", () => {
    expect(parsePriceAmount("1.999")).toEqual({ valid: false, message: "Enter an amount like 1.99" });
  });
});

describe("parseCurrency", () => {
  it("uppercases a lowercase 3-letter code", () => {
    expect(parseCurrency("usd")).toEqual({ valid: true, value: "USD" });
  });

  it("treats a blank value as valid with no currency", () => {
    expect(parseCurrency("  ")).toEqual({ valid: true, value: null });
  });

  it("rejects a code that isn't 3 letters", () => {
    expect(parseCurrency("US")).toEqual({ valid: false, message: "Use a 3-letter code like EUR" });
    expect(parseCurrency("USDD")).toEqual({ valid: false, message: "Use a 3-letter code like EUR" });
  });
});

describe("non-ASCII digits (T-125)", () => {
  it("rejects digits from other scripts, matching the server", () => {
    // The three copies of this pattern (here, server sync.py, android ItemFormViewModel) looked
    // identical but did not agree: Python's \d is Unicode-aware, so the SERVER accepted these and
    // then served back a price no client could parse. All three now spell out [0-9].
    for (const amount of ["٥.٩٩", "١٩٩", "५.९९", "５.９９"]) {
      expect(parsePriceAmount(amount).valid, amount).toBe(false);
    }
  });

  it("still accepts ordinary ASCII decimals", () => {
    for (const amount of ["1.99", "0", "12", "1234.5"]) {
      expect(parsePriceAmount(amount).valid, amount).toBe(true);
    }
  });
});
