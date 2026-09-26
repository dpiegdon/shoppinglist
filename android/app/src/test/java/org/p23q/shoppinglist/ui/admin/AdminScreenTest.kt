package org.p23q.shoppinglist.ui.admin

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.TestServerAddress
import org.p23q.shoppinglist.data.testApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Tall enough for the whole console, the server message field included (T-315). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AdminScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private val usersJson = """
        {"users":[
          {"id":"admin-1","email":"boss@example.com","created_at":1,"session_count":1,"is_admin":true},
          {"id":"user-2","email":"u@example.com","created_at":2,"session_count":0,"is_admin":false}
        ]}
    """.trimIndent()

    @Test
    fun `the user list is fetched only when asked for, then shown with its count (T-221)`() = runBlocking<Unit> {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path.endsWith("/admin/users") ->
                        MockResponse().setResponseCode(200).setBody(usersJson)
                    request.method == "GET" && path.endsWith("/admin/server-settings") ->
                        MockResponse().setResponseCode(200).setBody("""{"allow_registration":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val configFile = File.createTempFile("admin_screen_config", ".preferences_pb").apply { deleteOnExit() }
        val serverConfig = TestServerAddress()
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val viewModel = AdminViewModel(
            testApi(json, token = { "tok" }) { serverConfig.url },
            serverAccountId = "admin-1",
        )

        composeTestRule.setContent { AdminScreen(viewModel = viewModel) }
        // Re-idle each attempt so a reply that lands late is still picked up (T-96).
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.allowRegistration != null
        }

        // The console is open and the toggle is live, but nobody has been listed.
        composeTestRule.onNodeWithText("Allow new accounts").assertExists()
        composeTestRule.onNodeWithText("u@example.com").assertDoesNotExist()
        composeTestRule.onNodeWithText("Your password (for reset/delete)").assertDoesNotExist()

        composeTestRule.onNodeWithText("Show registered users").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.users != null
        }

        composeTestRule.onNodeWithText("Registered users: 2").assertExists()
        composeTestRule.onNodeWithText("u@example.com").assertExists()
        // Shown, the list keeps the step-up field the per-user actions need, and a way to re-fetch.
        composeTestRule.onNodeWithText("Your password (for reset/delete)").assertExists()
        composeTestRule.onNodeWithText("Refresh").assertExists()

        viewModel.viewModelScope.cancel()
    }

    /** Bodies of the reset requests the server saw, in order. */
    private val resetBodies = CopyOnWriteArrayList<String>()

    /** Opens the console against a server that lists two users and answers a reset (T-313). */
    private fun openConsoleWithUsers(clipboard: ClipboardManager? = null): AdminViewModel {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path.endsWith("/admin/users") ->
                        MockResponse().setResponseCode(200).setBody(usersJson)
                    request.method == "GET" && path.endsWith("/admin/server-settings") ->
                        MockResponse().setResponseCode(200).setBody("""{"allow_registration":true}""")
                    request.method == "POST" && path.endsWith("/admin/users/user-2/reset-password") -> {
                        resetBodies.add(request.body.readUtf8())
                        MockResponse().setResponseCode(200).setBody("""{"password":"NEWpw123456"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val serverConfig = TestServerAddress()
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val viewModel = AdminViewModel(
            testApi(json, token = { "tok" }) { serverConfig.url },
            serverAccountId = "admin-1",
        )
        composeTestRule.setContent {
            if (clipboard == null) {
                AdminScreen(viewModel = viewModel)
            } else {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) { AdminScreen(viewModel = viewModel) }
            }
        }
        composeTestRule.onNodeWithText("Show registered users").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.users != null
        }
        return viewModel
    }

    private fun dialogButton(label: String) = composeTestRule.onNode(hasText(label) and hasAnyAncestor(isDialog()))

    /** The non-admin's Reset button: the rows are ordered by email, and boss@ comes first. */
    private fun clickUsersReset() = composeTestRule.onAllNodesWithText("Reset")[1].performClick()

    private fun resetAndWaitForPassword(viewModel: AdminViewModel) {
        composeTestRule.onNodeWithText("Your password (for reset/delete)").performTextInput("adminpw")
        clickUsersReset()
        dialogButton("Reset").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.resetPassword != null
        }
    }

    @Test
    fun `a reset waits for a confirmation naming the user, and Cancel sends nothing (T-313)`() = runBlocking<Unit> {
        val viewModel = openConsoleWithUsers()
        composeTestRule.onNodeWithText("Your password (for reset/delete)").performTextInput("adminpw")

        clickUsersReset()
        composeTestRule.onNodeWithText("Reset password?").assertExists()
        composeTestRule.onNodeWithText(
            "Reset the password of u@example.com? Their current password stops working at once.",
        ).assertExists()
        dialogButton("Cancel").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Reset password?").assertDoesNotExist()
        assertEquals(emptyList<String>(), resetBodies.toList())

        clickUsersReset()
        dialogButton("Reset").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.resetPassword != null
        }
        assertEquals(1, resetBodies.size)
        assertTrue(resetBodies.single(), resetBodies.single().contains("adminpw"))
        composeTestRule.onNodeWithText("NEWpw123456").assertExists()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `without the step-up password a reset complains and opens no confirmation (T-113, T-313)`() = runBlocking<Unit> {
        val viewModel = openConsoleWithUsers()

        clickUsersReset()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Reset password?").assertDoesNotExist()
        assertTrue(viewModel.uiState.value.passwordError != null)
        assertEquals(emptyList<String>(), resetBodies.toList())

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `Copy puts the new password on the clipboard and says so (T-313)`() = runBlocking<Unit> {
        val copied = mutableListOf<String>()
        val clipboard = object : ClipboardManager {
            override fun getText(): AnnotatedString? = copied.lastOrNull()?.let(::AnnotatedString)
            override fun setText(annotatedString: AnnotatedString) {
                copied.add(annotatedString.text)
            }
        }
        val viewModel = openConsoleWithUsers(clipboard)
        resetAndWaitForPassword(viewModel)

        composeTestRule.onNodeWithText("Copy").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("NEWpw123456"), copied)
        composeTestRule.onNodeWithText("Copied!").assertExists()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `the server message field shows the current text, checks it inline, and Save and Clear send it (T-315)`() = runBlocking<Unit> {
        val puts = CopyOnWriteArrayList<String>()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path.endsWith("/admin/server-settings") ->
                        MockResponse().setResponseCode(200).setBody("""{"allow_registration":true,"message":"Down Sunday"}""")
                    request.method == "PUT" && path.endsWith("/admin/server-settings") -> {
                        val body = request.body.readUtf8()
                        puts.add(body)
                        val sent = Json.parseToJsonElement(body).jsonObject["message"]!!.jsonPrimitive.content
                        MockResponse().setResponseCode(200).setBody("""{"allow_registration":true,"message":"$sent"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val serverConfig = TestServerAddress()
        serverConfig.setServerUrl(server.url("/").toString())
        val viewModel = AdminViewModel(
            testApi(Json { ignoreUnknownKeys = true }, token = { "tok" }) { serverConfig.url },
            serverAccountId = "admin-1",
        )
        composeTestRule.setContent { AdminScreen(viewModel = viewModel) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.serverMessage != null
        }

        composeTestRule.onNodeWithText("Server message").assertExists()
        composeTestRule.onNodeWithText("Down Sunday").assertExists()
        composeTestRule.onNodeWithText("One line, shown to everyone on the login page and above their lists.").assertExists()

        // Over 200 characters: the rule's sentence at the field, and nothing sent.
        composeTestRule.onNodeWithTag("admin-server-message").performTextReplacement("x".repeat(201))
        composeTestRule.onNodeWithTag("admin-server-message-save").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("The message must be one line of at most 200 characters.").assertExists()
        assertEquals(emptyList<String>(), puts)

        composeTestRule.onNodeWithTag("admin-server-message").performTextReplacement("Full, use another")
        composeTestRule.onNodeWithTag("admin-server-message-save").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.serverMessage == "Full, use another"
        }
        composeTestRule.onNodeWithTag("admin-server-message-clear").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.serverMessage == ""
        }

        assertEquals(listOf("""{"message":"Full, use another"}""", """{"message":""}"""), puts.toList())
        viewModel.viewModelScope.cancel()
    }
}
