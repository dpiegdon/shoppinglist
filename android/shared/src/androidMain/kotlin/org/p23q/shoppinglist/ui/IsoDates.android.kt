package org.p23q.shoppinglist.ui

import java.time.LocalDate

actual fun todayIso(): String = LocalDate.now().toString()
