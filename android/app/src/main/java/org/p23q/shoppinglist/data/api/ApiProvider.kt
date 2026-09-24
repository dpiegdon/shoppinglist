package org.p23q.shoppinglist.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.api.AppJson
import org.p23q.shoppinglist.core.api.AuthInterceptor
import org.p23q.shoppinglist.core.api.ErrorInterceptor
import org.p23q.shoppinglist.core.api.ProtocolInterceptor
import org.p23q.shoppinglist.data.ServerConfig
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

/** Builds the Retrofit [Api] lazily and rebuilds it whenever [ServerConfig.serverUrl] changes. */
@Singleton
class ApiProvider @Inject constructor(
    private val serverConfig: ServerConfig,
    private val authInterceptor: AuthInterceptor,
    private val errorInterceptor: ErrorInterceptor,
    private val json: Json,
    // Stateless, and last with a default, so the existing tests that build a provider by hand keep
    // compiling; every client this builds sends the header either way.
    private val protocolInterceptor: ProtocolInterceptor = ProtocolInterceptor(),
) {
    private val mutex = Mutex()
    private var cachedUrl: String? = null
    private var cachedAllowSelfSigned: Boolean? = null
    private var cachedApi: Api? = null

    suspend fun get(): Api {
        val url = serverConfig.serverUrl.first() ?: error("Server URL is not configured")
        val allowSelfSigned = serverConfig.allowSelfSignedCerts.first()
        mutex.withLock {
            if (url != cachedUrl || allowSelfSigned != cachedAllowSelfSigned || cachedApi == null) {
                cachedApi = buildApi(url, allowSelfSigned)
                cachedUrl = url
                cachedAllowSelfSigned = allowSelfSigned
            }
            return cachedApi!!
        }
    }

    private fun buildApi(url: String, allowSelfSignedCerts: Boolean): Api {
        val client = OkHttpClient.Builder()
            // First in the chain: the protocol header rides on every request there is (T-240).
            .addInterceptor(protocolInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(errorInterceptor)
            // No-op in release (see DevCertTrust.kt); honored only in debug builds when the dev
            // opt-in is set, to allow connecting to a self-signed dev server.
            .applyDevCertTrust(allowSelfSignedCerts)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(Api::class.java)
    }
}
