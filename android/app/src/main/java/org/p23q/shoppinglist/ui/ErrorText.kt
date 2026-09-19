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
        // A different rule, so a different code and a different sentence (T-199): the account's
        // default_currency is an ISO code, an expenses list's currency is a free-text label.
        "invalid_list_currency" to UiText.res(R.string.api_error_invalid_list_currency),
        "invalid_price" to UiText.res(R.string.api_error_invalid_price),
        "invite_expired" to UiText.res(R.string.api_error_invite_expired),
        "invite_revoked" to UiText.res(R.string.api_error_invite_revoked),
        "invite_used" to UiText.res(R.string.api_error_invite_used),
        "invite_not_found" to UiText.res(R.string.api_error_invite_not_found),
        "invite_email_mismatch" to UiText.res(R.string.api_error_invite_email_mismatch),
        "list_closed" to UiText.res(R.string.api_error_list_closed),
        "list_open" to UiText.res(R.string.listprops_leave_blocked),
        "voted_to_close" to UiText.res(R.string.api_error_voted_to_close),
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
     * Why a row this device had queued was refused, for the row itself to show (T-200). [code] is
     * what the server answered, [who] the participant it named, already labelled the way the screen
     * labels people. Null when there is nothing better to say than "not saved": an unknown code, or
     * participant_frozen from a server too old to name the account.
     *
     * Separate from [of] because there is no exception left by then — the refusal was stored on the
     * row when the push failed, possibly days earlier, with nobody watching.
     */
    fun refusal(code: String?, who: String?): UiText? = when {
        code == null -> null
        code == "participant_frozen" -> who?.let { UiText.res(R.string.expense_error_frozen, it) }
        else -> byCode[code]
    }

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
