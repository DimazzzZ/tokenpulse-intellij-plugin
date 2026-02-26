package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.model.Balance
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object BalanceFormatter {
    private val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    fun format(balance: Balance): String {
        val credits = balance.credits?.remaining
        val tokens = balance.tokens?.remaining

        return when {
            credits != null && tokens != null -> {
                val creditsStr = formatCredits(credits)
                val tokensStr = formatTokens(tokens)
                "$creditsStr ($tokensStr)"
            }
            credits != null -> formatCredits(credits)
            tokens != null -> formatTokens(tokens)
            else -> "--"
        }
    }

    private fun formatCredits(credits: BigDecimal): String {
        return "$${credits.setScale(2, RoundingMode.HALF_UP)}"
    }

    private fun formatTokens(tokens: Long): String {
        return String.format(Locale.US, "%,d", tokens)
    }

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
        balance.credits?.remaining?.let { remaining ->
            parts.add(formatCredits(remaining))
        }
    }

    private fun addTokensPart(balance: Balance, parts: MutableList<String>) {
        balance.tokens?.used?.let { used ->
            val totalStr = balance.tokens.total?.let { "/ ${formatNumber(it)}" } ?: ""
            parts.add("${formatNumber(used)}$totalStr tokens")
        }
    }

    private fun formatNumber(number: Long): String = numberFormat.format(number)
}
