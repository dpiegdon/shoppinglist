import { SERVER_MESSAGE_MAX_LENGTH } from "../api/contract";

/**
 * The server message's rule, as the server checks it (T-315): after trimming, one line of at most
 * 200 characters, with no control characters (Unicode category Cc — which covers \n and \r).
 * Characters, not UTF-16 units: the server counts code points, so an emoji is one.
 * The admin console checks this before sending anything; the server's 422 invalid_message is the
 * same rule.
 */
export function isValidServerMessage(text: string): boolean {
  const trimmed = text.trim();
  if (/\p{Cc}/u.test(trimmed)) return false;
  return [...trimmed].length <= SERVER_MESSAGE_MAX_LENGTH;
}
