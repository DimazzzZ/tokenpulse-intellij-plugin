package org.zhavoronkov.tokenpulse.ui

import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.ProviderResult
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.*

object BalanceFormatter {
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)
    private val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    fun format(balance: Balance): String {
        val settings = org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService.getInstance().state
        val parts = mutableListOf<String>()

        val showCredits = settings.showCredits
        val showTokens = settings.showTokens

        if (showCredits) {
            balance.credits?.let { credits ->
                credits.remaining?.let { 
                    parts.add(currencyFormat.format(it)) 
                } ?: credits.used?.let {
                    parts.add("${currencyFormat.format(it)} used")
                }
            }
        }
        
        if (showTokens) {
            balance.tokens?.let { tokens ->
                tokens.used?.let { used ->
                    val totalStr = tokens.total?.let { "/ ${formatNumber(it)}" } ?: ""
                    parts.add("${formatNumber(used)}$totalStr tokens")
                }
            }
        }
        
        return if (parts.isEmpty()) "--" else parts.joinToString(" + ")
    }

    private fun formatNumber(number: Long): String = numberFormat.format(number)
    private fun formatNumber(number: BigDecimal): String = numberFormat.format(number)
}
