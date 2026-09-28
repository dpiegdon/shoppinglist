/// <reference types="node" />
// Node's file APIs, not a ?raw import: Vite refuses raw imports from outside web/ (its dev-server
// fs.allow rule), as i18n/crossClient.test.ts explains at more length.
import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

/**
 * The colour scheme (T-324): one scheme for both clients, each held in one place — the tokens at
 * the top of index.css here, BrandColors.kt on Android. This reads the Kotlin source, as
 * balanceColors.test.ts reads Theme.kt, so a role changed on one side alone fails here.
 */

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(HERE, "..");
const CSS = readFileSync(path.join(SRC, "index.css"), "utf8");
const BRAND_KT = readFileSync(
  path.join(HERE, "../../../android/app/src/main/java/org/p23q/shoppinglist/ui/theme/BrandColors.kt"),
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

/** `val AccentLight = Color(0xFF5A97FF)` as `#5a97ff`. */
function kotlinColor(name: string): string {
  const match = BRAND_KT.match(new RegExp(`val ${name} = Color\\(0x([0-9A-Fa-f]{8})\\)`));
  expect(match, `${name} in BrandColors.kt`).not.toBeNull();
  const argb = (match as RegExpMatchArray)[1];
  expect(argb.slice(0, 2).toUpperCase()).toBe("FF");
  return `#${argb.slice(2).toLowerCase()}`;
}

/** Each role: its web token and its Android name (without the Light/Dark suffix). */
const ROLES: [token: string, kotlin: string][] = [
  ["--color-bg", "Background"],
  ["--color-text", "Foreground"],
  ["--color-accent", "Accent"],
  ["--color-accent-strong", "AccentText"],
  ["--color-accent-text", "OnAccent"],
];

/** The scheme as decided (T-324), so changing both clients at once is still a deliberate edit here. */
const SCHEME: Record<string, { light: string; dark: string }> = {
  "--color-bg": { light: "#ffffff", dark: "#000000" },
  "--color-text": { light: "#1a1a1e", dark: "#f2f2f4" },
  "--color-accent": { light: "#5a97ff", dark: "#5a97ff" },
  "--color-accent-strong": { light: "#126bff", dark: "#5a97ff" },
  "--color-accent-text": { light: "#ffffff", dark: "#0b1220" },
};

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

/** Every source file under src/ except index.css and the tests. */
function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    if (!/\.(tsx?|css)$/.test(name) || /\.test\.tsx?$/.test(name)) return [];
    return full === path.join(SRC, "index.css") ? [] : [full];
  });
}

describe("the colour scheme", () => {
  it("gives each role the same value on both clients, light and dark", () => {
    for (const theme of ["light", "dark"] as const) {
      const tokens = cssTokens(theme);
      const suffix = theme === "light" ? "Light" : "Dark";
      for (const [token, kotlin] of ROLES) {
        expect(tokens.get(token), `${token} (${theme})`).toBe(kotlinColor(kotlin + suffix));
      }
    }
  });

  it("holds the decided values", () => {
    for (const theme of ["light", "dark"] as const) {
      const tokens = cssTokens(theme);
      for (const [token] of ROLES) {
        expect(tokens.get(token), `${token} (${theme})`).toBe(SCHEME[token][theme]);
      }
    }
  });

  it("keeps highlighted text legible on the background, in both themes", () => {
    for (const theme of ["light", "dark"] as const) {
      const tokens = cssTokens(theme);
      const ratio = contrast(tokens.get("--color-accent-strong") as string, tokens.get("--color-bg") as string);
      expect(ratio, `--color-accent-strong on --color-bg (${theme})`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("names no colour outside index.css", () => {
    const offenders = sourceFiles(SRC).flatMap((file) =>
      readFileSync(file, "utf8")
        .split("\n")
        .map((line, at) => ({ line, at }))
        .filter(({ line }) => /#[0-9a-fA-F]{3,8}\b|\b(rgba?|hsla?)\(/.test(line))
        .map(({ at, line }) => `${path.relative(SRC, file)}:${at + 1}: ${line.trim()}`),
    );
    expect(offenders).toEqual([]);
  });
});
