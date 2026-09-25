package org.p23q.shoppinglist.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.core.account.AccountRegistry

/**
 * Creates the phone's local area (T-293), the account whose lists never leave the phone: from the
 * start screen's "Use without an account" and the Accounts screen's "Add local area". A seam over
 * [AccountRegistry.addLocal], so the login form's view model tests need no database.
 */
fun interface LocalArea {
    /** True when the area was created now, false when the phone has one already. */
    suspend fun create(): Boolean
}

@Module
@InstallIn(SingletonComponent::class)
object LocalAreaModule {
    @Provides
    fun provideLocalArea(registry: AccountRegistry): LocalArea = LocalArea { registry.addLocal() != null }
}
