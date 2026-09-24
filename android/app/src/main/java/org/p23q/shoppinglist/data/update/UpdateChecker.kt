package org.p23q.shoppinglist.data.update

import kotlinx.coroutines.flow.first
import org.p23q.shoppinglist.BuildConfig
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.api.AppVersionResponse
import org.p23q.shoppinglist.core.update.compareVersions
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
 * Asks the servers this device has accounts on whether they carry a newer app than this build
 * (T-135). Every server is asked, and the newest version any of them offers is the one offered.
 * Each answer also refreshes the protocol stored on that server's accounts (T-291).
 *
 * Every "no" is silent by design — no server configured, checking switched off, server too old
 * to answer, network down, version unparseable, already asked about. An update check that
 * interrupts you to report that it failed is worse than no update check.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val registry: AccountRegistry,
    private val sessions: AccountSessions,
    private val prefs: UpdatePrefsStore,
) {
    /**
     * Returns the update to offer, or null if there is nothing to say. [currentVersion] is
     * injectable only so tests don't have to fake BuildConfig.
     */
    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): AvailableUpdate? {
        if (!prefs.autoCheckEnabled.first()) return null
        if (!hasServer()) return null

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
        if (!hasServer()) return CheckOutcome.NotChecked
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

    /**
     * The check the "update required" screen makes (T-244). Updating is not optional here — every
     * request is being refused until it happens — so neither of [checkNow]'s remaining rules
     * applies: not the twelve-hour interval, and not the "check automatically" switch, which is a
     * preference about being *offered* updates, not about being allowed to have one. Only "no
     * server configured" still stops it, because there is nothing to ask.
     */
    suspend fun checkForced(currentVersion: String = BuildConfig.VERSION_NAME): CheckOutcome {
        if (!hasServer()) return CheckOutcome.NotChecked
        // Counts as the automatic check too, so a foreground right after this does not ask again.
        prefs.recordCheck(System.currentTimeMillis())

        val response = fetchLatest() ?: return CheckOutcome.Failed
        val order = compareVersions(response.version, currentVersion) ?: return CheckOutcome.Failed
        return if (order > 0) {
            CheckOutcome.Available(AvailableUpdate(response.version, response.downloadUrl))
        } else {
            CheckOutcome.UpToDate(currentVersion)
        }
    }

    private suspend fun hasServer(): Boolean = registry.load().any { it.isServer && !it.serverUrl.isNullOrBlank() }

    /**
     * The newest app version any server offers, or null for every way of not getting one. One
     * request per server, however many accounts are on it.
     */
    private suspend fun fetchLatest(): AppVersionResponse? {
        val byServer = registry.load().filter { it.isServer && !it.serverUrl.isNullOrBlank() }.groupBy { it.serverUrl }
        val answers = byServer.values.mapNotNull { accounts ->
            val response = fetchFrom(accounts.first().id) ?: return@mapNotNull null
            accounts.forEach { account -> registry.update(account.id) { it.copy(serverProtocol = response.protocol) } }
            response
        }
        // Among the versions that parse, the newest; an answer whose version does not parse is
        // returned only when it is the only kind there is, for the callers to reject as today.
        val parseable = answers.filter { compareVersions(it.version, it.version) != null }
        return parseable.maxWithOrNull { a, b -> compareVersions(a.version, b.version)!! } ?: answers.firstOrNull()
    }

    private suspend fun fetchFrom(accountId: String): AppVersionResponse? = try {
        sessions.get(accountId).api.appVersion()
    } catch (e: IOException) {
        // Covers both halves of "couldn't ask": genuine network failure, and every non-2xx,
        // which ErrorInterceptor turns into an ApiException (itself an IOException). A 404
        // is the expected answer from any server predating this endpoint.
        null
    } catch (e: IllegalStateException) {
        // The account vanished between listing it and asking (a removal racing a check).
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
