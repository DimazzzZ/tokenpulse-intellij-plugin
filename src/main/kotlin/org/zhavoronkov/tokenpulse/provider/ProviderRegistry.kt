package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.zhavoronkov.tokenpulse.model.ProviderId

interface ProviderRegistry {
    fun getClient(providerId: ProviderId): ProviderClient
}

class DefaultProviderRegistry(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) : ProviderRegistry {
    private val openRouterClient = OpenRouterProviderClient(httpClient, gson)
    private val clineClient = ClineProviderClient(httpClient, gson)
    private val nebiusClient = NebiusProviderClient(httpClient, gson)

    override fun getClient(providerId: ProviderId): ProviderClient {
        return when (providerId) {
            ProviderId.OPENROUTER -> openRouterClient
            ProviderId.CLINE -> clineClient
            ProviderId.NEBIUS -> nebiusClient
        }
    }
}
