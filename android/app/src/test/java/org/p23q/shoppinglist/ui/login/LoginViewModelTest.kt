package org.p23q.shoppinglist.ui.login

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.AuthRepository
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.UnauthorizedException
import java.io.File

class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuthRepository(
        private val onLogin: suspend (String, String) -> Unit = { _, _ -> },
    ) : AuthRepository {
        var registerCalled = false
        var loginCalled = false
        var loggedOut = false

        override suspend fun register(email: String, password: String) {
            registerCalled = true
        }

        override suspend fun login(email: String, password: String) {
            loginCalled = true
            onLogin(email, password)
        }

        override suspend fun logout() {
            loggedOut = true
        }

        override fun lastOpenedListId(): String? = null
    }

    private fun newServerConfig(): ServerConfig {
        val tempFile = File.createTempFile("login_vm_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return ServerConfig(PreferenceDataStoreFactory.create { tempFile })
    }

    @Test
    fun `happy login succeeds and persists the entered server URL`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig)

        viewModel.onServerUrlChange("https://example.com/shoppinglist")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertTrue(viewModel.uiState.value.loginSucceeded)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals("https://example.com/shoppinglist/", serverConfig.serverUrl.first())
        assertTrue(repo.loginCalled)
        assertFalse(repo.registerCalled)
    }

    @Test
    fun `bad password surfaces an error message and does not succeed`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository(onLogin = { _, _ -> throw UnauthorizedException("bad creds") })
        val viewModel = LoginViewModel(repo, serverConfig)

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("wrong")
        viewModel.submit()?.join()

        assertFalse(viewModel.uiState.value.loginSucceeded)
        assertEquals("Incorrect email or password", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `register mode calls register before login`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig)

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.onToggleRegisterMode()
        viewModel.submit()?.join()

        assertTrue(repo.registerCalled)
        assertTrue(repo.loginCalled)
    }

    @Test
    fun `non-https server URL is rejected before calling the repository`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig)

        viewModel.onServerUrlChange("http://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()

        assertFalse(repo.loginCalled)
        assertEquals("Enter a valid https server URL", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `logout delegates to the repository`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig)

        viewModel.logout().join()

        assertTrue(repo.loggedOut)
    }
}
