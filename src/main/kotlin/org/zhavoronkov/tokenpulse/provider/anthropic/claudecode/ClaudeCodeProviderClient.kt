package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Provider client for Claude Code (Claude CLI).
 *
 * Extracts usage data by running the Claude CLI in a PTY and parsing the /usage output.
 * This approach works cross-platform (Windows, macOS, Linux) using IntelliJ's bundled pty4j.
 *
 * ## Authentication
 * No secret is required - the Claude CLI handles authentication via its own login flow.
 * Users must run `claude` manually once to authenticate before this provider will work.
 *
 * ## Usage Data
 * The provider extracts:
 * - Session usage percentage (5-hour window)
 * - Week usage percentage (7-day window for all models)
 * - Reset times for both windows
 *
 * ## Requirements
 * - Claude CLI must be installed: `npm install -g @anthropic-ai/claude-code`
 * - User must be logged in via the CLI
 * - Windows 10 1809+ for ConPTY support on Windows
 */
class ClaudeCodeProviderClient : ProviderClient {

    private val usageExtractor = ClaudeCliUsageExtractor()

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        TokenPulseLogger.Provider.debug("[ClaudeCodeProviderClient] fetchBalance() called for account: ${account.id}")

        if (!usageExtractor.isClaudeCliAvailable()) {
            return ProviderResult.Failure.AuthError(
                "Claude CLI not found. Please install it via: npm install -g @anthropic-ai/claude-code"
            )
        }

        // First attempt with default timeout
        TokenPulseLogger.Provider.debug("[ClaudeCodeProviderClient] First extraction attempt (${INITIAL_TIMEOUT_SECONDS}s timeout)")
        val firstResult = usageExtractor.extractUsage(timeoutSeconds = INITIAL_TIMEOUT_SECONDS)

        if (firstResult is ClaudeCliUsageExtractor.ExtractionResult.Success) {
            return buildSuccessResult(account, firstResult.usageData)
        }

        // Check if it was a timeout - if so, retry with longer timeout (cold start handling)
        val firstError = firstResult as ClaudeCliUsageExtractor.ExtractionResult.Error
        val isTimeout = firstError.message.contains("timed out", ignoreCase = true) ||
            firstError.message.contains("timeout", ignoreCase = true)

        if (isTimeout) {
            TokenPulseLogger.Provider.info(
                "[ClaudeCodeProviderClient] First attempt timed out, retrying with extended timeout (${RETRY_TIMEOUT_SECONDS}s). " +
                    "This is normal for cold start after IDE restart."
            )

            val retryResult = usageExtractor.extractUsage(timeoutSeconds = RETRY_TIMEOUT_SECONDS)

            return when (retryResult) {
                is ClaudeCliUsageExtractor.ExtractionResult.Success -> {
                    TokenPulseLogger.Provider.info("[ClaudeCodeProviderClient] Retry successful after cold start")
                    buildSuccessResult(account, retryResult.usageData)
                }
                is ClaudeCliUsageExtractor.ExtractionResult.Error -> {
                    TokenPulseLogger.Provider.warn("[ClaudeCodeProviderClient] Retry also failed: ${retryResult.message}")
                    mapExtractionError(retryResult)
                }
            }
        }

        // Non-timeout error - return immediately
        TokenPulseLogger.Provider.warn("[ClaudeCodeProviderClient] Extraction failed: ${firstError.message}")
        return mapExtractionError(firstError)
    }

    private fun buildSuccessResult(account: Account, usageData: ClaudeCliUsageExtractor.UsageData): ProviderResult.Success {
        TokenPulseLogger.Provider.debug(
            "[ClaudeCodeProviderClient] Extraction successful: " +
                "session=${usageData.sessionUsedPercent}%, week=${usageData.weekUsedPercent}%"
        )

        val metadata = buildMetadata(usageData)
        val (tokensUsed, tokensTotal) = calculateTokens(usageData)

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.CLAUDE_CODE,
                balance = Balance(
                    tokens = Tokens(
                        used = tokensUsed,
                        total = tokensTotal,
                        remaining = tokensTotal - tokensUsed
                    )
                ),
                metadata = metadata
            )
        )
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        if (!usageExtractor.isClaudeCliAvailable()) {
            return ProviderResult.Failure.AuthError(
                "Claude CLI not found. Please install it via: npm install -g @anthropic-ai/claude-code"
            )
        }

        val version = usageExtractor.getClaudeCliVersion()
        if (version == null) {
            return ProviderResult.Failure.AuthError(
                "Claude CLI found but not responding. Please check your installation."
            )
        }

        TokenPulseLogger.Provider.debug("[ClaudeCodeProviderClient] Claude CLI version: $version")

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.CLAUDE_CODE,
                balance = Balance(),
                metadata = mapOf("version" to version)
            )
        )
    }

    private fun buildMetadata(usageData: ClaudeCliUsageExtractor.UsageData): Map<String, String> {
        val metadata = mutableMapOf<String, String>()

        usageData.sessionUsedPercent?.let { metadata["sessionUsed"] = it.toString() }
        usageData.sessionResetsAt?.let { metadata["sessionResetsAt"] = it }
        usageData.weekUsedPercent?.let { metadata["weekUsed"] = it.toString() }
        usageData.weekResetsAt?.let { metadata["weekResetsAt"] = it }
        metadata["status"] = "CLI extraction successful"

        return metadata
    }

    private fun calculateTokens(usageData: ClaudeCliUsageExtractor.UsageData): Pair<Long, Long> {
        val sessionPercent = usageData.sessionUsedPercent ?: 0
        val tokensUsed = (sessionPercent * TOKENS_PER_PERCENT).toLong()
        return tokensUsed to TOTAL_TOKENS
    }

    private fun mapExtractionError(result: ClaudeCliUsageExtractor.ExtractionResult.Error): ProviderResult.Failure {
        val message = result.message.lowercase()
        return when {
            message.contains("not found") ->
                ProviderResult.Failure.AuthError(
                    "Claude CLI not found. Install via: npm install -g @anthropic-ai/claude-code"
                )

            message.contains("not authenticated") || message.contains("log in") ->
                ProviderResult.Failure.AuthError(
                    "Claude CLI not authenticated. Please run 'claude' in terminal and log in."
                )

            message.contains("timed out") ->
                ProviderResult.Failure.NetworkError(
                    "Claude CLI timed out. The CLI may be slow to respond.",
                    Exception(result.details)
                )

            message.contains("rate limit") ->
                ProviderResult.Failure.RateLimited("Claude rate limit reached")

            else ->
                ProviderResult.Failure.UnknownError(
                    "${result.message}${result.details?.let { ": $it" } ?: ""}"
                )
        }
    }

    companion object {
        /** Use session percentage * 1000 as a pseudo-token count for display. */
        private const val TOKENS_PER_PERCENT = 1000

        /** 100% = 100,000 pseudo-tokens for display purposes. */
        private const val TOTAL_TOKENS = 100_000L

        /** Initial timeout for first extraction attempt. */
        private const val INITIAL_TIMEOUT_SECONDS = 30L

        /** Extended timeout for retry after cold start timeout. */
        private const val RETRY_TIMEOUT_SECONDS = 60L
    }
}
