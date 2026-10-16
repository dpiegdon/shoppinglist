package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.savedstate.SavedState

/*
 * What the screens need from the platform beyond Compose: each an expect here, with its actual in
 * androidMain (intents, the Activity's window, the package manager) and in iosMain.
 */

/** Whether this is a debug build: the self-signed-certificate opt-in is shown only then (T-38). */
expect val isDebugBuild: Boolean

/** The installed app's version name ("3.6.1"), or empty when the platform does not say. */
@Composable
expect fun appVersionName(): String

/** Hands [text] to the platform's share sheet, titled [title]: an invite link (T-38). */
@Composable
expect fun rememberShareText(): (text: String, title: String) -> Unit

/** Hands the file at [path] to the platform's share sheet, titled [title]: the crash log (T-50). */
@Composable
expect fun rememberShareFile(): (path: String, title: String) -> Unit

/**
 * Opens [url] outside the app: the app package a server offers (T-135), which the system then
 * installs. Nothing happens when nothing on the device can open it.
 */
@Composable
expect fun rememberOpenUrl(): (url: String) -> Unit

/**
 * Asks for the permission to post notifications, where the platform has one and it is not yet
 * granted (T-65); the answer surfaces through the platform, not here.
 */
@Composable
expect fun rememberNotificationPermissionRequest(): () -> Unit

/**
 * Sets the system bars' icons for the app's theme rather than the phone's (T-331): dark icons on
 * the light theme, light ones on the dark.
 */
@Composable
expect fun SystemBarIcons(darkTheme: Boolean)

/**
 * Called inside a full-screen dialog's content: lets the dialog's window draw behind the system
 * bars, so the keyboard becomes a real inset and `safeDrawingPadding()` can lift the dialog's
 * action bar above it (T-80).
 */
@Composable
expect fun DialogEdgeToEdge()

/** The link a destination was opened for by a deep link, from its [arguments]; null otherwise. */
expect fun deepLinkUri(arguments: SavedState): String?

/** Whether [url] is an absolute https URL with a host, as the login form requires. */
expect fun isValidHttpsUrl(url: String): Boolean
