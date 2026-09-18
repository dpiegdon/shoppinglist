package org.p23q.shoppinglist.ui

import androidx.annotation.StringRes
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.api.ApiException

/**
 * What a failure says to the user, in the app's language.
 *
 * The server's error envelope carries an English `message` for developers and a `code` for clients.
 * Screens used to show the message, so a German user with a wrong password read English — and a
 * network failure showed the platform's own English ("Unable to resolve host"). Now a user-facing
 * code maps to a translated message here, and everything else shows the screen's own translated
 * fallback. The web's i18n/apiErrors.ts holds the same table, with the same English, and the
 * cross-client test keeps the translations equal.
 */
object ErrorText {

    private val byCode: Map<String, UiText> = mapOf(
        "invalid_credentials" to UiText.res(R.string.login_msg_incorrect_credentials),
        "email_taken" to UiText.res(R.string.api_error_email_taken),
        "registration_disabled" to UiText.res(R.string.api_error_registration_disabled),
        "invalid_email" to UiText.res(R.string.api_error_invalid_email),
        "invalid_password" to UiText.res(R.string.api_error_invalid_password),
        "invalid_name" to UiText.res(R.string.api_error_invalid_name),
        "invalid_notes" to UiText.res(R.string.api_error_invalid_notes),
        "invalid_initials" to UiText.res(R.string.settings_msg_initials_too_long, 3),
        "invalid_currency" to UiText.res(R.string.api_error_invalid_currency),
        "invalid_price" to UiText.res(R.string.api_error_invalid_price),
        "invite_expired" to UiText.res(R.string.api_error_invite_expired),
        "invite_revoked" to UiText.res(R.string.api_error_invite_revoked),
        "invite_used" to UiText.res(R.string.api_error_invite_used),
        "invite_not_found" to UiText.res(R.string.api_error_invite_not_found),
        "invite_email_mismatch" to UiText.res(R.string.api_error_invite_email_mismatch),
        "list_closed" to UiText.res(R.string.api_error_list_closed),
        "list_open" to UiText.res(R.string.listprops_leave_blocked),
        "cannot_delete_expense_list" to UiText.res(R.string.api_error_cannot_delete_expense_list),
        "not_a_member" to UiText.res(R.string.api_error_not_a_member),
        "not_admin" to UiText.res(R.string.api_error_not_admin),
        "cannot_delete_admin" to UiText.res(R.string.api_error_cannot_delete_admin),
        "cannot_delete_self" to UiText.res(R.string.api_error_cannot_delete_self),
        "account_not_found" to UiText.res(R.string.api_error_account_not_found),
        "session_not_found" to UiText.res(R.string.api_error_session_not_found),
        "server_busy" to UiText.res(R.string.api_error_server_busy),
        "payload_too_large" to UiText.res(R.string.api_error_payload_too_large),
    )

    /**
     * [e] as a message for the user: the code's translation if it has one, else [fallback].
     * [overrides] lets a screen say something narrower for a code — "Current password is incorrect"
     * rather than "Incorrect email or password" on the change-password form.
     */
    fun of(e: Throwable, @StringRes fallback: Int, overrides: Map<String, Int> = emptyMap()): UiText {
        val code = (e as? ApiException)?.code ?: return UiText.res(fallback)
        overrides[code]?.let { return UiText.res(it) }
        return byCode[code] ?: UiText.res(fallback)
    }
}
