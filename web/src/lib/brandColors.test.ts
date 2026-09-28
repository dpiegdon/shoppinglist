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
const THEME_KT = readFileSync(
  path.join(HERE, "../../../android/app/src/main/java/org/p23q/shoppinglist/ui/theme/Theme.kt"),
  "utf8",
);

/** CSS with its comments blanked out, newlines kept, so a commented-out token or colour is not read. */
function withoutCssComments(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, " "));
}

/** The body of the `:root { … }` block that starts at or after [from], up to its own closing brace. */
function rootBlock(text: string, from: number): string {
  const open = text.indexOf(":root {", from);
  expect(open, ":root block").toBeGreaterThanOrEqual(0);
  let depth = 0;
  for (let at = text.indexOf("{", open); at < text.length; at++) {
    if (text[at] === "{") depth++;
    if (text[at] === "}" && --depth === 0) return text.slice(text.indexOf("{", open) + 1, at);
  }
  throw new Error("unclosed :root block");
}

/**
 * The custom properties of one theme: the top-level :root block, with the :root block inside the
 * dark media query layered over it. Nothing outside those two blocks is read.
 */
function cssTokens(theme: "light" | "dark", source: string = CSS): Map<string, string> {
  const css = withoutCssComments(source);
  const dark = css.indexOf("@media (prefers-color-scheme: dark)");
  expect(dark).toBeGreaterThan(0);
  const light = rootBlock(css, 0);
  expect(css.indexOf(":root {"), "the light :root block comes before the dark query").toBeLessThan(dark);
  const blocks = theme === "light" ? [light] : [light, rootBlock(css, dark)];
  const tokens = new Map<string, string>();
  for (const block of blocks) {
    for (const [, name, value] of block.matchAll(/(--color-[a-z-]+):\s*([^;]+);/g)) {
      tokens.set(name, value.trim());
    }
  }
  return tokens;
}

/**
 * `val AccentLight = Color(0xFF5A97FF)` as `#5a97ff`. The whole line must be that declaration, so
 * a commented-out or indented one never matches first.
 */
function kotlinColor(name: string, source: string = BRAND_KT): string {
  const match = source.match(new RegExp(`^val ${name} = Color\\(0x([0-9A-Fa-f]{8})\\)$`, "m"));
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

/** CSS's named colours: a word on its own, not part of a name like `white-space` or `tan-x`. */
const NAMED_COLOURS = (
  "aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown " +
  "burlywood cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan " +
  "darkgoldenrod darkgray darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid " +
  "darkred darksalmon darkseagreen darkslateblue darkslategray darkslategrey darkturquoise darkviolet " +
  "deeppink deepskyblue dimgray dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro " +
  "ghostwhite gold goldenrod gray green greenyellow grey honeydew hotpink indianred indigo ivory khaki " +
  "lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan lightgoldenrodyellow " +
  "lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray " +
  "lightslategrey lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine " +
  "mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise " +
  "mediumvioletred midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab " +
  "orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru " +
  "pink plum powderblue purple rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown " +
  "seagreen seashell sienna silver skyblue slateblue slategray slategrey snow springgreen steelblue tan " +
  "teal thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen"
).split(" ");
const NAMED = NAMED_COLOURS.join("|");

/**
 * The lines of a source file that name a colour: a hex value or a colour function anywhere (a
 * comment included, as before), and a named colour where it is a value, with comments blanked out:
 * in CSS any standalone use, in TypeScript a string that is only the name, or a colour property
 * (`color: "white"`, `background: "red"`, `fill="navy"`) whose value holds one.
 */
function colourOffenders(text: string, css: boolean): number[] {
  const functions = /#[0-9a-fA-F]{3,8}\b|\b(rgba?|hsla?|hwb|lab|lch|oklab|oklch|color-mix|color)\(/i;
  const named = css
    ? new RegExp(`(?<![\\w-])(${NAMED})(?![\\w-])`, "i")
    : new RegExp(
        `(["'\`])(${NAMED})\\1|\\b(color|background\\w*|fill|stroke|border\\w*|outline\\w*|accentColor|caretColor)\\s*[:=]\\s*["'\`{][^"'\`]*?(?<![\\w-])(${NAMED})(?![\\w-])`,
        "i",
      );
  const blank = (comment: string) => comment.replace(/[^\n]/g, " ");
  const code = css
    ? withoutCssComments(text)
    : text.replace(/\/\*[\s\S]*?\*\//g, blank).replace(/(^|[^:"'`])\/\/.*$/gm, (line, lead) => lead + blank(line.slice(lead.length)));
  const raw = text.split("\n");
  return code.split("\n").flatMap((line, at) => (functions.test(raw[at]) || named.test(line) ? [at + 1] : []));
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

  it("gives the registration switch the same meaning colours as Android's Theme.kt, in both themes", () => {
    // A meaning colour, not a brand one: the same values light and dark (T-327).
    for (const theme of ["light", "dark"] as const) {
      const tokens = cssTokens(theme);
      expect(tokens.get("--color-switch-on"), `--color-switch-on (${theme})`).toBe(kotlinColor("RegistrationSwitchOn", THEME_KT));
      expect(tokens.get("--color-switch-knob"), `--color-switch-knob (${theme})`).toBe(kotlinColor("RegistrationSwitchKnob", THEME_KT));
    }
    expect(kotlinColor("RegistrationSwitchOn", THEME_KT)).toBe("#2e7d32");
  });

  it("keeps highlighted text legible on the background, in both themes", () => {
    for (const theme of ["light", "dark"] as const) {
      const tokens = cssTokens(theme);
      const ratio = contrast(tokens.get("--color-accent-strong") as string, tokens.get("--color-bg") as string);
      expect(ratio, `--color-accent-strong on --color-bg (${theme})`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("names no colour outside index.css", () => {
    const offenders = sourceFiles(SRC).flatMap((file) => {
      const text = readFileSync(file, "utf8");
      const lines = text.split("\n");
      return colourOffenders(text, file.endsWith(".css")).map(
        (at) => `${path.relative(SRC, file)}:${at}: ${lines[at - 1].trim()}`,
      );
    });
    expect(offenders).toEqual([]);
  });
});

// The readers above are what the checks stand on; each is pinned on a sample it once misread (T-327).
describe("the colour readers", () => {
  it("read a Kotlin colour from its own whole line, not from a comment above it", () => {
    const source = [
      "// val AccentLight = Color(0xFF000000)",
      "    val AccentLight = Color(0xFF111111)",
      "val AccentLight = Color(0xFF5A97FF)",
    ].join("\n");
    expect(kotlinColor("AccentLight", source)).toBe("#5a97ff");
  });

  it("read the tokens inside the two :root blocks only", () => {
    const source = [
      "/* --color-bg: #123456; */",
      ".card { --color-bg: #abcdef; }",
      ":root {",
      "  --color-bg: #ffffff;",
      "}",
      ".later { --color-bg: #999999; }",
      "@media (prefers-color-scheme: dark) {",
      "  .other { --color-bg: #333333; }",
      "  :root {",
      "    --color-bg: #000000;",
      "  }",
      "}",
      ".last { --color-bg: #777777; }",
    ].join("\n");
    expect(cssTokens("light", source).get("--color-bg")).toBe("#ffffff");
    expect(cssTokens("dark", source).get("--color-bg")).toBe("#000000");
  });

  it("find a named colour or a newer colour function, and leave prose and look-alikes alone", () => {
    expect(colourOffenders("a { color: white; }", true)).toEqual([1]);
    expect(colourOffenders("a {\n  border: 1px solid Navy;\n}", true)).toEqual([2]);
    expect(colourOffenders("a { white-space: nowrap; }\n/* red owed, green owing */", true)).toEqual([]);
    expect(colourOffenders("a { background: oklch(70% 0.1 250); }", true)).toEqual([1]);
    expect(colourOffenders("a { color: color-mix(in srgb, var(--x), var(--y)); }", true)).toEqual([1]);
    expect(colourOffenders("a { color: hwb(0 0% 0%); }", true)).toEqual([1]);
    expect(colourOffenders('const s = { color: "red" };', false)).toEqual([1]);
    expect(colourOffenders("<svg fill='black' />", false)).toEqual([1]);
    expect(colourOffenders('const tone = "white";', false)).toEqual([1]);
    expect(colourOffenders("// green track when on, red when off\nconst label = \"Red Cross\";", false)).toEqual([]);
    expect(colourOffenders('const cls = "whitespace-nowrap";', false)).toEqual([]);
  });
});
