package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.model.Balance
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.*

object BalanceFormatter {
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)

    fun format(balance: Balance): String {
        return when (balance) {
            is Balance.CreditsUsd -> currencyFormat.format(balance.amount)
            is Balance.Tokens -> {
                val used = formatNumber(balance.used)
                val total = balance.total?.let { "/ ${formatNumber(it)}" } ?: ""
                "$used$total tokens"
            }
        }
    }

    private fun formatNumber(number: Long): String {
        return NumberFormat.getNumberInstance(Locale.US).format(number)
    }
}
