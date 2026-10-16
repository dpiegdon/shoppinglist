package org.p23q.shoppinglist.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.api_error_client_outdated
import org.p23q.shoppinglist.resources.api_error_invalid_currency
import org.p23q.shoppinglist.resources.api_error_invalid_device_label
import org.p23q.shoppinglist.resources.api_error_invalid_expense
import org.p23q.shoppinglist.resources.api_error_invalid_field
import org.p23q.shoppinglist.resources.api_error_invalid_list_currency
import org.p23q.shoppinglist.resources.api_error_invalid_status
import org.p23q.shoppinglist.resources.api_error_invite_used
import org.p23q.shoppinglist.resources.api_error_unknown_list
import org.p23q.shoppinglist.resources.redeem_msg_failed
import org.p23q.shoppinglist.resources.settings_msg_initials_failed
import org.p23q.shoppinglist.resources.settings_msg_initials_too_long
import org.p23q.shoppinglist.resources.settings_msg_password_failed
import org.p23q.shoppinglist.resources.settings_msg_password_incorrect
import java.io.IOException

/** Server failures in the app's language (see ErrorText), never the server's English text. */
class ErrorTextTest {

    private fun apiError(code: String) = ApiException(code, "English text from the server", 409)

    @Test
    fun `a known code becomes its translated message`() {
        assertEquals(UiText.res(Res.string.api_error_invite_used), ErrorText.of(apiError("invite_used"), Res.string.redeem_msg_failed))
    }

    @Test
    fun `a code with a count carries it`() {
        assertEquals(
            UiText.res(Res.string.settings_msg_initials_too_long, 3),
            ErrorText.of(apiError("invalid_initials"), Res.string.settings_msg_initials_failed),
        )
    }

    @Test
    fun `the two currency rules say different things`() {
        // One code for both used to tell someone whose list label was too long to type "EUR" (T-199).
        assertEquals(
            UiText.res(Res.string.api_error_invalid_currency),
            ErrorText.of(apiError("invalid_currency"), Res.string.redeem_msg_failed),
        )
        assertEquals(
            UiText.res(Res.string.api_error_invalid_list_currency),
            ErrorText.of(apiError("invalid_list_currency"), Res.string.redeem_msg_failed),
        )
    }

    @Test
    fun `a screen can say something narrower for a code`() {
        val text = ErrorText.of(
            apiError("invalid_credentials"),
            Res.string.settings_msg_password_failed,
            mapOf("invalid_credentials" to Res.string.settings_msg_password_incorrect),
        )
        assertEquals(UiText.res(Res.string.settings_msg_password_incorrect), text)
    }

    @Test
    fun `a list the account can no longer write to is named, not left as a bare not-saved (T-214)`() {
        assertEquals(UiText.res(Res.string.api_error_unknown_list), ErrorText.of(apiError("unknown_list"), Res.string.redeem_msg_failed))
    }

    @Test
    fun `the four codes the wire contract listed but neither client mapped get a translated message (T-271)`() {
        assertEquals(
            UiText.res(Res.string.api_error_invalid_expense),
            ErrorText.of(apiError("invalid_expense"), Res.string.redeem_msg_failed),
        )
        assertEquals(
            UiText.res(Res.string.api_error_invalid_field),
            ErrorText.of(apiError("invalid_field"), Res.string.redeem_msg_failed),
        )
        assertEquals(
            UiText.res(Res.string.api_error_invalid_status),
            ErrorText.of(apiError("invalid_status"), Res.string.redeem_msg_failed),
        )
        assertEquals(
            UiText.res(Res.string.api_error_invalid_device_label),
            ErrorText.of(apiError("invalid_device_label"), Res.string.redeem_msg_failed),
        )
    }

    @Test
    fun `an unknown code falls back to the screen's own message`() {
        assertEquals(UiText.res(Res.string.redeem_msg_failed), ErrorText.of(apiError("invalid_cursor"), Res.string.redeem_msg_failed))
    }

    @Test
    fun `an app too old for its server says so rather than falling back (T-244)`() {
        assertEquals(
            UiText.res(Res.string.api_error_client_outdated),
            ErrorText.of(ApiException("client_outdated", "too old", 426), Res.string.redeem_msg_failed),
        )
    }

    @Test
    fun `a parked ledger row names why it was refused instead of a bare not-saved (T-271)`() {
        assertEquals(UiText.res(Res.string.api_error_invalid_expense), ErrorText.refusal("invalid_expense", who = null))
    }

    @Test
    fun `a network failure falls back too, rather than showing the platform's English`() {
        assertEquals(
            UiText.res(Res.string.redeem_msg_failed),
            ErrorText.of(IOException("Unable to resolve host"), Res.string.redeem_msg_failed),
        )
    }
}
