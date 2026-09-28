package org.p23q.shoppinglist.ui.screenshots

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.account.ApiFactory
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.api.RetrofitApiFactory
import org.p23q.shoppinglist.data.closeWhenIdle
import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.AppDrawerScaffold
import org.p23q.shoppinglist.ui.DrawerViewModel
import org.p23q.shoppinglist.ui.SyncStatusViewModel
import org.p23q.shoppinglist.ui.theme.ShoppingListTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Where the reference images live, relative to the module (a test's working directory). */
const val SCREENSHOT_DIR = "src/test/screenshots"

/**
 * How close a capture has to be to its reference. A pixel counts as changed only when its colour
 * moved by more than [SimpleImageComparator.maxDistance] (antialiasing and blending wobble), and a
 * shot fails when more than [RoborazziOptions.CompareOptions.changeThreshold] of its pixels
 * changed: 0.1 %, about 370 of a phone screen's, well under one short word.
 */
@OptIn(ExperimentalRoborazziApi::class)
val SCREENSHOT_OPTIONS = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(
        changeThreshold = 0.001f,
        imageComparator = SimpleImageComparator(maxDistance = 0.01f, hShift = 0, vShift = 0),
    ),
)

/** The date every screenshot takes as today, whatever the calendar says. */
val SCREENSHOT_TODAY: LocalDate = LocalDate.of(2026, 9, 17)

/** Epoch milliseconds of [date] at noon, for timestamps a screen shows as a date. */
fun noonOf(date: LocalDate): Long = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

const val DAY_MS = 24 * 60 * 60 * 1000L

/** The people on the shared lists: this phone's account first. */
val ROSTER = listOf(
    Triple("acct-sam", "sam@example.org", "SA"),
    Triple("acct-alex", "alex@example.org", "AL"),
    Triple("acct-robin", "robin@example.org", "RO"),
)

/** The light or the dark theme, as one run of a parameterized screenshot class takes it. */
enum class ShotTheme(val dark: Boolean) {
    LIGHT(false),
    DARK(true),
    ;

    val suffix: String get() = name.lowercase()

    companion object {
        /** For `@ParameterizedRobolectricTestRunner.Parameters`. */
        fun both(): List<Array<Any>> = entries.map { arrayOf(it) }
    }
}

/**
 * A server that answers without a network: the last interceptor of every test account's client,
 * returning [answer]'s JSON for a path (from `api/v1/` on), or a 404 when it has none. The
 * accounts can then carry fixed, readable URLs rather than a MockWebServer's random port, which
 * would show on the screens and change every run.
 */
class FakeServer(private val answer: (method: String, path: String) -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath.substringAfter("/api/v1/", "")
        val body = answer(request.method, path)
        return Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200)
            .message(if (body == null) "Not Found" else "OK")
            .body((body ?: """{"error": "not_found", "message": "no"}""").toResponseBody("application/json".toMediaType()))
            .build()
    }

    /** An [ApiFactory] like the app's whose clients all end at this server. */
    fun apiFactory(json: Json): ApiFactory = ApiFactory { baseUrl, allowSelfSigned, interceptors ->
        RetrofitApiFactory(json).create(baseUrl, allowSelfSigned, interceptors + this)
    }
}

/**
 * What every screenshot class shares: the compose rule, an in-memory database with the app's
 * account wiring over a [FakeServer], and capturing into [SCREENSHOT_DIR] as `<name>-<theme>.png`.
 * A subclass is a `ParameterizedRobolectricTestRunner` over [ShotTheme.both], so each shot is
 * taken once light and once dark.
 */
abstract class ScreenshotTest(protected val theme: ShotTheme) {

    @get:Rule
    val composeTestRule = createComposeRule()

    protected lateinit var db: AppDb
    protected lateinit var accounts: TestAccounts
    protected lateinit var listsRepo: ListsRepo
    protected lateinit var itemsRepo: ItemsRepo
    protected val json = Json { ignoreUnknownKeys = true }
    protected val syncer = Syncer { SyncResult.Success(0, 0, 0, 0) }

    /** What the fake server answers; a test sets it before its screen asks. */
    protected var serverAnswer: (method: String, path: String) -> String? = { _, _ -> null }
    protected val server = FakeServer { method, path -> serverAnswer(method, path) }

    /** Everything whose scope has to end before the database closes. */
    protected val viewModels = mutableListOf<ViewModel>()

    @Before
    fun setUpDatabase() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        accounts = TestAccounts(db, json, server.apiFactory(json))
        val deviceId = DeviceIdProvider { "device-1" }
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
    }

    @After
    fun closeDatabase() {
        if (::db.isInitialized) closeWhenIdle(db, ::idleMainLooper, viewModels, registry = if (::accounts.isInitialized) accounts.registry else null)
    }

    /** The roster a list's server reports for [ROSTER], with one invite still open. */
    protected fun rosterJson(): String {
        // Half a day past three days, so it reads "3 d" however long the run takes.
        val expires = System.currentTimeMillis() + 3 * DAY_MS + DAY_MS / 2
        val members = ROSTER.mapIndexed { i, (id, email, initials) ->
            """{"account_id": "$id", "email": "$email", "initials": "$initials", "joined_at": ${i + 1}}"""
        }
        return """{"members": [${members.joinToString()}],
            "invites": [{"id": "inv-1", "invited_email": "kim@example.org", "expires_at": $expires}]}"""
    }

    /** Gives [listId] the roster [members], each as (server account id, email, initials). */
    protected suspend fun setMembers(listId: String, vararg members: Triple<String, String, String>) {
        val roster = members.map { (id, email, initials) -> org.p23q.shoppinglist.core.ListMember(id, email, initials) }
        val list = checkNotNull(listsRepo.getById(listId))
        db.listDao().upsert(list.copy(membersJson = json.encodeToString(roster)))
    }

    /** An English string of the app's, as a title the screen's top bar shows. */
    protected fun str(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    protected fun <T : ViewModel> T.tracked(): T = also { viewModels += it }

    protected fun prefsFile(name: String): File = File.createTempFile("shot_$name", ".preferences_pb").apply { deleteOnExit() }

    protected fun <T> prefs(name: String, make: (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> T): T =
        make(PreferenceDataStoreFactory.create { prefsFile(name) })

    /** [content] in the app's theme on its background, as MainActivity hosts every screen. */
    protected fun setThemedContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            ShoppingListTheme(darkTheme = theme.dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
    }

    /** [content] under the app's top bar titled [title], as Nav puts a drawer screen there. */
    protected fun setScreen(
        title: String,
        subtitle: String? = null,
        actions: @Composable RowScope.() -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        val drawer = DrawerViewModel(accounts.registry).tracked()
        val sync = SyncStatusViewModel(accounts.syncStatus).tracked()
        setThemedContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "shot") {
                composable("shot") {
                    AppDrawerScaffold(
                        navController = navController,
                        title = title,
                        drawerViewModel = drawer,
                        subtitle = subtitle,
                        actions = actions,
                        syncStatusViewModel = sync,
                        content = content,
                    )
                }
            }
        }
    }

    /** Waits until [ready] holds and the UI is idle. */
    protected fun awaitUntil(ready: () -> Boolean) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            ready()
        }
        composeTestRule.waitForIdle()
    }

    private fun fileFor(name: String) = "$SCREENSHOT_DIR/$name-${theme.suffix}.png"

    /** The screen as it stands, as `<name>-<theme>.png`. */
    protected fun capture(name: String) {
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(fileFor(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }

    /** Every window, a dialog over its screen included, as `<name>-<theme>.png`. */
    @OptIn(ExperimentalRoborazziApi::class)
    protected fun captureWithDialog(name: String) {
        composeTestRule.waitForIdle()
        captureScreenRoboImage(fileFor(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
