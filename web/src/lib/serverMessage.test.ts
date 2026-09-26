/// <reference types="node" />
// Node's file APIs and JSON.parse, not a JSON import: Vite's JSON plugin refuses the table's lone
// surrogate ("\ud800"), which is legal JSON and exactly one of the cases.
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { normalizeServerMessage, type ServerMessageResult } from "./serverMessage";

const TABLE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../shared-test-cases/server-message.json");
const table = JSON.parse(readFileSync(TABLE, "utf8")) as {
  cases: Array<{ name: string; input: string; result: ServerMessageResult }>;
};

describe("normalizeServerMessage follows the shared table (T-316)", () => {
  it("has cases to check", () => {
    expect(table.cases.length).toBeGreaterThan(0);
  });

  for (const c of table.cases) {
    it(c.name, () => {
      expect(normalizeServerMessage(c.input)).toEqual(c.result);
    });
  }
});

// The shared table cannot tell the rule's trim from String.prototype.trim, which also strips
// U+000B, U+000C, U+2028, U+2029 and U+FEFF from the ends. The rule trims only its own set.
describe("normalizeServerMessage trims only the rule's characters", () => {
  it("refuses a vertical tab or form feed at an end instead of trimming it", () => {
    expect(normalizeServerMessage("a\u000b")).toEqual({ error: "invalid_message" });
    expect(normalizeServerMessage("\u000ca")).toEqual({ error: "invalid_message" });
  });

  it("refuses a line or paragraph separator at an end instead of trimming it", () => {
    expect(normalizeServerMessage("a ")).toEqual({ error: "invalid_message" });
    expect(normalizeServerMessage(" a")).toEqual({ error: "invalid_message" });
  });

  it("keeps a byte-order mark (Cf) at an end", () => {
    expect(normalizeServerMessage("a﻿")).toEqual({ message: "a﻿" });
  });
});
