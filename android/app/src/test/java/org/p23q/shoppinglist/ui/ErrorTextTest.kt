package org.p23q.shoppinglist.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.api.ApiException
import java.io.IOException

/** Server failures in the app's language (see ErrorText), never the server's English text. */
class ErrorTextTest {

    private fun apiError(code: String) = ApiException(code, "English text from the server", 409)

    @Test
    fun `a known code becomes its translated message`() {
        assertEquals(UiText.res(R.string.api_error_invite_used), ErrorText.of(apiError("invite_used"), R.string.redeem_msg_failed))
    }

    @Test
    fun `a code with a count carries it`() {
        assertEquals(
            UiText.res(R.string.settings_msg_initials_too_long, 3),
            ErrorText.of(apiError("invalid_initials"), R.string.settings_msg_initials_failed),
        )
    }

    @Test
    fun `the two currency rules say different things`() {
        // One code for both used to tell someone whose list label was too long to type "EUR" (T-199).
        assertEquals(
            UiText.res(R.string.api_error_invalid_currency),
            ErrorText.of(apiError("invalid_currency"), R.string.redeem_msg_failed),
        )
        assertEquals(
            UiText.res(R.string.api_error_invalid_list_currency),
            ErrorText.of(apiError("invalid_list_currency"), R.string.redeem_msg_failed),
        )
    }

    @Test
    fun `a screen can say something narrower for a code`() {
        val text = ErrorText.of(
            apiError("invalid_credentials"),
            R.string.settings_msg_password_failed,
            mapOf("invalid_credentials" to R.string.settings_msg_password_incorrect),
        )
        assertEquals(UiText.res(R.string.settings_msg_password_incorrect), text)
    }

    @Test
    fun `a list the account can no longer write to is named, not left as a bare not-saved (T-214)`() {
        assertEquals(UiText.res(R.string.api_error_unknown_list), ErrorText.of(apiError("unknown_list"), R.string.redeem_msg_failed))
    }

    @Test
    fun `an unknown code falls back to the screen's own message`() {
        assertEquals(UiText.res(R.string.redeem_msg_failed), ErrorText.of(apiError("invalid_cursor"), R.string.redeem_msg_failed))
    }

    @Test
    fun `a network failure falls back too, rather than showing the platform's English`() {
        assertEquals(
            UiText.res(R.string.redeem_msg_failed),
            ErrorText.of(IOException("Unable to resolve host"), R.string.redeem_msg_failed),
        )
    }
}
