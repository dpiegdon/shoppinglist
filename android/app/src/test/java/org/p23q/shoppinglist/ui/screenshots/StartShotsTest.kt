package org.p23q.shoppinglist.ui.screenshots

import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.FakeLastServerAddress
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.RecordingAuthRepository
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.login.KnownAccounts
import org.p23q.shoppinglist.ui.login.LoginScreen
import org.p23q.shoppinglist.ui.login.LoginViewModel
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The start screen a fresh install opens on, light and dark (T-325). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class StartShotsTest(theme: ShotTheme) : ScreenshotTest(theme) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = ShotTheme.both()
    }

    @Test
    fun start() {
        val viewModel = LoginViewModel(
            RecordingAuthRepository(),
            KnownAccounts { emptyList() },
            FakeLastServerAddress(),
            PendingInviteHolder(),
            FakeSyncTrigger(),
        ).tracked()

        setThemedContent { LoginScreen(onLoginSuccess = {}, viewModel = viewModel) }
        capture("start")
    }
}
