package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

/**
 * Provider-agnostic Claude usage snapshot covering the two windows Claude
 * reports: the 5-hour ("session") window and the 7-day ("week") window.
 *
 * Produced by [ClaudeOAuthUsageClient] from the OAuth usage API and consumed by
 * [ClaudeCodeProviderClient] to build a [org.zhavoronkov.tokenpulse.model.BalanceSnapshot].
 */
data class ClaudeUsageData(
    val sessionUsedPercent: Int? = null,
    val sessionResetsAt: String? = null,
    val weekUsedPercent: Int? = null,
    val weekResetsAt: String? = null,
)
