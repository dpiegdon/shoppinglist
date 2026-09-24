package org.p23q.shoppinglist.ui.login

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AccountEntity

/**
 * The accounts on this phone as the login form needs them: which one a re-sign-in is for, and
 * whether an added account was here already. A seam over [AccountRegistry.snapshot], so the form's
 * view model tests need no database.
 */
fun interface KnownAccounts {
    fun snapshot(): List<AccountEntity>
}

@Module
@InstallIn(SingletonComponent::class)
object KnownAccountsModule {
    @Provides
    fun provideKnownAccounts(registry: AccountRegistry): KnownAccounts = KnownAccounts { registry.snapshot() }
}
