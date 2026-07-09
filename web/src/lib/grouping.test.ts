import { describe, expect, it } from "vitest";
import { checkedItems, groupTodoItems } from "./grouping";
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

describe("groupTodoItems", () => {
  it("only includes todo items, excluding checked and backlog", () => {
    const items = [
      item("1", "Milk", "todo", "dairy"),
      item("2", "Old thing", "checked", "dairy"),
      item("3", "Someday item", "backlog", "dairy"),
    ];
    const groups = groupTodoItems(items, []);
    expect(groups).toHaveLength(1);
    expect(groups[0].items.map((i) => i.id)).toEqual(["1"]);
  });

  it("orders groups per category_order, then leftovers alphabetically", () => {
    const items = [
      item("1", "Bread", "todo", "bakery"),
      item("2", "Milk", "todo", "dairy"),
      item("3", "Soap", "todo", "hygiene"),
      item("4", "Apple", "todo", "produce"),
    ];
    const groups = groupTodoItems(items, ["dairy", "bakery"]);
    expect(groups.map((g) => g.category)).toEqual(["dairy", "bakery", "hygiene", "produce"]);
  });

  it("sorts items alphabetically within a category", () => {
    const items = [item("1", "Zucchini", "todo", "produce"), item("2", "Apple", "todo", "produce")];
    const groups = groupTodoItems(items, []);
    expect(groups[0].items.map((i) => i.id)).toEqual(["2", "1"]);
  });

  it("puts uncategorized items in a trailing '—' group", () => {
    const items = [item("1", "Milk", "todo", "dairy"), item("2", "Mystery item", "todo", null)];
    const groups = groupTodoItems(items, []);
    expect(groups.map((g) => g.category)).toEqual(["dairy", "—"]);
  });

  it("treats an empty-string category the same as uncategorized", () => {
    const items = [item("1", "Mystery item", "todo", "")];
    const groups = groupTodoItems(items, []);
    expect(groups).toEqual([{ category: "—", items: [items[0]] }]);
  });
});

describe("checkedItems", () => {
  it("returns only checked items, alphabetically", () => {
    const items = [
      item("1", "Zucchini", "checked"),
      item("2", "Apple", "checked"),
      item("3", "Bread", "todo"),
    ];
    expect(checkedItems(items).map((i) => i.id)).toEqual(["2", "1"]);
  });
});
