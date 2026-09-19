package org.p23q.shoppinglist

import android.app.Application

/**
 * The Application every Robolectric test runs under, in place of the real [ShoppingListApp]
 * (wired up in `src/test/resources/robolectric.properties`).
 *
 * ShoppingListApp.onCreate() schedules the periodic sync, which initializes WorkManager, which
 * opens WorkManager's *own* Room database. That one runs on the framework SQLite path — i.e.
 * Robolectric's SQLite shadows — on WorkManager's executor, and nothing ever closes it. Robolectric
 * drops those shadow connections when a test ends, so a Room invalidation refresh still queued on a
 * WM.task thread then failed with "Illegal connection pointer", and kotlinx-coroutines-test charged
 * that stray exception to whichever test started next ("There were uncaught exceptions before the
 * test started") — about one run in three of ListPropsViewModelTest (T-209). No unit test needs the
 * real application: each one builds by hand the dependencies it exercises.
 */
class RobolectricTestApp : Application()
