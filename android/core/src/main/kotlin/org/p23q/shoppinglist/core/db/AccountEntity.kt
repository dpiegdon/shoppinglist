package org.p23q.shoppinglist.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One account this device holds lists for: an account on some server, or (later) a device-local
 * one that never syncs. Every list belongs to exactly one ([ListEntity.accountId]); items reach
 * their account through their list.
 *
 * Written only by [org.p23q.shoppinglist.core.account.AccountRegistry]. The bearer token is not
 * here: it stays in the platform's secret store, keyed by [id].
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["serverUrl", "accountId"], unique = true)],
)
data class AccountEntity(
    /** A local UUID, minted on this device. Never the server's account id. */
    @PrimaryKey val id: String,
    /** [KIND_SERVER] or [KIND_LOCAL]. */
    val kind: String = KIND_SERVER,
    /** The server's base URL, always ending in '/'; null for a local account. */
    val serverUrl: String?,
    /** The server's id for this account, as login reported it; null for a local account. */
    val accountId: String?,
    val email: String?,
    val isAdmin: Boolean = false,
    /** What the account is called on this device; defaults to the server's host. */
    val label: String,
    /**
     * Whether requests may go out for this account. False after a 401 or a logout; the lists stay,
     * so the unpushed rows among them go out once the same account signs in again.
     */
    val signedIn: Boolean,
    /** The server refused this build's protocol (426). Cleared by the next accepted request. */
    val outdated: Boolean = false,
    /** The server's protocol version, from `/app-version`; null until it has been asked. */
    val serverProtocol: Int? = null,
    val syncCursor: Long = 0,
    val defaultCurrency: String? = null,
    /** Invites this device chose to ignore on the overview (T-233), as a JSON array of ids. */
    val ignoredInviteIdsJson: String = "[]",
    /** Debug builds only honour it; see DevCertTrust.kt in :app. */
    val allowSelfSignedCerts: Boolean = false,
    /** The user's order of accounts; lower first. */
    val sortOrder: Int = 0,
) {
    val isServer: Boolean get() = kind == KIND_SERVER

    companion object {
        const val KIND_SERVER = "server"
        const val KIND_LOCAL = "local"
    }
}
