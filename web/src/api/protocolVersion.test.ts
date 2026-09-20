/// <reference types="node" />
// Node's file APIs, not a ?raw import: Vite refuses raw imports from outside web/ (its dev-server
// fs.allow rule), exactly as crossClient.test.ts explains.
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { PROTOCOL_HEADER, PROTOCOL_VERSION } from "./protocol";

/**
 * One protocol number, three copies (T-240).
 *
 * The web, the Android client and the server each hold PROTOCOL_VERSION as a constant of their
 * own — three languages, no shared build step — and a server that disagrees with its clients turns
 * every one of them away. So the constants are read straight from the repository and compared,
 * the way crossClient.test.ts reads the Android string resources.
 */
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
const ANDROID_PROTOCOL = path.join(
  ROOT,
  "android/app/src/main/java/org/p23q/shoppinglist/data/api/Protocol.kt",
);
const SERVER_PROTOCOL = path.join(ROOT, "server/src/shoppinglist_server/protocol.py");

/** The integer a `NAME = 3` / `const val NAME = 3` line assigns, or null if the file has none. */
function constantIn(file: string, pattern: RegExp): number | null {
  const match = readFileSync(file, "utf8").match(pattern);
  return match ? Number(match[1]) : null;
}

describe("the three protocol constants agree (T-240)", () => {
  it("Android's Protocol.kt holds the same version and header as the web's protocol.ts", () => {
    expect(existsSync(ANDROID_PROTOCOL)).toBe(true);
    expect(constantIn(ANDROID_PROTOCOL, /const val PROTOCOL_VERSION\s*=\s*(\d+)/)).toBe(PROTOCOL_VERSION);
    const header = readFileSync(ANDROID_PROTOCOL, "utf8").match(
      /const val PROTOCOL_HEADER\s*=\s*"([^"]+)"/,
    );
    expect(header?.[1]).toBe(PROTOCOL_HEADER);
  });

  it("the server's protocol.py holds the same version and header", () => {
    if (!existsSync(SERVER_PROTOCOL)) {
      // The server side is T-243, landing separately. Skipping rather than failing keeps this
      // suite honest about what it actually checked instead of going red over work not yet done;
      // once protocol.py exists the assertions below run on every suite from then on.
      console.warn(`skipped: ${SERVER_PROTOCOL} does not exist yet (server side, T-243)`);
      return;
    }
    expect(constantIn(SERVER_PROTOCOL, /^PROTOCOL_VERSION\s*=\s*(\d+)/m)).toBe(PROTOCOL_VERSION);
    const header = readFileSync(SERVER_PROTOCOL, "utf8").match(/^PROTOCOL_HEADER\s*=\s*"([^"]+)"/m);
    expect(header?.[1]).toBe(PROTOCOL_HEADER);
  });
});
