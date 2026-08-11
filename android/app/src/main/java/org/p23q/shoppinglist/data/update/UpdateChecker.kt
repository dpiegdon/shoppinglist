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

        val response = try {
            apiProvider.get().appVersion()
        } catch (e: IOException) {
            // Covers both halves of "couldn't ask": genuine network failure, and every non-2xx,
            // which ErrorInterceptor turns into an ApiException (itself an IOException). A 404
            // is the expected answer from any server predating this endpoint.
            return null
        } catch (e: IllegalStateException) {
            // ApiProvider.get() throws this when the server URL vanished between the check above
            // and here (logout racing a foreground check).
            return null
        }

        if ((compareVersions(response.version, currentVersion) ?: return null) <= 0) return null
        if (prefs.lastPromptedVersion.first() == response.version) return null
        return AvailableUpdate(response.version, response.downloadUrl)
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
