package org.zhavoronkov.tokenpulse.service

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import okhttp3.OkHttpClient
import org.zhavoronkov.tokenpulse.provider.DefaultProviderRegistry
import org.zhavoronkov.tokenpulse.provider.ProviderRegistry
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Application-level service that owns a single shared [OkHttpClient] and [Gson] instance,
 * plus the [ProviderRegistry] wired up with them.
 *
 * Lifecycle is managed by the IntelliJ platform – [dispose] is called when the IDE shuts down,
 * ensuring the OkHttp dispatcher and connection pool are properly released (no thread/socket leaks).
 */
@Service(Service.Level.APP)
class HttpClientService : Disposable {

    val httpClient: OkHttpClient = createHttpClient()
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

    private fun createHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(CONNECT_TIMEOUT))
            .readTimeout(java.time.Duration.ofSeconds(READ_TIMEOUT))
            .writeTimeout(java.time.Duration.ofSeconds(WRITE_TIMEOUT))
            .callTimeout(java.time.Duration.ofSeconds(CALL_TIMEOUT))

        if (System.getProperty("tokenpulse.httpTrace", "false").toBoolean() || TokenPulseLogger.isDebugEnabled()) {
            builder.eventListenerFactory(object : okhttp3.EventListener.Factory {
                override fun create(call: okhttp3.Call): okhttp3.EventListener = DebugEventListener(call)
            })
        }

        return builder.build()
    }

    private class DebugEventListener(private val call: okhttp3.Call) : okhttp3.EventListener() {
        private var startNanos: Long = 0

        override fun callStart(call: okhttp3.Call) {
            startNanos = System.nanoTime()
            log("callStart")
        }

        override fun dnsStart(call: okhttp3.Call, domainName: String) = log("dnsStart domain=$domainName")
        override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) = log(
            "dnsEnd"
        )
        override fun connectStart(
            call: okhttp3.Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy
        ) = log(
            "connectStart proxy=$proxy"
        )
        override fun secureConnectStart(call: okhttp3.Call) = log("secureConnectStart")
        override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) = log(
            "secureConnectEnd protocol=${handshake?.tlsVersion}"
        )
        override fun connectEnd(
            call: okhttp3.Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: okhttp3.Protocol?
        ) = log(
            "connectEnd protocol=$protocol"
        )
        override fun requestHeadersStart(call: okhttp3.Call) = log("requestHeadersStart")
        override fun requestHeadersEnd(call: okhttp3.Call, request: okhttp3.Request) = log("requestHeadersEnd")
        override fun responseHeadersStart(call: okhttp3.Call) = log("responseHeadersStart")
        override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) = log(
            "responseHeadersEnd code=${response.code}"
        )
        override fun callEnd(call: okhttp3.Call) = log(
            "callEnd duration=${(System.nanoTime() - startNanos) / 1_000_000}ms"
        )
        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) = log(
            "callFailed error=${ioe.javaClass.simpleName} message=${ioe.message}"
        )

        private fun log(event: String) {
            val url = call.request().url.encodedPath
            TokenPulseLogger.Provider.debug("[HTTP-TRACE] $event url=$url")
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT = 10L
        private const val READ_TIMEOUT = 30L
        private const val WRITE_TIMEOUT = 10L
        private const val CALL_TIMEOUT = 60L

        fun getInstance(): HttpClientService = service()
    }
}
