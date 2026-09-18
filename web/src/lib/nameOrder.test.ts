import { describe, expect, it } from "vitest";
import cases from "../../../shared-test-cases/name-order.json";
import { byName, nameSortKey } from "./nameOrder";

// Driven by the table Android's NameOrderTest reads too (T-176): the point is that both agree.
describe("name order", () => {
  it.each(cases.keys)("$name sorts as '$key'", ({ name, key }) => {
    expect(nameSortKey(name)).toBe(key);
  });

  it.each(cases.orders)("$name", ({ lists, expect: expected }) => {
    const sorted = [...lists].sort(byName((l) => l.name, (l) => l.id));
    expect(sorted.map((l) => l.id)).toEqual(expected);
  });
});
