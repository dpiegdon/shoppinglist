/**
 * The English message catalog — the source of truth for both the text and the KEY SET.
 *
 * `MessageKey` is derived from this object, so `t()` calls are checked at compile time and a typo
 * is a build error rather than a blank label. Other languages are `Partial<Record<MessageKey,
 * string>>`: a missing entry falls back to English at runtime, which is what allows a translation
 * to land incrementally instead of having to be complete before it can be merged (T-124).
 *
 * Conventions:
 * - Keys are dotted and grouped by screen, so a translator can work through the file in roughly
 *   the order a user meets the strings.
 * - `{placeholder}` interpolation. Placeholders carry no grammatical agreement — see T-123: every
 *   count sits after a label rather than inside a sentence, which is why this catalog needs no
 *   plural machinery at all and translators get free word order.
 * - No string is assembled from fragments at a call site. A sentence built by concatenation cannot
 *   be reordered by a translator, and word order is exactly what differs between languages.
 * - Every key here is actually used. An unused key is a string a translator will be asked to
 *   translate for nothing, so the catalog is pruned rather than pre-populated.
 */
export const en = {
  "app.title": "Shopping List",

  // ---- shared relative-time labels ----
  // One set, used by both the session list and the sync indicator: the wording is identical, so
  // separate keys would mean translating the same four labels twice and inviting them to drift.
  // Abbreviated units never inflect, in any language (T-123).
  "ago.justNow": "just now",
  "ago.minutes": "{count} min ago",
  "ago.hours": "{count} h ago",
  "ago.days": "{count} d ago",

  // ---- login / register ----
  "login.email": "Email",
  "login.password": "Password",
  "login.submit": "Log in",
  "login.register": "Create account",
  "login.toggleToRegister": "Need an account? Register",
  "login.toggleToLogin": "Have an account? Log in",
  "login.registrationDisabled": "Registration is disabled on this server.",
  "login.getAndroidApp": "Get the Android app",
  "login.error.generic": "Something went wrong. Please try again.",

  // ---- redeem an invite ----
  "redeem.title": "Join a shopping list",
  "redeem.hint": "Paste the invite code, or open the invite link directly.",
  "redeem.code": "Invite code",
  "redeem.join": "Join list",
  "redeem.error": "Could not redeem this invite.",

  // ---- overview / list ----
  "common.loading": "Loading…",
  "overview.empty": "No lists yet. Create one to get started.",
  "list.registry.empty": "No items found.",
  "list.categoryFixed": "Casing fixed in {category}: {count}",

  // ---- sync health ----
  "sync.syncing": "Syncing…",
  "sync.synced": "Synced {ago}",
  "sync.failed": "Sync failed",
  "sync.failedSince": "Sync failed · last ok {ago}",
  "sync.never": "Not synced yet",
  "sync.now": "Sync now",

  // ---- sessions ----
  "lastSeen.activeNow": "Active now",

  // ---- item dialog ----
  "item.add": "Add item",
  "item.edit": "Edit item",
  "item.name": "Name",
  "item.category": "Category",
  "item.stores": "Stores",
  "item.addStore": "Add a store",
  "item.quantity": "Quantity",
  "item.price": "Price",
  "item.currency": "Currency",
  "item.note": "Note",
  "item.statusLabel": "Status",
  // The ONE canonical wording for the three item states, matching the Android client word for
  // word (T-124). The wire values stay the English identifiers backlog/todo/checked (see
  // docs/wire-contract.md); only these labels are translated.
  "item.status.todo": "Todo",
  "item.status.checked": "Checked",
  "item.status.backlog": "Backlog",
  // Shown beside the Backlog option where there is room. "Backlog" alone is the term this app is
  // least likely to survive translation without a gloss, so the gloss is a string of its own
  // rather than being welded into the label and dragged onto space-constrained surfaces.
  "item.status.backlogHint": "Not on the list yet",
  "item.addAnother": "Add another",
  "item.saveFailed": "Failed to save. Please try again.",

  // ---- list properties ----
  "listProps.title": "List properties",
  "listProps.name": "Name",
  "listProps.type": "Type",
  "listProps.kind.checklist": "Items have a name, category and note.",
  "listProps.kind.shopping": "Items also have stores, quantity and price.",
  "listProps.makeShopping": "Make shopping list",
  "listProps.makeChecklist": "Make checklist",
  "listProps.categories": "Categories",
  "listProps.categoriesHelp": "Rename to fix casing or merge; use the arrows to set the order items are grouped in.",
  "listProps.noCategories": "No categories yet.",
  "listProps.moveUp": "Move up",
  "listProps.moveDown": "Move down",
  "listProps.removeFromOrder": "Remove from order",
  "listProps.addToOrder": "Add to order",
  "listProps.addCategory": "Add category…",
  "listProps.clearChecked": "Clear checked",
  // Separate from the heading above: the BUTTON carries the count, the section title must not.
  "listProps.clearCheckedCount": "Clear checked ({count})",
  "listProps.kindSwitchHelp": "Switching only changes which fields are shown — nothing is deleted, so you can switch back.",
  "listProps.notes": "Notes",
  "listProps.notesPlaceholder": "Gate code, store hours, anything worth remembering…",
  "listProps.members": "Members",
  "listProps.inviteByEmail": "Invite by email…",
  "listProps.inviteLink": "Invite link",
  "listProps.inviteLinkFor": "Invite link for {email} — send it to them. Only that email can redeem it, and it expires in 7 days.",
  "listProps.clearCheckedHelp": "Move every checked item back to the backlog.",
  "listProps.saveNotes": "Save notes",
  "listProps.pendingInvites": "Pending invites",
  "listProps.leaveList": "Leave list",
  "listProps.membersFailed": "Failed to load members.",
  "listProps.inviteFailed": "Failed to send invite.",
  "listProps.leaveConfirm": "Leave this list? You will lose access to it.",

  // ---- generic actions ----
  "action.cancel": "Cancel",
  "action.save": "Save",
  "action.delete": "Delete",
  "action.revoke": "Revoke",
  "action.undo": "Undo",
  "action.invite": "Invite",
  "action.create": "Create",
  "action.duplicate": "Duplicate",
  "action.add": "Add",
  "action.copy": "Copy",
  "action.copied": "Copied!",
  "common.saved": "Saved.",
  "error.generic": "Something went wrong.",

  // ---- navigation ----
  "nav.overview": "Overview",
  "nav.joinList": "Join a list",
  "nav.logOut": "Log out",
  "nav.menu": "Menu",

  // ---- overview ----
  "overview.title": "Your lists",
  "overview.newList": "New list",
  "overview.newListButton": "+ New list",
  "overview.name": "Name",
  "overview.type": "Type",
  "overview.kind.checklist": "Just names, categories and notes.",
  "overview.kind.shopping": "Adds stores, quantity and price to each item.",

  // ---- list ----
  "list.notFound": "List not found (or you no longer have access).",
  "list.backToOverview": "Back to overview",
  // Two routes back to the overview, worded differently on purpose: this is the visible breadcrumb
  // above the title, `backToAllLists` below is the tooltip on the title itself (T-109).
  // The arrow is part of the translation rather than the markup so that a right-to-left language
  // can turn it around — "back" points rightward in Arabic. Same for `admin.backToSettings`.
  "list.allListsLink": "← All lists",
  "list.backToAllLists": "Back to all lists",
  "list.allItems": "All items",
  "list.search": "Search items…",
  "list.empty": "Nothing on this list yet. Add an item to get started.",
  "list.checkedOff": "Item checked off.",
  "list.showChecked": "Show checked",
  "list.addItem": "+ Add item",

  // ---- settings ----
  "settings.language": "Language",
  "settings.title": "Account settings",
  "settings.serverAdmin": "Server admin",
  "settings.defaultCurrency": "Default currency",
  "settings.currentlyCached": "Currently cached: {currency}",
  "settings.thisDevice": "(this device)",
  "settings.initials": "Displayed initials",
  "settings.changePassword": "Change password",
  "settings.currentPassword": "Current password",
  "settings.newPassword": "New password",
  "settings.passwordChanged": "Password changed.",
  "settings.changeEmail": "Change email",
  "settings.newEmail": "New email",
  "settings.emailChanged": "Email changed.",
  "settings.sessions": "Sessions",
  "settings.deleteAccount": "Delete account",
  "settings.openServerAdmin": "Open server admin",
  "settings.deleteMyAccount": "Delete my account",
  "settings.password": "Password",
  "settings.deleteConfirm": "This permanently deletes your account. Are you sure?",

  // ---- admin ----
  "admin.title": "Server admin",
  "admin.backToSettings": "← Settings",
  "admin.registration": "Registration",
  "admin.registrationHelp": "Runtime override — resets to the server\'s configured default on restart.",
  "admin.newPasswordFor": "New password for {email} — shown once, send it to them securely:",
  "admin.deleteUserBody": "Permanently delete {email} and all of their data. This can\'t be undone.",
  "admin.deleteUserConfirm": "Delete {email}",
  "admin.allowNewAccounts": "Allow new accounts",
  "admin.users": "Users",
  "admin.yourPassword": "Your password (required for reset/delete)",
  "admin.deleteUserTitle": "Delete user?",
  "admin.resetPassword": "Reset password",
  "admin.passwordRequired": "Enter your password to reset or delete a user.",
  "admin.loadFailed": "Failed to load admin data.",
  "admin.updateFailed": "Failed to update.",
  "admin.resetFailed": "Failed to reset password.",
  "admin.deleteFailed": "Failed to delete user.",
  "admin.sessionCount": "Sessions: {count}",
  "admin.isAdmin": "(admin)",
} as const;

export type MessageKey = keyof typeof en;

/** A non-English catalog. Partial by design — missing keys fall back to English (see above). */
export type Catalog = Partial<Record<MessageKey, string>>;
