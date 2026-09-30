/// <reference types="node" />
import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

/**
 * Every confirmation is the app's ConfirmDialog (T-343), never the browser's `window.confirm`:
 * that one draws OK/Cancel in the browser's language, not the app's, joins title and body into one
 * string, and can't show a destructive action in red. jsdom can't tell the two apart for a test that
 * forgets to click through, so this reads the source.
 */

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

/** Every source file under src/ except the tests. */
function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [full] : [];
  });
}

/**
 * The code without its comments, which may well name window.confirm to say why it is gone. A block
 * comment keeps its line breaks, so the line numbers reported stay right.
 */
function code(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, "")).replace(/(^|\s)\/\/.*$/gm, "$1");
}

// A bare call, or one through the global object. `askConfirm(` and `onConfirm(` are not it.
const NATIVE_CONFIRM = /(?<![\w$.])confirm\s*\(|\b(?:window|globalThis|self)\s*\.\s*confirm\b/;

describe("confirmations", () => {
  it("never use the browser's window.confirm", () => {
    const offenders = sourceFiles(SRC).flatMap((file) =>
      code(readFileSync(file, "utf8"))
        .split("\n")
        .flatMap((line, at) => (NATIVE_CONFIRM.test(line) ? [`${path.relative(SRC, file)}:${at + 1}: ${line.trim()}`] : [])),
    );
    expect(offenders).toEqual([]);
  });

  it("recognises the calls it is there to catch", () => {
    for (const call of ['if (!confirm("x")) return;', "window.confirm(message)", "const ok = globalThis.confirm(t)"]) {
      expect(NATIVE_CONFIRM.test(call), call).toBe(true);
    }
    for (const call of ["await askConfirm({ title })", "onConfirm()", "confirmReset()", "const confirmed = true"]) {
      expect(NATIVE_CONFIRM.test(call), call).toBe(false);
    }
  });
});
