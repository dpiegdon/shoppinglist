package org.p23q.shoppinglist.ui

import org.p23q.shoppinglist.core.AppLocale
import java.util.Locale

/** The java.util.Locale this language's tag denotes, for Configuration and resource resolution. */
fun AppLocale.toJavaLocale(): Locale = Locale.forLanguageTag(tag)
