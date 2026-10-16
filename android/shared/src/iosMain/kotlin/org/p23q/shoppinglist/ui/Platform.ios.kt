package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.savedstate.SavedState
import platform.Foundation.NSBundle
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
actual val isDebugBuild: Boolean = Platform.isDebugBinary

@Composable
actual fun appVersionName(): String = remember {
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
}

/** The share sheet, presented over the app's root view controller. */
private fun share(items: List<Any>) {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
    val controller = UIActivityViewController(activityItems = items, applicationActivities = null)
    root.presentViewController(controller, animated = true, completion = null)
}

@Composable
actual fun rememberShareText(): (text: String, title: String) -> Unit = remember {
    { text, _ -> share(listOf(text)) }
}

@Composable
actual fun rememberShareFile(): (path: String, title: String) -> Unit = remember {
    { path, _ -> share(listOf(NSURL.fileURLWithPath(path))) }
}

@Composable
actual fun rememberOpenUrl(): (url: String) -> Unit = remember {
    { url ->
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null) }
    }
}

@Composable
actual fun rememberNotificationPermissionRequest(): () -> Unit = remember {
    {
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { _, _ -> }
    }
}

/** The status bar's style belongs to the hosting view controller, which the iOS app sets. */
@Composable
actual fun SystemBarIcons(darkTheme: Boolean) = Unit

/** A Compose dialog on iOS already sees the keyboard as an inset. */
@Composable
actual fun DialogEdgeToEdge() = Unit

/** iOS opens a link by navigating to its route; there is no deep-link intent to read. */
actual fun deepLinkUri(arguments: SavedState): String? = null

actual fun isValidHttpsUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val parsed = NSURL.URLWithString(url) ?: return false
    return parsed.scheme == "https" && !parsed.host.isNullOrBlank()
}

actual fun todayIso(): String {
    val parts = NSCalendar.currentCalendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay,
        fromDate = NSDate(),
    )
    return parts.year.toString().padStart(4, '0') + "-" + parts.month.toString().padStart(2, '0') + "-" +
        parts.day.toString().padStart(2, '0')
}
