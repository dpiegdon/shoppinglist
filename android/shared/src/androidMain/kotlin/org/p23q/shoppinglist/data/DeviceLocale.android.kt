package org.p23q.shoppinglist.data

import android.content.res.Resources
import android.os.Build
import org.p23q.shoppinglist.core.AppLocale
import java.util.Locale

actual fun deviceLocale(): AppLocale {
    val tags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val list = Resources.getSystem().configuration.locales
        (0 until list.size()).map { list[it].toLanguageTag() }
    } else {
        listOf(Locale.getDefault().toLanguageTag())
    }
    return AppLocale.resolve(tags)
}
