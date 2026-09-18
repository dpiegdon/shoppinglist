import { ApiError } from "../api/client";
import type { MessageKey } from "./messages/en";

type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

/**
 * What a server error says, in the app's language.
 *
 * The server's error envelope carries an English `message` for developers and a `code` for clients.
 * Screens used to show the message, so a German user with a wrong password read English. Now a
 * user-facing code maps to a translated message here, and every other failure — a code no person
 * can cause from the UI, a network error — shows the screen's own translated fallback. Android's
 * ErrorText holds the same table, with the same English, which the cross-client test keeps equal.
 */
const BY_CODE: Record<string, MessageKey | [MessageKey, Record<string, number>]> = {
  invalid_credentials: "apiError.invalidCredentials",
  email_taken: "apiError.emailTaken",
  registration_disabled: "apiError.registrationDisabled",
  invalid_email: "apiError.invalidEmail",
  invalid_password: "apiError.invalidPassword",
  invalid_name: "apiError.invalidName",
  invalid_notes: "apiError.invalidNotes",
  invalid_initials: ["settings.initialsTooLong", { count: 3 }],
  invalid_currency: "apiError.invalidCurrency",
  invalid_price: "apiError.invalidPrice",
  invite_expired: "apiError.inviteExpired",
  invite_revoked: "apiError.inviteRevoked",
  invite_used: "apiError.inviteUsed",
  invite_not_found: "apiError.inviteNotFound",
  invite_email_mismatch: "apiError.inviteEmailMismatch",
  list_closed: "expense.error.closed",
  list_open: "listProps.leaveBlocked",
  voted_to_close: "apiError.votedToClose",
  cannot_delete_expense_list: "apiError.cannotDeleteExpenseList",
  not_a_member: "apiError.notAMember",
  not_admin: "apiError.notAdmin",
  cannot_delete_admin: "apiError.cannotDeleteAdmin",
  cannot_delete_self: "apiError.cannotDeleteSelf",
  account_not_found: "apiError.accountNotFound",
  session_not_found: "apiError.sessionNotFound",
  server_busy: "apiError.serverBusy",
  payload_too_large: "apiError.payloadTooLarge",
};

/**
 * [err] as a message for the user: the code's translation if it has one, else [fallback].
 * [overrides] lets a screen say something narrower for a code — "Current password is incorrect"
 * rather than "Incorrect email or password" on the change-password form.
 */
export function errorMessage(
  t: Translate,
  err: unknown,
  fallback: MessageKey,
  overrides: Partial<Record<string, MessageKey>> = {},
): string {
  if (err instanceof ApiError) {
    const entry = overrides[err.code] ?? BY_CODE[err.code];
    if (typeof entry === "string") return t(entry);
    if (entry) return t(entry[0], entry[1]);
  }
  return t(fallback);
}
