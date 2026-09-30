/// <reference types="node" />
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

/**
 * Touch targets (T-338): every control a finger presses is 44px (2.75rem) at least, either drawn
 * that size or given a hit area that size around a smaller drawing. jsdom lays nothing out, so this
 * reads the rules in index.css.
 */

const CSS = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "index.css"), "utf8").replace(
  /\/\*[\s\S]*?\*\//g,
  "",
);

/** The declarations of the rule whose selector list is exactly [selector]. */
function rule(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace(/,\s*/g, ",\\s*");
  const match = CSS.match(new RegExp(`(?:^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`));
  expect(match, selector).not.toBeNull();
  return (match as RegExpMatchArray)[1];
}

describe("touch targets", () => {
  it("draws an icon button and a menu entry at 44px at least", () => {
    expect(rule(".btn-icon")).toMatch(/min-width:\s*2\.75rem/);
    expect(rule(".btn-icon")).toMatch(/min-height:\s*2\.75rem/);
    expect(rule(".menu-item")).toMatch(/min-height:\s*2\.75rem/);
  });

  it("gives a chip's remove button and a segment a 44px hit area around what they draw", () => {
    const hit = rule(".chip-remove::after, .segmented a::after");
    expect(hit).toMatch(/position:\s*absolute/);
    expect(hit).toMatch(/width:\s*2\.75rem/);
    expect(hit).toMatch(/height:\s*2\.75rem/);
    // The hit area is placed around its own control, not around some ancestor.
    expect(rule(".chip-remove")).toMatch(/position:\s*relative/);
    expect(rule(".segmented a")).toMatch(/position:\s*relative/);
  });
});
