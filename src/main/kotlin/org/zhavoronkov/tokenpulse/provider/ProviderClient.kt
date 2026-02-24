package org.zhavoronkov.tokenpulse.provider

import org.zhavoronkov.tokenpulse.model.ProviderResult

interface ProviderClient {
    fun fetchBalance(accountId: String, apiKey: String): ProviderResult
}
