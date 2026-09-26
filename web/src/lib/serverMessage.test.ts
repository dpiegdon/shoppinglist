import { describe, expect, it } from "vitest";
import { isValidServerMessage } from "./serverMessage";

describe("isValidServerMessage (T-315)", () => {
  it("accepts an empty message, which clears it", () => {
    expect(isValidServerMessage("")).toBe(true);
  });

  it("accepts a link: only clients keep it unclickable", () => {
    expect(isValidServerMessage("Down Sunday 10:00, see https://example.com/status")).toBe(true);
  });

  it("allows 200 characters and refuses 201", () => {
    expect(isValidServerMessage("a".repeat(200))).toBe(true);
    expect(isValidServerMessage("a".repeat(201))).toBe(false);
  });

  it("counts after trimming", () => {
    expect(isValidServerMessage(`  ${"a".repeat(200)}  `)).toBe(true);
  });

  it("counts characters, not UTF-16 units", () => {
    expect(isValidServerMessage("😀".repeat(200))).toBe(true);
    expect(isValidServerMessage("😀".repeat(201))).toBe(false);
  });

  it("refuses a line break or another control character inside", () => {
    expect(isValidServerMessage("one\ntwo")).toBe(false);
    expect(isValidServerMessage("one\rtwo")).toBe(false);
    expect(isValidServerMessage("one\ttwo")).toBe(false);
    expect(isValidServerMessage("one\u0007two")).toBe(false);
  });
});
