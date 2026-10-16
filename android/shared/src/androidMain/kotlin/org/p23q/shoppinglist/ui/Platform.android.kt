package org.p23q.shoppinglist.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.savedstate.SavedState
import org.p23q.shoppinglist.shared.BuildConfig
import java.io.File
import java.net.URI

// The library's own BuildConfig: the app's debug build is built on this module's debug variant.
actual val isDebugBuild: Boolean = BuildConfig.DEBUG

@Composable
actual fun appVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
}

@Composable
actual fun rememberShareText(): (text: String, title: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text, title ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, title))
        }
    }
}

/**
 * Through the app's FileProvider (`<package>.fileprovider`, declared in the app's manifest), whose
 * file_paths.xml grants exactly the crash log's folder (T-265).
 */
@Composable
actual fun rememberShareFile(): (path: String, title: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { path, title ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title))
        }
    }
}

/**
 * Handed to the system rather than downloaded in-app: no extra permission, no installer session to
 * babysit, and the user gets the standard install flow they already know. NEW_TASK because this
 * leaves our task entirely.
 */
@Composable
actual fun rememberOpenUrl(): (url: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                // No browser/download handler on this device. Nothing useful to say, and crashing
                // over this would be worse — on the blocking screen it would also take the only way out.
            }
        }
    }
}

/** API 33+ needs the runtime permission; older Android grants it with the install. */
@Composable
actual fun rememberNotificationPermissionRequest(): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * enableEdgeToEdge() picks the icons from the phone's dark-mode setting, so with the app on Light
 * and the phone on Dark they were white on the flat white background, and black on black the
 * other way.
 */
@Composable
actual fun SystemBarIcons(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/** The activity behind a possibly wrapped context (LocalizedContent wraps it for the locale). */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * A plain Compose Dialog window fits system windows and reports the IME inset as 0; this opts the
 * dialog's window out of decor-fits-system-windows so the keyboard becomes a real inset.
 */
@Composable
actual fun DialogEdgeToEdge() {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { dialogWindow?.let { WindowCompat.setDecorFitsSystemWindows(it, false) } }
}

/** The tapped link rides along in the deep-link Intent navigation puts into the arguments. */
actual fun deepLinkUri(arguments: SavedState): String? {
    val intent = BundleCompat.getParcelable(arguments, NavController.KEY_DEEP_LINK_INTENT, Intent::class.java)
    return intent?.data?.toString()
}

actual fun isValidHttpsUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank()
}
