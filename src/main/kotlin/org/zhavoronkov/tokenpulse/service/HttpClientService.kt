package org.zhavoronkov.tokenpulse.service

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import okhttp3.OkHttpClient
import org.zhavoronkov.tokenpulse.provider.DefaultProviderRegistry
import org.zhavoronkov.tokenpulse.provider.ProviderRegistry

/**
 * Application-level service that owns a single shared [OkHttpClient] and [Gson] instance,
 * plus the [ProviderRegistry] wired up with them.
 *
 * Lifecycle is managed by the IntelliJ platform – [dispose] is called when the IDE shuts down,
 * ensuring the OkHttp dispatcher and connection pool are properly released (no thread/socket leaks).
 */
@Service(Service.Level.APP)
class HttpClientService : Disposable {

    val httpClient: OkHttpClient = OkHttpClient()
    val gson: Gson = Gson()

    /**
     * Shared registry backed by the shared HTTP client.
     * All provider clients reuse the same connection pool and JSON deserialiser.
     */
    val providerRegistry: ProviderRegistry = DefaultProviderRegistry(httpClient, gson)

    override fun dispose() {
        httpClient.dispatcher.cancelAll()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    companion object {
        fun getInstance(): HttpClientService = service()
    }
}
