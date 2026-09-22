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
  "app.title": "Tuppu",

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
  "login.toggleToLogin": "Already have an account? Log in",
  "login.registrationDisabled": "Registration is disabled on this server.",
  "login.getAndroidApp": "Get the Android app",
  "login.error.generic": "Something went wrong. Please try again.",

  // ---- redeem an invite ----
  "redeem.title": "Join a list",
  "redeem.hint": "Paste the invite code, or open the invite link directly.",
  "redeem.code": "Invite code or link",
  "redeem.join": "Join list",
  "redeem.error": "Couldn't redeem invite",

  // ---- overview / list ----
  "common.loading": "Loading…",
  "overview.empty": "No lists yet. Create one to get started.",
  // Invites addressed to this account, offered on the overview (T-233). Ignoring is a
  // client-side choice: the invite moves to the greyed section at the bottom, still joinable.
  "overview.invites": "Invitations",
  "overview.invitesIgnored": "Ignored",
  "overview.invite.from": "From {initials}",
  "overview.invite.expiresMinutes": "Expires in {count} min",
  "overview.invite.expiresHours": "Expires in {count} h",
  "overview.invite.expiresDays": "Expires in {count} d",
  "list.registry.empty": "No items found.",
  "list.categoryFixed": "Casing fixed in {category}: {count}",

  // ---- sync health ----
  "sync.syncing": "Syncing…",
  "sync.synced": "Synced {ago}",
  "sync.failed": "Sync failed",
  "sync.failedSince": "Sync failed · last ok {ago}",
  "sync.never": "Not synced yet",
  "sync.now": "Sync now",

  // ---- protocol version: this build is older than the server (T-244) ----
  // The whole-page notice that replaces the app once the automatic reload has not helped, and the
  // server's own code for the refusal that raised it.
  "outdated.title": "Update required",
  "outdated.body": "The server has been updated. Reload this page to continue.",
  "outdated.reload": "Reload",
  "apiError.clientOutdated": "This app is too old for this server",

  // ---- sessions ----
  "lastSeen.activeNow": "Active now",

  // ---- item dialog ----
  "item.add": "Add item",
  "item.edit": "Edit item",
  "item.name": "Name",
  "item.category": "Category",
  "item.stores": "Stores",
  "item.addStore": "Add store",
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
  "item.saveFailed": "Failed to save. Please try again.",
  // Refused before the row is ever pushed (T-273): item names are unique per list, case-
  // insensitively, on both clients — this is the web's twin of Android's item_msg_duplicate_name.
  "item.duplicateName": "An item named \"{name}\" already exists",
  // Reuses Android's item_delete_title/item_delete_body word for word (T-277): the web used to
  // delete on the first press, with no confirmation and no undo. confirm() shows both lines at
  // once, so the two keys stay separate strings — matching Android's — rather than one sentence.
  "item.deleteTitle": "Delete item?",
  "item.deleteBody": "“{name}” will be removed from this list.",
  "item.priceInvalid": "Enter an amount like 1.99",
  "item.currencyInvalid": "Use a 3-letter code like EUR",

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
  // Renaming a category onto another existing one merges them irreversibly (T-270): every item in
  // both categories ends up in one. Was a hard-coded English confirm() call, the only user-facing
  // string in the client that bypassed the catalog.
  "listProps.mergeCategoryConfirm": "Merge into \"{category}\"? Both categories will become one.",
  "listProps.clearChecked": "Clear checked",
  // Separate from the heading above: the BUTTON carries the count, the section title must not.
  "listProps.clearCheckedCount": "Clear checked ({count})",
  "listProps.kindSwitchHelp": "Switching only changes which fields are shown — nothing is deleted, so you can switch back.",
  "listProps.notes": "Notes",
  "listProps.notesPlaceholder": "Gate code, store hours, anything worth remembering…",
  "listProps.members": "Members",
  "listProps.inviteByEmail": "Invite by email",
  "listProps.inviteLink": "Invite link",
  "listProps.inviteLinkFor": "Invite link for {email} — send it to them. Only that email can redeem it, and it expires in 7 days.",
  "listProps.clearCheckedHelp": "Move every checked item back to the backlog.",
  "listProps.saveNotes": "Save notes",
  "listProps.pendingInvites": "Pending invites",
  "listProps.leaveList": "Leave list",
  "listProps.membersFailed": "Failed to load members.",
  "listProps.inviteFailed": "Couldn't send invite",
  "listProps.leaveConfirm": "Leave this list? You will lose access to it.",

  // ---- generic actions ----
  "action.cancel": "Cancel",
  "action.save": "Save",
  "action.delete": "Delete",
  "action.refresh": "Refresh",
  "action.revoke": "Revoke",
  "action.undo": "Undo",
  "action.invite": "Invite",
  "action.join": "Join",
  "action.ignore": "Ignore",
  "action.create": "Create",
  "action.duplicate": "Duplicate",
  "action.add": "Add",
  "action.copy": "Copy",
  "action.copied": "Copied!",
  "common.saved": "Saved.",
  "error.generic": "Something went wrong",

  // ---- navigation ----
  "nav.overview": "Overview",
  "nav.joinList": "Join a list",
  "nav.serverAdmin": "Server admin",
  "nav.about": "About",
  "nav.logOut": "Log out",
  "nav.menu": "Menu",

  // ---- overview ----
  "overview.newList": "New list",
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
  // can turn it around — "back" points rightward in Arabic.
  "list.allListsLink": "← All lists",
  "list.backToAllLists": "Back to all lists",
  "list.allItems": "All items",
  "list.search": "Search",
  "list.empty": "Nothing on this list yet. Add an item to get started.",
  "list.checkedOff": "Item checked off.",
  "list.showChecked": "Show checked",
  "list.addItem": "Add item",

  // ---- settings ----
  "settings.language": "Language",
  "settings.title": "Settings",
  "settings.defaultCurrency": "Default currency",
  "settings.currentlyCached": "Currently cached: {currency}",
  "settings.thisDevice": "(this device)",
  "settings.initials": "Displayed initials",
  "settings.changePassword": "Change password",
  "settings.currentPassword": "Current password",
  "settings.newPassword": "New password",
  "settings.passwordChanged": "Password changed",
  "settings.changeEmail": "Change email",
  "settings.newEmail": "New email",
  "settings.emailChanged": "Email changed",
  "settings.sessions": "Sessions",
  "settings.deleteAccount": "Delete account",
  "settings.deleteMyAccount": "Delete my account",
  "settings.password": "Password",
  "settings.deleteConfirm": "This permanently deletes your account. Are you sure?",

  // ---- about (T-224) ----
  // Under the cuneiform sign on About, and the accessible label for it there and on login (T-225).
  // The same text in every language, like the licence line: a transliteration, not a translation.
  "about.transliteration": "ṭuppu",
  "about.tagline": "The Akkadian word for a clay tablet — the thing a list was pressed into.",
  "about.version": "Version {version}",
  "about.license": "MIT, © 2026 David R. Piegdon",

  // ---- admin ----
  "admin.title": "Server admin",
  "admin.registration": "Registration",
  "admin.registrationHelp": "Runtime override — resets to the server\'s configured default on restart.",
  "admin.newPasswordFor": "New password for {email} — shown once, send it securely:",
  "admin.deleteUserBody": "Permanently delete {email} and all of their data. This can\'t be undone.",
  "admin.deleteUserConfirm": "Delete {email}",
  "admin.allowNewAccounts": "Allow new accounts",
  "admin.users": "Users",
  "admin.showUsers": "Show registered users",
  "admin.userCount": "Registered users: {count}",
  "admin.yourPassword": "Your password (for reset/delete)",
  "admin.deleteUserTitle": "Delete user?",
  "admin.resetPassword": "Reset password",
  "admin.passwordRequired": "Enter your password to reset or delete a user.",
  "admin.loadFailed": "Couldn't load admin data",
  "admin.updateFailed": "Couldn't update",
  "admin.resetFailed": "Couldn't reset password",
  "admin.deleteFailed": "Couldn't delete user",
  "admin.sessionCount": "Sessions: {count}",
  "admin.isAdmin": "(admin)",

  // --- Expense lists (T-155) ---
  "listKind.shopping": "Shopping list",
  "listKind.checklist": "Checklist",
  "listKind.expenses": "Ledger",
  "overview.kind.expenses": "Shared money: who paid, who received, who owes what.",
  "overview.currencyHelp": "Used for every entry on this list, and fixed once the list exists.",
  "listProps.kind.expenses": "Shared money, with a balance per member.",
  "listProps.kindFixed": "A ledger keeps its type and its currency for life.",
  "expense.currency": "Currency",
  "expense.what": "What",
  "expense.total": "Total",
  "expense.date": "Date",
  "expense.type": "Type",
  "expense.type.expense": "Expense",
  "expense.type.income": "Income",
  "expense.type.transfer": "Transfer",
  "expense.paidBy": "Paid by",
  "expense.paidFor": "For",
  "expense.receivedBy": "Received by",
  "expense.creditedTo": "Credited to",
  "expense.from": "From",
  "expense.to": "To",
  "expense.new": "New entry",
  "expense.edit": "Edit entry",
  "expense.add": "Add entry",
  "expense.empty": "No entries yet. Add one to get started.",
  "expense.balances": "Balances",
  "expense.entries": "Entries",
  "expense.totalSpent": "Net spent",
  "expense.yourBalance": "Your balance",
  "expense.paidAndShare": "paid {paid} · share {share}",
  "expense.paidShareSettled": "paid {paid} · share {share} · settled {settled}",
  "expense.spentBreakdown": "Expenses {spent} · Income {income}",
  "expense.formerMember": "Former member {number}",
  "expense.forEveryone": "everyone",
  "expense.rowBy": "paid by {by} · for {for}",
  "expense.rowReceivedBy": "received by {by} · for {for}",
  "expense.rowTransfer": "{from} → {to}",
  "expense.soloHint": "It is just you on this list, so the whole amount is yours.",
  "expense.error.aboveTotal": "The amounts entered are more than the total.",
  "expense.error.doesNotAddUp": "The amounts entered add up to {sum}, not {total}.",
  "expense.error.useSum": "Set the total to {sum}",
  "expense.error.nobody": "Choose at least one person.",
  "expense.error.sameMember": "Choose two different people.",
  "expense.error.needTwoMembers": "A transfer needs two different people on this list.",
  "expense.error.total": "Enter a total.",

  // --- Closing an expenses list (T-159) ---
  "expense.closing": "Closing",
  "expense.closingHelp": "Everyone has to agree. Once you agree, you can't change anything on the list any more, and nothing involving you can be added, changed or deleted. Once closed, the list is a read-only record and can be left.",
  "expense.agreeToClose": "Agree to close",
  "expense.withdrawVote": "Withdraw",
  "expense.agreeCount": "Votes to close: {voted} of {total}",
  "expense.closedOn": "Closed on {date}",
  "expense.closed": "Closed",
  "expense.voteFailed": "Could not record your vote.",
  "expense.frozenVoter": "agreed to close — amounts fixed",
  "expense.frozenFormer": "no longer a member — amounts fixed",
  "expense.error.frozen": "The amounts of {who} are fixed: they have agreed to close the list, or have left it.",
  "expense.notSaved": "Not saved to the list",
  "expense.error.closed": "This list has been closed and can no longer be changed.",
  "expense.settleUp": "Settle up",
  "expense.transfer": "{from} pays {to}",
  "expense.reimburse": "Reimburse",
  "expense.allSettled": "All settled",
  "expense.settlement": "Settlement",
  "listProps.leaveBlocked": "A ledger can only be left once it is closed.",
  "apiError.invalidCredentials": "Incorrect email or password",
  "settings.passwordIncorrect": "Current password is incorrect",
  "settings.passwordWrong": "Password is incorrect",
  "settings.initialsTooLong": "Initials must be {count} characters or fewer",
  "list.editItem": "Edit {name}",
  "list.lastTouchedBy": "Last touched by {email}",
  "item.removeStore": "Remove {store}",
  "listProps.renameCategory": "Rename {category}",
  "apiError.emailTaken": "An account with this email already exists",
  "apiError.registrationDisabled": "This server isn't accepting new accounts",
  "apiError.invalidEmail": "That email address isn't valid",
  "apiError.invalidPassword": "Passwords need at least 8 characters",
  "apiError.invalidName": "Enter a name",
  "apiError.invalidNotes": "The notes are too long",
  "apiError.invalidCurrency": "Enter a three-letter currency code, such as EUR",
  "apiError.invalidListCurrency": "Enter a currency for this list, up to 32 characters",
  "apiError.invalidPrice": "That price isn't valid",
  // Four codes the wire contract always listed but neither client mapped (T-271): a pushed row
  // could 422 with one of these and fall back to the generic "not saved" with no reason at all.
  "apiError.invalidExpense": "That entry isn't valid",
  "apiError.invalidField": "That value isn't valid",
  "apiError.invalidStatus": "That status isn't valid",
  "apiError.invalidDeviceLabel": "That device name is too long",
  "apiError.inviteExpired": "This invite has expired",
  "apiError.inviteRevoked": "This invite was withdrawn",
  "apiError.inviteUsed": "This invite has already been used",
  "apiError.inviteNotFound": "This invite doesn't exist",
  "apiError.inviteEmailMismatch": "This invite is for a different email address",
  "apiError.cannotDeleteExpenseList": "A ledger can't be deleted. Close it, then leave it.",
  "apiError.notAMember": "You're no longer a member of this list",
  "apiError.unknownList": "This list is no longer available to you",
  "apiError.notAdmin": "Only an admin can do that",
  "apiError.cannotDeleteAdmin": "Admins can't be deleted here",
  "apiError.cannotDeleteSelf": "Delete your own account in Settings",
  "apiError.accountNotFound": "That account no longer exists",
  "apiError.sessionNotFound": "That session has already ended",
  "apiError.serverBusy": "The server is busy. Try again in a moment.",
  "apiError.payloadTooLarge": "That's too much to send at once",
  "apiError.votedToClose": "You've agreed to close this list, so you can't change it any more. Withdraw your vote to make changes.",
  "apiError.notAnExpensesList": "Only a ledger can be voted closed.",
  "expense.deleteBlocked": "This entry can't be deleted: it involves someone whose amounts are fixed.",
} as const;

export type MessageKey = keyof typeof en;

/** A non-English catalog. Partial by design — missing keys fall back to English (see above). */
export type Catalog = Partial<Record<MessageKey, string>>;
