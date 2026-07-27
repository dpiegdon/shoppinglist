/**
 * The English message catalog — the source of truth for both the text and the KEY SET.
 *
 * `MessageKey` is derived from this object, so `t()` calls are checked at compile time and a typo
 * is a build error rather than a blank label. Other languages are `Partial<Record<MessageKey,
 * string>>`: a missing entry falls back to English at runtime, which is what allows a translation
 * to land incrementally instead of having to be complete before it can be merged (T-124).
 *
 * Conventions:
 * - Keys are dotted and grouped by screen, so a translator can work through a file in the order a
 *   user meets the strings.
 * - `{placeholder}` interpolation. Placeholders carry no grammatical agreement — see T-123: every
 *   count sits after a label rather than inside a sentence, which is why this catalog needs no
 *   plural machinery at all and translators get free word order.
 * - No string is assembled from fragments at a call site. A sentence built by concatenation cannot
 *   be reordered by a translator, and word order is exactly what differs between languages.
 */
export const en = {
  // ---- generic actions & states, reused across screens ----
  "action.cancel": "Cancel",
  "action.save": "Save",
  "action.delete": "Delete",
  "action.close": "Close",
  "action.retry": "Retry",
  "action.back": "Back",
  "common.loading": "Loading…",

  // ---- login / register ----
  "login.title": "Sign in",
  "login.email": "Email",
  "login.password": "Password",
  "login.submit": "Sign in",
  "login.register": "Create account",
  "login.serverUrl": "Server URL",
  "login.language": "Language",

  // ---- overview ----
  "overview.title": "Lists",
  "overview.empty": "No lists yet. Create one to get started.",

  // ---- list ----
  "list.registry.empty": "No items found.",
  "list.categoryFixed": "Casing fixed in {category}: {count}",

  // ---- last seen (T-123: abbreviated units never inflect, so no plural rule is needed) ----
  "lastSeen.activeNow": "Active now",
  "lastSeen.minutes": "{count} min ago",
  "lastSeen.hours": "{count} h ago",
  "lastSeen.days": "{count} d ago",

  // ---- settings ----
  "settings.title": "Settings",
  "settings.language": "Language",
  "settings.sessions": "Sessions",

  // ---- admin ----
  "admin.title": "Admin",
  "admin.sessionCount": "Sessions: {count}",
  "admin.isAdmin": "(admin)",
} as const;

export type MessageKey = keyof typeof en;

/** A non-English catalog. Partial by design — missing keys fall back to English (see above). */
export type Catalog = Partial<Record<MessageKey, string>>;
