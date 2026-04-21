package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.Provider
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.StatusBarDollarFormat
import org.zhavoronkov.tokenpulse.settings.StatusBarFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object BalanceFormatter {
    private val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    /** Connection types that report usage as percentage (not dollars). */
    private val usagePercentageTypes = setOf(
        ConnectionType.CLAUDE_CODE,
        ConnectionType.CODEX_CLI
    )

    /**
     * Checks if a connection type reports usage as percentage (not dollars).
     */
    fun isUsagePercentageType(connectionType: ConnectionType): Boolean =
        connectionType in usagePercentageTypes

    // ==================== Status Bar Formatting ====================

    /**
     * Formats usage percentage data for status bar display.
     * Extracts 5-hour and weekly usage from metadata.
     *
     * @param result The provider result containing metadata
     * @param format Compact or descriptive format
     * @param provider Optional provider for abbreviation suffix
     * @return Formatted string like "86% 5h • 72% wk" or "86% of 5h remaining"
     */
    fun formatUsagePercentageForStatusBar(
        result: ProviderResult.Success,
        format: StatusBarFormat,
        provider: Provider? = null
    ): String {
        val metadata = result.snapshot.metadata
        val connectionType = result.snapshot.connectionType

        // Extract usage percentages based on connection type
        val usageData = when (connectionType) {
            ConnectionType.CLAUDE_CODE -> {
                // OAuth data preferred, fallback to CLI data
                val fiveHour = metadata["fiveHourUtilization"]?.toIntOrNull()
                    ?: metadata["sessionUsed"]?.toIntOrNull()
                val weekly = metadata["sevenDayUtilization"]?.toIntOrNull()
                    ?: metadata["weekUsed"]?.toIntOrNull()

                // If we have weekly data but no session data, show weekly only
                // Don't assume session is 0 - the CLI may not have reported it
                UsageData(fiveHour, weekly, "5h", "wk")
            }
            ConnectionType.CODEX_CLI -> {
                val fiveHour = metadata["fiveHourUsed"]?.toFloatOrNull()?.toInt()
                val weekly = metadata["weeklyUsed"]?.toFloatOrNull()?.toInt()
                UsageData(fiveHour, weekly, "5h", "wk")
            }
            else -> UsageData(null, null, "", "")
        }
        val shortUsed = usageData.shortUsed
        val longUsed = usageData.longUsed
        val shortLabel = usageData.shortLabel
        val longLabel = usageData.longLabel

        // Build the display string
        val parts = mutableListOf<String>()

        if (shortUsed != null) {
            val remaining = 100 - shortUsed
            parts.add(
                when (format) {
                    StatusBarFormat.COMPACT -> "$remaining% $shortLabel"
                    StatusBarFormat.DESCRIPTIVE -> "$remaining% of $shortLabel remaining"
                }
            )
        }

        if (longUsed != null) {
            val remaining = 100 - longUsed
            parts.add(
                when (format) {
                    StatusBarFormat.COMPACT -> "$remaining% $longLabel"
                    StatusBarFormat.DESCRIPTIVE -> "$remaining% of weekly remaining"
                }
            )
        }

        if (parts.isEmpty()) {
            return "--"
        }

        val joined = when (format) {
            StatusBarFormat.COMPACT -> parts.joinToString(" • ")
            StatusBarFormat.DESCRIPTIVE -> parts.joinToString(" • ")
        }

        // Add provider abbreviation if requested
        return if (provider != null) {
            "$joined (${provider.abbreviation})"
        } else {
            joined
        }
    }

    /**
     * Formats dollar amount for status bar display.
     *
     * @param amount The dollar amount
     * @param format Compact or descriptive format
     * @param provider Optional provider for abbreviation suffix
     * @return Formatted string like "$500" or "$500 remaining (OR)"
     */
    fun formatDollarsForStatusBar(
        amount: BigDecimal,
        format: StatusBarFormat,
        provider: Provider? = null
    ): String {
        val formatted = "\$${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()}"

        val text = when (format) {
            StatusBarFormat.COMPACT -> formatted
            StatusBarFormat.DESCRIPTIVE -> "$formatted remaining"
        }

        return if (provider != null) {
            "$text (${provider.abbreviation})"
        } else {
            text
        }
    }

    /**
     * Formats total dollar amount for status bar (aggregated from multiple providers).
     *
     * @param amount The total dollar amount
     * @param format Compact or descriptive format
     * @param providerCount Number of providers contributing to total (optional)
     * @return Formatted string like "$500" or "$500 total remaining"
     */
    fun formatTotalDollarsForStatusBar(
        amount: BigDecimal,
        format: StatusBarFormat,
        providerCount: Int? = null
    ): String {
        val formatted = "\$${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()}"

        return when (format) {
            StatusBarFormat.COMPACT -> {
                if (providerCount != null && providerCount > 1) {
                    "$formatted ($providerCount)"
                } else {
                    formatted
                }
            }
            StatusBarFormat.DESCRIPTIVE -> "$formatted total remaining"
        }
    }

    /**
     * Formats used dollars for status bar (for usage-only providers like OpenAI Platform).
     *
     * @param amount The used dollar amount
     * @param format Compact or descriptive format
     * @param provider Optional provider for abbreviation suffix
     * @return Formatted string like "$14.50 used" or "$14.50 used (OA)"
     */
    fun formatUsedDollarsForStatusBar(
        amount: BigDecimal,
        format: StatusBarFormat,
        provider: Provider? = null
    ): String {
        val formatted = "\$${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()}"

        val text = when (format) {
            StatusBarFormat.COMPACT -> "$formatted used"
            StatusBarFormat.DESCRIPTIVE -> "$formatted used this period"
        }

        return if (provider != null) {
            "$text (${provider.abbreviation})"
        } else {
            text
        }
    }

    /**
     * Describes what format capabilities a Credits object supports.
     */
    data class FormatCapability(
        val supportsRemaining: Boolean,
        val supportsUsedOfTotal: Boolean,
        val supportsPercentage: Boolean
    ) {
        companion object {
            fun fromCredits(credits: Credits?): FormatCapability {
                if (credits == null) return FormatCapability(false, false, false)

                val hasRemaining = credits.remaining != null
                val hasUsed = credits.used != null
                val hasTotal = credits.total != null || (hasRemaining && hasUsed)

                return FormatCapability(
                    supportsRemaining = hasRemaining || hasUsed,
                    supportsUsedOfTotal = hasTotal && (hasUsed || hasRemaining),
                    supportsPercentage = hasTotal && hasRemaining
                )
            }
        }

        fun supports(format: StatusBarDollarFormat): Boolean = when (format) {
            StatusBarDollarFormat.REMAINING_ONLY -> supportsRemaining
            StatusBarDollarFormat.USED_OF_REMAINING -> supportsUsedOfTotal
            StatusBarDollarFormat.PERCENTAGE_REMAINING -> supportsPercentage
        }

        /** Returns the best available format if requested format is not supported */
        fun bestAvailable(requested: StatusBarDollarFormat): StatusBarDollarFormat {
            if (supports(requested)) return requested

            // Fallback order: USED_OF_REMAINING -> REMAINING_ONLY -> original (will show --)
            return when {
                supportsUsedOfTotal -> StatusBarDollarFormat.USED_OF_REMAINING
                supportsRemaining -> StatusBarDollarFormat.REMAINING_ONLY
                else -> requested
            }
        }
    }

    /**
     * Formats credits for status bar display using the new dollar format settings.
     * Automatically falls back to the best available format if requested format is not supported.
     *
     * @param credits The credits data containing remaining, used, and/or total
     * @param dollarFormat The format to use (REMAINING_ONLY, USED_OF_REMAINING, PERCENTAGE_REMAINING)
     * @param provider Optional provider for abbreviation suffix
     * @return Formatted string based on the selected format (or best available fallback)
     */
    fun formatCreditsForStatusBar(
        credits: Credits,
        dollarFormat: StatusBarDollarFormat,
        provider: Provider? = null
    ): String {
        val remaining = credits.remaining
        val used = credits.used
        val total = credits.total ?: run {
            // Calculate total from remaining + used if not explicitly set
            if (remaining != null && used != null) {
                remaining + used
            } else {
                null
            }
        }

        // Check format support and use best available
        val capability = FormatCapability.fromCredits(credits)
        val effectiveFormat = capability.bestAvailable(dollarFormat)

        val text = when (effectiveFormat) {
            StatusBarDollarFormat.REMAINING_ONLY -> {
                if (remaining != null) {
                    "\$${remaining.setScale(0, RoundingMode.HALF_UP)}"
                } else if (used != null) {
                    "\$${used.setScale(0, RoundingMode.HALF_UP)} used"
                } else {
                    "--"
                }
            }
            StatusBarDollarFormat.USED_OF_REMAINING -> {
                // Show used/remaining format: $193/$200
                when {
                    used != null && remaining != null -> {
                        "\$${used.setScale(0, RoundingMode.HALF_UP)}/\$${remaining.setScale(0, RoundingMode.HALF_UP)}"
                    }
                    remaining != null -> {
                        // Fallback: show remaining only
                        "\$${remaining.setScale(0, RoundingMode.HALF_UP)}"
                    }
                    used != null -> {
                        // Fallback: show used only
                        "\$${used.setScale(0, RoundingMode.HALF_UP)} used"
                    }
                    else -> "--"
                }
            }
            StatusBarDollarFormat.PERCENTAGE_REMAINING -> {
                when {
                    remaining != null && total != null && total > BigDecimal.ZERO -> {
                        val percentage = remaining.multiply(BigDecimal(100))
                            .divide(total, 0, RoundingMode.HALF_UP)
                            .toInt()
                        "$percentage% remaining"
                    }
                    remaining != null && used != null -> {
                        val computedTotal = remaining + used
                        if (computedTotal > BigDecimal.ZERO) {
                            val percentage = remaining.multiply(BigDecimal(100))
                                .divide(computedTotal, 0, RoundingMode.HALF_UP)
                                .toInt()
                            "$percentage% remaining"
                        } else {
                            // Fallback to remaining only
                            "\$${remaining.setScale(0, RoundingMode.HALF_UP)}"
                        }
                    }
                    remaining != null -> {
                        // Fallback: can't compute percentage, show remaining
                        "\$${remaining.setScale(0, RoundingMode.HALF_UP)}"
                    }
                    used != null -> {
                        // Fallback: can't compute percentage, show used
                        "\$${used.setScale(0, RoundingMode.HALF_UP)} used"
                    }
                    else -> "--"
                }
            }
        }

        return if (provider != null && text != "--") {
            "$text (${provider.abbreviation})"
        } else {
            text
        }
    }

    /**
     * Extracts the primary usage metric from a snapshot for status bar display.
     * Returns remaining balance, used amount, or usage percentage based on provider type.
     */
    fun getStatusBarDataFromSnapshot(snapshot: BalanceSnapshot): StatusBarData {
        val connectionType = snapshot.connectionType
        val provider = connectionType.provider

        // Check for usage-percentage type first
        if (isUsagePercentageType(connectionType)) {
            return StatusBarData.UsagePercentage(provider)
        }

        // Check for dollar amounts
        val credits = snapshot.balance.credits
        return when {
            credits?.remaining != null -> StatusBarData.RemainingDollars(credits.remaining, provider)
            credits?.used != null -> StatusBarData.UsedDollars(credits.used, provider)
            else -> StatusBarData.NoData
        }
    }

    /** Helper data class for extracting usage percentages */
    private data class UsageData(
        val shortUsed: Int?,
        val longUsed: Int?,
        val shortLabel: String,
        val longLabel: String
    )

    /** Sealed class representing different types of status bar data */
    sealed class StatusBarData {
        data class RemainingDollars(val amount: BigDecimal, val provider: Provider) : StatusBarData()
        data class UsedDollars(val amount: BigDecimal, val provider: Provider) : StatusBarData()
        data class UsagePercentage(val provider: Provider) : StatusBarData()
        data object NoData : StatusBarData()
    }

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
            else -> "No data"
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
        return "$${credits.setScale(2, RoundingMode.HALF_UP)}"
    }

    private fun formatTokens(tokens: Long): String {
        return formatNumber(tokens)
    }

    private fun formatTokensUsage(tokens: Long): String {
        return "${formatNumber(tokens)} used"
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
                    val totalStr = tokens.total?.let { "/ ${numberFormat.format(it)}" } ?: ""
                    parts.add("${formatTokensUsage(tokens.used)}$totalStr")
                }
                tokens.total != null -> parts.add(formatTokens(tokens.total))
                else -> {}
            }
        }
    }

    private fun formatNumber(number: Long): String = numberFormat.format(number)
}
