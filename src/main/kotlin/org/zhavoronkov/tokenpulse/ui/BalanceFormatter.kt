package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.model.Balance
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object BalanceFormatter {
    private val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    /**
     * Formats a balance for display in the status bar or dashboard.
     * Handles both balance (remaining) and usage-only data.
     */
    fun format(balance: Balance): String {
        val credits = balance.credits
        val tokens = balance.tokens

        return when {
            // Both credits and tokens available
            credits != null && tokens != null -> {
                val creditsStr = formatCreditsOrUsage(credits)
                val tokensStr = formatTokensOrUsage(tokens)
                "$creditsStr ($tokensStr)"
            }
            // Only credits available
            credits != null -> formatCreditsOrUsage(credits)
            // Only tokens available
            tokens != null -> formatTokensOrUsage(tokens)
            else -> "--"
        }
    }

    /**
     * Formats credits as remaining balance or usage.
     */
    private fun formatCreditsOrUsage(credits: org.zhavoronkov.tokenpulse.model.Credits): String {
        return when {
            credits.remaining != null -> formatCredits(credits.remaining)
            credits.used != null -> formatUsageCredits(credits.used)
            credits.total != null -> formatCredits(credits.total)
            else -> "--"
        }
    }

    /**
     * Formats tokens as remaining, used, or total.
     */
    private fun formatTokensOrUsage(tokens: org.zhavoronkov.tokenpulse.model.Tokens): String {
        return when {
            tokens.remaining != null -> formatTokens(tokens.remaining)
            tokens.used != null -> formatTokensUsage(tokens.used)
            tokens.total != null -> formatTokens(tokens.total)
            else -> "--"
        }
    }

    private fun formatCredits(credits: BigDecimal): String {
        return "$${credits.setScale(2, RoundingMode.HALF_UP)}"
    }

    private fun formatUsageCredits(credits: BigDecimal): String {
        return "$${credits.setScale(2, RoundingMode.HALF_UP)} used"
    }

    private fun formatTokens(tokens: Long): String {
        return String.format(Locale.US, "%,d", tokens)
    }

    private fun formatTokensUsage(tokens: Long): String {
        return String.format(Locale.US, "%,d used", tokens)
    }

    /**
     * Formats a balance for detailed display in the dashboard.
     * Shows both credits and tokens with their respective values.
     */
    fun formatDetailed(balance: Balance, showCredits: Boolean, showTokens: Boolean): String {
        val parts = mutableListOf<String>()

        if (showCredits) {
            addCreditsPart(balance, parts)
        }

        if (showTokens) {
            addTokensPart(balance, parts)
        }

        return if (parts.isEmpty()) "--" else parts.joinToString(" + ")
    }

    private fun addCreditsPart(balance: Balance, parts: MutableList<String>) {
        balance.credits?.let { credits ->
            when {
                credits.remaining != null -> parts.add(formatCredits(credits.remaining))
                credits.used != null -> parts.add(formatUsageCredits(credits.used))
                credits.total != null -> parts.add(formatCredits(credits.total))
                else -> {}
            }
        }
    }

    private fun addTokensPart(balance: Balance, parts: MutableList<String>) {
        balance.tokens?.let { tokens ->
            when {
                tokens.remaining != null -> parts.add(formatTokens(tokens.remaining))
                tokens.used != null -> {
                    val totalStr = tokens.total?.let { "/ ${formatNumber(it)}" } ?: ""
                    parts.add("${formatTokensUsage(tokens.used)}$totalStr")
                }
                tokens.total != null -> parts.add(formatTokens(tokens.total))
                else -> {}
            }
        }
    }

    private fun formatNumber(number: Long): String = numberFormat.format(number)
}
