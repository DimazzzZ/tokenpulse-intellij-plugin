package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import com.google.gson.Gson
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Provider client for Codex CLI (ChatGPT Plus/Pro/Team).
 *
 * Uses Codex app-server JSON-RPC to fetch rate limits:
 * - 5-hour usage limit
 * - Weekly usage limit
 *
 * ## Authentication
 * Codex app-server handles authentication internally. The plugin calls
 * `account/read` and `account/rateLimits/read` to retrieve usage data.
 *
 * ## Rate Limits via Codex app-server
 * The app-server provides actual usage percentages:
 * - 5-hour usage percentage
 * - Weekly usage percentage
 *
 * ## Credits
 * Credits remaining is NOT available via public API.
 * We show "unavailable" for credits.
 */
class CodexProviderClient(
    private val gson: Gson = Gson()
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            TokenPulseLogger.Provider.info("Codex fetchBalance: accountId=${account.id}")

            val client = CodexAppServerClient(gson)
            val startupResult = client.start()

            if (!startupResult.success) {
                val errorMessage = startupResult.errorMessage ?: "Codex app-server failed to start"
                TokenPulseLogger.Provider.info("Codex app-server startup failed: $errorMessage")
                return ProviderResult.Failure.AuthError(
                    "Codex app-server failed: $errorMessage"
                )
            }

            try {
                // First check account status
                val accountInfo = client.readAccount()
                if (accountInfo == null || !accountInfo.isAuthenticated) {
                    return ProviderResult.Failure.AuthError(
                        "Codex not authenticated. Run 'codex login' in terminal to sign in."
                    )
                }

                // Read rate limits
                val rateLimits = client.readRateLimits()
                if (rateLimits == null) {
                    val errorCode = client.lastRateLimitsErrorCode
                    val errorMessage = client.lastRateLimitsErrorMessage ?: "Unknown error"

                    val uiErrorDetail = when (errorCode) {
                        "token_expired", "refresh_token_reused", "unauthorized" ->
                            "Codex session expired. Run 'codex login' in terminal to re-authenticate."
                        "limits_refresh_pending" ->
                            "Codex rate limits are refreshing. Please wait a moment and try again."
                        else -> errorMessage.takeIf { it.isNotBlank() } ?: "Rate limits unavailable"
                    }

                    TokenPulseLogger.Provider.info("Codex app-server error: $uiErrorDetail")
                    return ProviderResult.Failure.AuthError(uiErrorDetail)
                }

                // Build success result from rate limits
                buildSuccessResultFromRateLimits(account, rateLimits, accountInfo)
            } finally {
                client.stop()
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Codex fetch error", e)
            ProviderResult.Failure.NetworkError("Failed to connect to Codex app-server", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    private fun buildSuccessResultFromRateLimits(
        account: Account,
        rateLimits: CodexAppServerClient.RateLimits,
        accountInfo: CodexAppServerClient.AccountInfo
    ): ProviderResult {
        val fiveHourBucket = rateLimits.fiveHourBucket
        val weeklyBucket = rateLimits.weeklyBucket

        val fiveHourUsed = fiveHourBucket?.usedPercent?.toInt()
        val weeklyUsed = weeklyBucket?.usedPercent?.toInt()

        val fiveHourResetsAt = fiveHourBucket?.resetsAt?.let { formatTimestamp(it) } ?: "N/A"
        val weeklyResetsAt = weeklyBucket?.resetsAt?.let { formatTimestamp(it) } ?: "N/A"

        TokenPulseLogger.Provider.debug(
            "Codex rate limits: 5h=$fiveHourUsed%, weekly=$weeklyUsed%"
        )

        val fiveHourTokens = fiveHourUsed?.let { (it * TOKENS_PER_PERCENT).toLong() }
        val totalUsed = fiveHourTokens ?: 0L
        val totalRemaining = fiveHourUsed?.let { ((100 - it) * TOKENS_PER_PERCENT).toLong() } ?: 0L

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.CODEX_CLI,
                balance = Balance(
                    credits = Credits(used = null, total = null, remaining = null),
                    tokens = Tokens(used = totalUsed, total = TOTAL_TOKENS, remaining = totalRemaining)
                ),
                metadata = mapOf(
                    "email" to (accountInfo.email ?: "N/A"),
                    "planType" to (accountInfo.planType ?: "N/A"),
                    "fiveHourUsed" to (fiveHourUsed?.toString() ?: "N/A"),
                    "weeklyUsed" to (weeklyUsed?.toString() ?: "N/A"),
                    "fiveHourResetsAt" to fiveHourResetsAt,
                    "weeklyResetsAt" to weeklyResetsAt,
                    "codexSource" to "app_server"
                )
            )
        )
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return try {
            val instant = java.time.Instant.ofEpochMilli(timestampMs)
            val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d 'at' h:mm a")
                .withZone(java.time.ZoneId.systemDefault())
            formatter.format(instant)
        } catch (_: Exception) {
            "N/A"
        }
    }

    companion object {
        private const val TOKENS_PER_PERCENT = 1000
        private const val TOTAL_TOKENS = 100_000L
    }
}
