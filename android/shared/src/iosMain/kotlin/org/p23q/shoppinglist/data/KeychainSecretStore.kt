package org.p23q.shoppinglist.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * The bearer tokens, one per local account id, as generic-password items in the app's Keychain:
 * the iOS counterpart of Android's Keystore-backed preferences (`KeystoreSecretStore` in :app).
 * Readable after the first unlock, on this device only, so a sync in the background (T-354) finds
 * them while a restored backup on another phone starts signed out, as Android does (T-37).
 *
 * The device's last-opened list is no secret and lives in the user defaults, like every other
 * device-level preference here.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecretStore(
    /** The Keychain service the items are filed under; a test uses one of its own. */
    private val service: String = SERVICE,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : SecretStore, LastOpenedListStore {

    override fun token(accountId: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(
            tokenKey(accountId),
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { query -> SecItemCopyMatching(query, result.ptr) }
        if (status == errSecItemNotFound) return null
        check(status == errSecSuccess) { "Keychain read failed: $status" }
        val data = CFBridgingRelease(result.value) as NSData
        NSString.create(data, NSUTF8StringEncoding) as String?
    }

    override fun setToken(accountId: String, token: String?) {
        val key = tokenKey(accountId)
        if (token == null) {
            val status = withQuery(key) { query -> SecItemDelete(query) }
            check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain delete failed: $status" }
            return
        }
        val value = CFBridgingRetain(NSString.create(string = token).dataUsingEncoding(NSUTF8StringEncoding))
        try {
            val added = withQuery(
                key,
                kSecValueData to value,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ) { query -> SecItemAdd(query, null) }
            if (added == errSecDuplicateItem) {
                val updated = withQuery(key) { query ->
                    withDictionary(kSecValueData to value) { update -> SecItemUpdate(query, update) }
                }
                check(updated == errSecSuccess) { "Keychain update failed: $updated" }
            } else {
                check(added == errSecSuccess) { "Keychain write failed: $added" }
            }
        } finally {
            CFRelease(value)
        }
    }

    override var lastOpenedListId: String?
        get() = defaults.stringForKey(KEY_LAST_OPENED_LIST_ID)
        set(value) {
            if (value == null) defaults.removeObjectForKey(KEY_LAST_OPENED_LIST_ID) else defaults.setObject(value, KEY_LAST_OPENED_LIST_ID)
        }

    /** A query for this store's item [account], with [extra] entries; released after [block]. */
    private inline fun <T> withQuery(account: String, vararg extra: Pair<CFStringRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> T): T {
        val service = CFBridgingRetain(NSString.create(string = service))
        val account = CFBridgingRetain(NSString.create(string = account))
        try {
            return withDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to account,
                *extra,
                block = block,
            )
        } finally {
            CFRelease(account)
            CFRelease(service)
        }
    }

    private inline fun <T> withDictionary(vararg entries: Pair<CFStringRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> T): T {
        // The dictionary retains its keys and values, so the callers may release theirs after.
        val dictionary = CFDictionaryCreateMutable(
            null, entries.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        try {
            entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
        }
    }

    internal companion object {
        const val SERVICE = "org.p23q.shoppinglist.tokens"
        const val KEY_LAST_OPENED_LIST_ID = "last_opened_list_id"

        fun tokenKey(accountId: String) = "token:$accountId"
    }
}
