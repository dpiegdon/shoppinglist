package org.p23q.shoppinglist.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.p23q.shoppinglist.core.account.ApiFactory
import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.api.AppJson
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object JsonModule {
    @Provides
    @Singleton
    fun provideJson(): Json = AppJson
}

/** Builds each account's Retrofit [Api]; the accounts' sessions decide which interceptors it gets. */
@Singleton
class RetrofitApiFactory @Inject constructor(private val json: Json) : ApiFactory {
    override fun create(baseUrl: String, allowSelfSignedCerts: Boolean, interceptors: List<Interceptor>): Api {
        val client = OkHttpClient.Builder()
            .apply { interceptors.forEach { addInterceptor(it) } }
            // No-op in release (see DevCertTrust.kt); honored only in debug builds when the dev
            // opt-in is set, to allow connecting to a self-signed dev server.
            .applyDevCertTrust(allowSelfSignedCerts)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(Api::class.java)
    }
}
