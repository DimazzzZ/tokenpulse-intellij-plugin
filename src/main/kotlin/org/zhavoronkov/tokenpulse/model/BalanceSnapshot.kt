package org.zhavoronkov.tokenpulse.model

import java.time.Instant

data class BalanceSnapshot(
    val accountId: String,
    val providerId: ProviderId,
    val balance: Balance,
    val timestamp: Instant = Instant.now()
)

sealed class ProviderResult {
    data class Success(val snapshot: BalanceSnapshot) : ProviderResult()
    data class Error(val message: String, val throwable: Throwable? = null) : ProviderResult()
}
