import { SERVER_MESSAGE_MAX_LENGTH } from "../api/contract";

/** What the rule makes of a draft: the text the server stores (null clears it), or its refusal. */
export type ServerMessageResult = { message: string | null } | { error: "invalid_message" };

// Trimmed from both ends: tab, line feed, carriage return and every space separator (Zs, which
// includes U+0020 and the no-break spaces).
const EDGES = /^[\t\n\r\p{Zs}]+|[\t\n\r\p{Zs}]+$/gu;
// Refused anywhere: control characters (Cc), the line and paragraph separators, the bidi
// embeddings and overrides U+202A–U+202E, and an unpaired surrogate (which the u flag sees as Cs).
// The isolates U+2066–U+2069 and the marks and joiners (Cf) stay allowed.
const REFUSED = /[\p{Cc}\p{Zl}\p{Zp}‪-‮\p{Cs}]/u;
// Nothing a reader would see: only format characters and spaces are left.
const INVISIBLE = /^[\p{Cf}\p{Zs}]*$/u;

/**
 * The server message's rule, exactly as the server applies it (T-316), pinned for all three
 * implementations by shared-test-cases/server-message.json. Lengths count code points, so an emoji
 * is one. The admin console checks this before sending anything; the server's 422 invalid_message
 * is the same rule.
 */
export function normalizeServerMessage(text: string): ServerMessageResult {
  const trimmed = text.replace(EDGES, "");
  if (REFUSED.test(trimmed)) return { error: "invalid_message" };
  if ([...trimmed].length > SERVER_MESSAGE_MAX_LENGTH) return { error: "invalid_message" };
  if (INVISIBLE.test(trimmed)) return { message: null };
  return { message: trimmed };
}
