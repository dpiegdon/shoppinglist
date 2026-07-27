import { describe, expect, it } from "vitest";
import { MAX_CHANGES_PER_SYNC } from "../api/contract";
import type { ItemObject, ListObject } from "../api/contract";
import { splitChanges } from "./useSync";

const mkList = (id: string) => ({ id, fields: {} }) as unknown as ListObject;
const mkItem = (id: string) => ({ id, list_id: "l", fields: {} }) as unknown as ItemObject;

const lists = (n: number) => Array.from({ length: n }, (_, i) => mkList(`list-${i}`));
const items = (n: number) => Array.from({ length: n }, (_, i) => mkItem(`item-${i}`));

const sizeOf = (batch: { lists: ListObject[]; items: ItemObject[] }) =>
  batch.lists.length + batch.items.length;

describe("splitChanges", () => {
  it("still makes one request when there is nothing to push", () => {
    // A pull-only sync goes through the same path; returning zero batches would skip the pull.
    expect(splitChanges({})).toEqual([{ lists: [], items: [] }]);
  });

  it("keeps a push that fits in a single batch", () => {
    const batches = splitChanges({ lists: lists(2), items: items(3) });

    expect(batches).toHaveLength(1);
    expect(batches[0].lists).toHaveLength(2);
    expect(batches[0].items).toHaveLength(3);
  });

  it("sends exactly the cap as one batch", () => {
    const batches = splitChanges({ items: items(MAX_CHANGES_PER_SYNC) });

    expect(batches).toHaveLength(1);
    expect(sizeOf(batches[0])).toBe(MAX_CHANGES_PER_SYNC);
  });

  it("splits one row over the cap into two batches", () => {
    const batches = splitChanges({ items: items(MAX_CHANGES_PER_SYNC + 1) });

    expect(batches).toHaveLength(2);
    expect(sizeOf(batches[0])).toBe(MAX_CHANGES_PER_SYNC);
    expect(sizeOf(batches[1])).toBe(1);
  });

  it("counts lists and items against the same cap", () => {
    const half = Math.floor(MAX_CHANGES_PER_SYNC / 2) + 1;

    const batches = splitChanges({ lists: lists(half), items: items(half) });

    expect(batches.length).toBeGreaterThan(1);
    for (const batch of batches) {
      expect(sizeOf(batch)).toBeLessThanOrEqual(MAX_CHANGES_PER_SYNC);
    }
  });

  it("never exceeds the cap for any batch size", () => {
    for (const n of [1, 7, MAX_CHANGES_PER_SYNC - 1, MAX_CHANGES_PER_SYNC * 3 + 7]) {
      for (const batch of splitChanges({ items: items(n) })) {
        expect(sizeOf(batch)).toBeLessThanOrEqual(MAX_CHANGES_PER_SYNC);
      }
    }
  });

  it("sends every list before any item, so an item never names a list the server has not seen", () => {
    // The server registers membership when it applies a list; an item naming an unknown list is
    // refused. Across batches, all lists must therefore be drained first.
    const batches = splitChanges({
      lists: lists(MAX_CHANGES_PER_SYNC + 5),
      items: items(10),
    });

    const firstBatchWithItems = batches.findIndex((b) => b.items.length > 0);
    const lastBatchWithLists = batches.map((b) => b.lists.length > 0).lastIndexOf(true);
    expect(firstBatchWithItems).toBeGreaterThanOrEqual(lastBatchWithLists);
  });

  it("loses and duplicates nothing", () => {
    const batches = splitChanges({
      lists: lists(MAX_CHANGES_PER_SYNC + 3),
      items: items(MAX_CHANGES_PER_SYNC + 4),
    });

    const listIds = batches.flatMap((b) => b.lists.map((l) => l.id));
    const itemIds = batches.flatMap((b) => b.items.map((i) => i.id));

    expect(listIds).toEqual(lists(MAX_CHANGES_PER_SYNC + 3).map((l) => l.id));
    expect(itemIds).toEqual(items(MAX_CHANGES_PER_SYNC + 4).map((i) => i.id));
    expect(new Set(listIds).size).toBe(listIds.length);
    expect(new Set(itemIds).size).toBe(itemIds.length);
  });

  it("terminates on a pathologically small cap", () => {
    const batches = splitChanges({ lists: lists(2), items: items(2) }, 1);

    expect(batches).toHaveLength(4);
    expect(batches.every((b) => sizeOf(b) === 1)).toBe(true);
  });
});
