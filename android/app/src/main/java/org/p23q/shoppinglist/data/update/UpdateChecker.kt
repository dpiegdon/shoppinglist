package org.p23q.shoppinglist.data.update

import kotlinx.coroutines.flow.first
import org.p23q.shoppinglist.BuildConfig
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A newer app package the server is offering. */
data class AvailableUpdate(val version: String, val downloadUrl: String)

/** What a check made because the user opened settings found (T-149). */
sealed interface CheckOutcome {
    data class Available(val update: AvailableUpdate) : CheckOutcome
    data class UpToDate(val version: String) : CheckOutcome
    /** Asked and got no usable answer: offline, a server too old to say, an unparseable version. */
    data object Failed : CheckOutcome
    /** Did not ask: checking is switched off, or no server is configured. */
    data object NotChecked : CheckOutcome
}

/**
 * Asks the configured server whether it carries a newer app than this build (T-135).
 *
 * Every "no" is silent by design — no server configured, checking switched off, server too old
 * to answer, network down, version unparseable, already asked about. An update check that
 * interrupts you to report that it failed is worse than no update check.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val apiProvider: ApiProvider,
    private val serverConfig: ServerConfig,
    private val prefs: UpdatePrefsStore,
) {
    /**
     * Returns the update to offer, or null if there is nothing to say. [currentVersion] is
     * injectable only so tests don't have to fake BuildConfig.
     */
    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): AvailableUpdate? {
        if (!prefs.autoCheckEnabled.first()) return null
        if (serverConfig.serverUrl.first().isNullOrBlank()) return null

        val now = System.currentTimeMillis()
        if (now - prefs.lastCheckedAt.first() < CHECK_INTERVAL_MS) return null
        // Recorded before the request, not after: a server that is down or hanging would
        // otherwise never record an attempt and get retried on every single foreground.
        prefs.recordCheck(now)

        val response = fetchLatest() ?: return null
        if ((compareVersions(response.version, currentVersion) ?: return null) <= 0) return null
        if (prefs.lastPromptedVersion.first() == response.version) return null
        return AvailableUpdate(response.version, response.downloadUrl)
    }

    /**
     * The check the settings screen makes when it opens (T-149). The user went there, so two of
     * [check]'s rules do not apply: the twelve-hour interval (a release made an hour after the last
     * automatic check would otherwise stay invisible until tomorrow), and the once-per-version rule
     * (a prompt skipped by accident gets its second chance here). The switch still decides — off
     * means no request at all — and every outcome is reported, because there is someone looking.
     */
    suspend fun checkNow(currentVersion: String = BuildConfig.VERSION_NAME): CheckOutcome {
        if (!prefs.autoCheckEnabled.first()) return CheckOutcome.NotChecked
        if (serverConfig.serverUrl.first().isNullOrBlank()) return CheckOutcome.NotChecked
        // Counts as the automatic check too, so leaving settings does not trigger a second one.
        prefs.recordCheck(System.currentTimeMillis())

        val response = fetchLatest() ?: return CheckOutcome.Failed
        val order = compareVersions(response.version, currentVersion) ?: return CheckOutcome.Failed
        return if (order > 0) {
            CheckOutcome.Available(AvailableUpdate(response.version, response.downloadUrl))
        } else {
            CheckOutcome.UpToDate(currentVersion)
        }
    }

    /** The server's current app version, or null for every way of not getting one. */
    private suspend fun fetchLatest() = try {
        apiProvider.get().appVersion()
    } catch (e: IOException) {
        // Covers both halves of "couldn't ask": genuine network failure, and every non-2xx,
        // which ErrorInterceptor turns into an ApiException (itself an IOException). A 404
        // is the expected answer from any server predating this endpoint.
        null
    } catch (e: IllegalStateException) {
        // ApiProvider.get() throws this when the server URL vanished between the check above
        // and here (logout racing a foreground check).
        null
    }

    /** Records that the user has now been asked about [version], whichever way they answered. */
    suspend fun markPrompted(version: String) = prefs.recordPrompted(version)

    private companion object {
        /**
         * Twelve hours. A self-hosted list app doesn't need to learn about a release within
         * minutes, and this bounds the traffic to roughly two requests a day per device however
         * often the user opens the app.
         */
        const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    }
}
