package org.p23q.shoppinglist

import android.app.Application

/**
 * The Application every Robolectric test here runs under (wired up in
 * `src/androidUnitTest/resources/robolectric.properties`): a bare one, as in :app's tests, where
 * the real ShoppingListApp would start WorkManager, whose own database then outlives the test and
 * charges a stray exception to the next one (T-209). No unit test needs the real application: each
 * one builds by hand the dependencies it exercises.
 */
class RobolectricTestApp : Application()
