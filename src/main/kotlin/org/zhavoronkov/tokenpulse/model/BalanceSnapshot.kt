package org.zhavoronkov.tokenpulse.model

import java.time.Instant

data class BalanceSnapshot(
    val accountId: String,
    val providerId: ProviderId,
    val balance: Balance,
    val timestamp: Instant = Instant.now()
)

sealed class ProviderResult {
    val timestamp: Instant = Instant.now()

    data class Success(val snapshot: BalanceSnapshot) : ProviderResult()

    sealed class Failure(val message: String, val throwable: Throwable? = null) : ProviderResult() {
        data class AuthError(val msg: String) : Failure(msg)
        data class RateLimited(val msg: String) : Failure(msg)
        data class NetworkError(val msg: String, val cause: Throwable? = null) : Failure(msg, cause)
        data class ParseError(val msg: String, val cause: Throwable? = null) : Failure(msg, cause)
        data class UnknownError(val msg: String, val cause: Throwable? = null) : Failure(msg, cause)
    }
}
