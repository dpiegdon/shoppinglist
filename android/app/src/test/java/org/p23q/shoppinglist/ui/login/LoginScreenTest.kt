package org.p23q.shoppinglist.ui.login

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.AuthRepository
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class NoopAuthRepository : AuthRepository {
        override suspend fun register(email: String, password: String) {}
        override suspend fun login(email: String, password: String) {}
        override suspend fun logout() {}
        override suspend fun clearLocalSession() {}
        override fun lastOpenedListId(): String? = null
    }

    @Test
    fun `renders server URL, email, password fields and a submit button`() {
        val tempFile = File.createTempFile("login_screen_test", ".preferences_pb")
        tempFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { tempFile })
        val viewModel = LoginViewModel(NoopAuthRepository(), serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        composeTestRule.setContent {
            LoginScreen(onLoginSuccess = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("Server URL").assertExists()
        composeTestRule.onNodeWithText("Email").assertExists()
        composeTestRule.onNodeWithText("Password").assertExists()
        composeTestRule.onNodeWithText("Log in").assertExists()
    }

    @Test
    fun `carries the name in cuneiform under the title, uncaptioned (T-225)`() {
        val tempFile = File.createTempFile("login_screen_cuneiform_test", ".preferences_pb")
        tempFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { tempFile })
        val viewModel = LoginViewModel(NoopAuthRepository(), serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        composeTestRule.setContent {
            LoginScreen(onLoginSuccess = {}, viewModel = viewModel)
        }

        composeTestRule
            .onNodeWithTag("login-cuneiform", useUnmergedTree = true)
            .assertExists()
            .assertContentDescriptionEquals("ṭuppu")
        // No caption here — the reading is spelled out on About, not on the way in.
        composeTestRule.onNodeWithText("ṭuppu").assertDoesNotExist()
    }
}
