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
import org.p23q.shoppinglist.data.ServerConfig
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object JsonModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }
}

/** Builds the Retrofit [Api] lazily and rebuilds it whenever [ServerConfig.serverUrl] changes. */
@Singleton
class ApiProvider @Inject constructor(
    private val serverConfig: ServerConfig,
    private val authInterceptor: AuthInterceptor,
    private val errorInterceptor: ErrorInterceptor,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var cachedUrl: String? = null
    private var cachedApi: Api? = null

    suspend fun get(): Api {
        val url = serverConfig.serverUrl.first() ?: error("Server URL is not configured")
        mutex.withLock {
            if (url != cachedUrl || cachedApi == null) {
                cachedApi = buildApi(url)
                cachedUrl = url
            }
            return cachedApi!!
        }
    }

    private fun buildApi(url: String): Api {
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(errorInterceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(Api::class.java)
    }
}
