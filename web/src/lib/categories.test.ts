import { describe, expect, it } from "vitest";
import cases from "../../../shared-test-cases/category-canon.json";
import {
  canonicalCategoryNames,
  categoryKey,
  distinctCanonicalCategories,
  normalizeCategoryOrder,
  planCategoryRename,
} from "./categories";

describe("categoryKey", () => {
  it("is case-insensitive and trimmed", () => {
    expect(categoryKey("  Group ")).toBe("group");
    expect(categoryKey("GROUP")).toBe(categoryKey("group"));
  });
  it("maps blank to the uncategorized key", () => {
    expect(categoryKey("   ")).toBe("");
  });
});

describe("canonicalCategoryNames", () => {
  it("picks the most-frequent casing, tie-broken lexicographically", () => {
    const names = canonicalCategoryNames(["group", "group", "Group"], []);
    expect(names.get("group")).toBe("group");
  });
  it("lets a category_order entry override the item casing", () => {
    const names = canonicalCategoryNames(["group", "group"], ["Group"]);
    expect(names.get("group")).toBe("Group");
  });
  it("ignores blank categories", () => {
    expect(canonicalCategoryNames(["", "  "], []).size).toBe(0);
  });
});

describe("distinctCanonicalCategories", () => {
  it("returns one entry per case-insensitive category, sorted", () => {
    // "Dairy" has the majority casing, so the single entry is "Dairy".
    expect(distinctCanonicalCategories(["Dairy", "Dairy", "dairy", "Bakery"], [])).toEqual([
      "Bakery",
      "Dairy",
    ]);
  });
});

// Driven by the table Android's CategoryCanonTest reads too (T-274): a tie-break or a sort order
// that used a locale-aware compare instead of byCodeUnits showed up only here, never in the cases
// above, where counts are never tied.
describe("category canon (shared table)", () => {
  it.each(cases.canonical_names)("canonical_names: $name", ({ raw_categories, category_order, expect: expected }) => {
    const names = canonicalCategoryNames(raw_categories, category_order);
    expect(Object.fromEntries(names)).toEqual(expected);
  });

  it.each(cases.autocomplete_order)(
    "autocomplete_order: $name",
    ({ raw_categories, category_order, expect: expected }) => {
      expect(distinctCanonicalCategories(raw_categories, category_order)).toEqual(expected);
    },
  );

  it("the case table covers every group this test drives", () => {
    // A renamed or emptied group would otherwise make a whole block silently iterate nothing.
    for (const name of ["canonical_names", "autocomplete_order"] as const) {
      expect(cases[name].length, name).toBeGreaterThan(0);
    }
  });
});

describe("planCategoryRename", () => {
  const items = [
    { id: "1", category: "group" },
    { id: "2", category: "Group" },
    { id: "3", category: "Other" },
  ];

  it("targets every item in the category, skipping ones already at the target casing", () => {
    const plan = planCategoryRename(items, [], "group", "Group");
    expect(plan.itemIds.sort()).toEqual(["1"]); // "2" already "Group"
  });

  it("rewrites the matching category_order entry and flags the change", () => {
    const plan = planCategoryRename(items, ["group", "Other"], "group", "Groceries");
    expect(plan.nextCategoryOrder).toEqual(["Groceries", "Other"]);
    expect(plan.orderChanged).toBe(true);
  });

  it("de-duplicates when a rename collides with another category (a merge)", () => {
    const plan = planCategoryRename(items, ["group", "Other"], "group", "other");
    expect(plan.nextCategoryOrder).toEqual(["other"]); // "Other" dropped as a dup of "other"
    expect(plan.orderChanged).toBe(true);
    // Every "group" item is retargeted to "other".
    expect(plan.itemIds.sort()).toEqual(["1", "2"]);
  });

  it("leaves the order untouched when the renamed category was never ordered", () => {
    const plan = planCategoryRename(items, ["Other"], "group", "Group");
    expect(plan.nextCategoryOrder).toEqual(["Other"]);
    expect(plan.orderChanged).toBe(false);
  });
});

describe("normalizeCategoryOrder (T-212)", () => {
  it("drops blanks and collapses a case-insensitive duplicate onto its first occurrence", () => {
    expect(normalizeCategoryOrder(["Dairy", " ", "dairy", "Bread", "bread "])).toEqual(["Dairy", "Bread"]);
  });

  it("trims entries and leaves a clean order as it is", () => {
    expect(normalizeCategoryOrder([" Dairy", "Bread"])).toEqual(["Dairy", "Bread"]);
  });
});
