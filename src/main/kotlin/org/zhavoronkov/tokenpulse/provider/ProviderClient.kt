package org.zhavoronkov.tokenpulse.provider

import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account

interface ProviderClient {
    fun fetchBalance(account: Account, secret: String): ProviderResult
    fun testCredentials(account: Account, secret: String): ProviderResult
}
