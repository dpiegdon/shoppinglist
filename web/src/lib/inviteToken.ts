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
