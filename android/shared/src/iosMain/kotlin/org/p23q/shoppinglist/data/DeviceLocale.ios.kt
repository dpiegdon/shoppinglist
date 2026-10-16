package org.p23q.shoppinglist.data

import org.p23q.shoppinglist.core.AppLocale
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

actual fun deviceLocale(): AppLocale = AppLocale.resolve(NSLocale.preferredLanguages.filterIsInstance<String>())
