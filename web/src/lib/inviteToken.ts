// A URL runs to the first whitespace. The Unicode spaces are spelled out so the set is exactly the
// one Android's pattern uses: JavaScript's \s has most of them, Java's only the ASCII ones.
const HTTPS_URL = /https:\/\/[^\s\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]+/gi;
const URL_TRAILING_PUNCTUATION = /[.,;:!?)\]>"']+$/;

/**
 * The invite in pasted text (T-300, T-301): a whole message such as "Join my list: https://…/invite/T"
 * is pasted as readily as the link alone, so the first https URL in it, preferring one with an
 * `/invite/` segment, with any sentence punctuation after it dropped. Text with no https URL in it
 * (a bare token) is returned trimmed, unchanged. Mirrors the Android app's pastedInvite; both are
 * pinned by shared-test-cases/invite-paste.json.
 */
export function pastedInvite(raw: string): string {
  const urls = Array.from(raw.matchAll(HTTPS_URL), (m) => m[0].replace(URL_TRAILING_PUNCTUATION, ""));
  return urls.find((url) => url.includes("/invite/")) ?? urls[0] ?? raw.trim();
}

/**
 * Accepts either a bare invite token or a full invite URL (T-71). Share links are
 * `<base_url>/invite/<token>` — possibly under a path prefix — so if the pasted text contains an
 * `/invite/` segment, take everything after the last one and strip any trailing slash, query, or
 * fragment that rode along. A bare token (no `/invite/`) is returned trimmed, unchanged. Mirrors
 * the Android app's extractInviteToken.
 */
export function extractInviteToken(raw: string): string {
  const trimmed = raw.trim();
  const marker = "/invite/";
  const idx = trimmed.lastIndexOf(marker);
  const afterPrefix = idx >= 0 ? trimmed.slice(idx + marker.length) : trimmed;
  return afterPrefix.split("?")[0].split("#")[0].replace(/\/+$/, "");
}
