import { describe, expect, it } from "vitest";
import { isExpenses, listKind, listKindIcon, listKindLabelKey, showsShoppingFields } from "./listKind";
import type { ListObject } from "../api/contract";

function list(kind?: string): ListObject {
  const clock = { updated_at: 1, updated_by: "dev" };
  return {
    id: "list-1",
    created_at: 0,
    fields: {
      name: { value: "L", ...clock },
      ...(kind ? { kind: { value: kind as "shopping" | "checklist", ...clock } } : {}),
    },
  };
}

describe("listKind (T-110)", () => {
  it("defaults to shopping when the field is absent", () => {
    // Pre-T-110 rows and older servers send no kind at all — they must keep behaving as before.
    expect(listKind(list())).toBe("shopping");
    expect(listKind(undefined)).toBe("shopping");
  });

  it("reads an explicit kind", () => {
    expect(listKind(list("checklist"))).toBe("checklist");
    expect(listKind(list("shopping"))).toBe("shopping");
  });

  it("only shopping lists show the shopping-only fields", () => {
    expect(showsShoppingFields("shopping")).toBe(true);
    expect(showsShoppingFields("checklist")).toBe(false);
    expect(showsShoppingFields("expenses")).toBe(false);
  });

  it("recognises an expenses list", () => {
    expect(isExpenses("expenses")).toBe(true);
    expect(isExpenses("shopping")).toBe(false);
    expect(isExpenses("checklist")).toBe(false);
  });

  it("labels and icons the three kinds distinctly", () => {
    const keys = (["shopping", "checklist", "expenses"] as const).map(listKindLabelKey);
    const icons = (["shopping", "checklist", "expenses"] as const).map(listKindIcon);
    expect(new Set(keys).size).toBe(3);
    expect(new Set(icons).size).toBe(3);
  });
});
