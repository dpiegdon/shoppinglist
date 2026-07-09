import { describe, expect, it } from "vitest";
import { checkedItems, groupVisibleItems } from "./grouping";
import type { ItemObject } from "../api/contract";

function item(
  id: string,
  name: string,
  status: "todo" | "checked" | "backlog",
  category: string | null = null,
): ItemObject {
  const clock = { updated_at: 1, updated_by: "dev" };
  return {
    id,
    list_id: "list-1",
    created_at: 0,
    fields: {
      name: { value: name, ...clock },
      status: { value: status, ...clock },
      category: { value: category, ...clock },
    },
  };
}

describe("groupVisibleItems", () => {
  it("excludes backlog items always", () => {
    const items = [item("1", "Milk", "todo", "dairy"), item("2", "Someday item", "backlog", "dairy")];
    const groups = groupVisibleItems(items, [], true);
    expect(groups).toHaveLength(1);
    expect(groups[0].items.map((i) => i.id)).toEqual(["1"]);
  });

  it("excludes checked items when showChecked is false", () => {
    const items = [item("1", "Milk", "todo", "dairy"), item("2", "Old thing", "checked", "dairy")];
    const groups = groupVisibleItems(items, [], false);
    expect(groups[0].items.map((i) => i.id)).toEqual(["1"]);
  });

  it("mixes checked items into their category alongside todo, not a separate section", () => {
    const items = [
      item("1", "Milk", "todo", "dairy"),
      item("2", "Butter", "checked", "dairy"),
      item("3", "Bread", "todo", "bakery"),
    ];
    const groups = groupVisibleItems(items, [], true);
    expect(groups.map((g) => g.category)).toEqual(["bakery", "dairy"]);
    const dairy = groups.find((g) => g.category === "dairy")!;
    // Alphabetical within the category, todo and checked interleaved as one list.
    expect(dairy.items.map((i) => i.id)).toEqual(["2", "1"]); // Butter, Milk
  });

  it("orders groups per category_order, then leftovers alphabetically", () => {
    const items = [
      item("1", "Bread", "todo", "bakery"),
      item("2", "Milk", "todo", "dairy"),
      item("3", "Soap", "todo", "hygiene"),
      item("4", "Apple", "todo", "produce"),
    ];
    const groups = groupVisibleItems(items, ["dairy", "bakery"], false);
    expect(groups.map((g) => g.category)).toEqual(["dairy", "bakery", "hygiene", "produce"]);
  });

  it("sorts items alphabetically within a category", () => {
    const items = [item("1", "Zucchini", "todo", "produce"), item("2", "Apple", "todo", "produce")];
    const groups = groupVisibleItems(items, [], false);
    expect(groups[0].items.map((i) => i.id)).toEqual(["2", "1"]);
  });

  it("puts uncategorized items in a trailing '—' group", () => {
    const items = [item("1", "Milk", "todo", "dairy"), item("2", "Mystery item", "todo", null)];
    const groups = groupVisibleItems(items, [], false);
    expect(groups.map((g) => g.category)).toEqual(["dairy", "—"]);
  });

  it("treats an empty-string category the same as uncategorized", () => {
    const items = [item("1", "Mystery item", "todo", "")];
    const groups = groupVisibleItems(items, [], false);
    expect(groups).toEqual([{ category: "—", items: [items[0]] }]);
  });
});

describe("checkedItems", () => {
  it("returns every checked item regardless of category or order", () => {
    const items = [
      item("1", "Zucchini", "checked", "produce"),
      item("2", "Apple", "checked", "produce"),
      item("3", "Bread", "todo", "bakery"),
    ];
    expect(checkedItems(items).map((i) => i.id).sort()).toEqual(["1", "2"]);
  });
});
