package org.zhavoronkov.tokenpulse.model

import java.math.BigDecimal
import java.time.Instant

/**
 * Optional breakdown of Nebius balance into paid and trial components.
 * Used to render a detailed tooltip while the status bar shows the combined total.
 */
data class NebiusBalanceBreakdown(
    val paidRemaining: BigDecimal? = null,
    val trialRemaining: BigDecimal? = null,
    val tenantName: String? = null
)

data class BalanceSnapshot(
    val accountId: String,
    val connectionType: ConnectionType,
    val balance: Balance,
    val timestamp: Instant = Instant.now(),
    /** Non-null only for Nebius accounts; carries paid/trial split for tooltip rendering. */
    val nebiusBreakdown: NebiusBalanceBreakdown? = null,
    /** Additional provider-specific metadata (e.g., rate limit percentages, email, plan type). */
    val metadata: Map<String, String> = emptyMap()
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
