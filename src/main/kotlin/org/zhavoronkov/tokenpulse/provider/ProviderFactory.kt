package org.zhavoronkov.tokenpulse.provider

import org.zhavoronkov.tokenpulse.model.ProviderId

object ProviderFactory {
    private val openRouterClient = OpenRouterProviderClient()
    private val clineClient = ClineProviderClient()

    fun getClient(providerId: ProviderId): ProviderClient {
        return when (providerId) {
            ProviderId.OPENROUTER -> openRouterClient
            ProviderId.CLINE -> clineClient
        }
    }
}
