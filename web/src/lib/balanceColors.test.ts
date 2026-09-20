/// <reference types="node" />
// Node's file APIs, not a ?raw import: Vite refuses raw imports from outside web/ (its dev-server
// fs.allow rule), as i18n/crossClient.test.ts explains at more length.
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

/**
 * The colours a balance is read by (T-241).
 *
 * Two things have to hold and neither is visible from one file: the green means the same on both
 * clients, so it is the same green; and a coloured number has to be legible, so every balance
 * colour clears WCAG AA (4.5:1) on the surfaces it is drawn on, in both themes. Android's constant
 * is read straight from the Kotlin source, the way crossClient.test.ts reads its string resources,
 * so a hex changed on one side alone fails here instead of drifting quietly.
 */

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CSS = readFileSync(path.join(HERE, "../index.css"), "utf8");
const THEME_KT = readFileSync(
  path.join(HERE, "../../../android/app/src/main/java/org/p23q/shoppinglist/ui/theme/Theme.kt"),
  "utf8",
);

/** The custom properties of one theme: the :root block, with the dark media query layered over it. */
function cssTokens(theme: "light" | "dark"): Map<string, string> {
  const dark = CSS.indexOf("@media (prefers-color-scheme: dark)");
  expect(dark).toBeGreaterThan(0);
  const text = theme === "light" ? CSS.slice(0, dark) : CSS;
  const tokens = new Map<string, string>();
  for (const [, name, value] of text.matchAll(/(--color-[a-z-]+):\s*([^;]+);/g)) {
    tokens.set(name, value.trim());
  }
  return tokens;
}

/** `val BalancePositiveLight = Color(0xFF15803D)` — Android's side of the same pair. */
function kotlinColor(name: string): string {
  const match = THEME_KT.match(new RegExp(`val ${name} = Color\\(0x([0-9A-Fa-f]{8})\\)`));
  expect(match, `${name} in Theme.kt`).not.toBeNull();
  const argb = (match as RegExpMatchArray)[1];
  expect(argb.slice(0, 2).toUpperCase()).toBe("FF");
  return `#${argb.slice(2).toLowerCase()}`;
}

/** WCAG 2.1 relative luminance of a #rrggbb colour. */
function luminance(hex: string): number {
  const channels = [1, 3, 5].map((at) => parseInt(hex.slice(at, at + 2), 16) / 255);
  const [r, g, b] = channels.map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (light + 0.05) / (dark + 0.05);
}

describe("the balance palette", () => {
  it("uses one green on both clients", () => {
    expect(cssTokens("light").get("--color-positive")).toBe(kotlinColor("BalancePositiveLight"));
    expect(cssTokens("dark").get("--color-positive")).toBe(kotlinColor("BalancePositiveDark"));
  });

  it("reads a credit as green and a debt as red, never one colour for both", () => {
    for (const theme of ["light", "dark"] as const) {
      const tokens = cssTokens(theme);
      expect(tokens.get("--color-positive")).not.toBe(tokens.get("--color-danger"));
      // The theme's accent has other work: it must not double as a meaning.
      expect(tokens.get("--color-positive")).not.toBe(tokens.get("--color-accent"));
    }
  });

  it("clears WCAG AA on the surfaces a balance is drawn on, in both themes", () => {
    for (const theme of ["light", "dark"] as const) {
      const tokens = cssTokens(theme);
      for (const ink of ["--color-positive", "--color-danger", "--color-text-muted"]) {
        for (const paper of ["--color-bg", "--color-surface"]) {
          const ratio = contrast(tokens.get(ink) as string, tokens.get(paper) as string);
          expect(ratio, `${ink} on ${paper} (${theme})`).toBeGreaterThanOrEqual(4.5);
        }
      }
    }
  });

  it("computes contrast the way WCAG does", () => {
    // A guard on the helper above: black on white is 21:1, and a colour on itself is 1:1.
    expect(contrast("#000000", "#ffffff")).toBeCloseTo(21, 5);
    expect(contrast("#15803d", "#15803d")).toBeCloseTo(1, 5);
  });
});
