package org.zhavoronkov.tokenpulse.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Represents a single balance history entry for charting.
 * All values are normalized to percentage (0-100) for unified display.
 *
 * @property accountId The account this entry belongs to.
 * @property timestamp When this snapshot was taken.
 * @property percentageRemaining The normalized percentage (0-100) of remaining balance.
 * @property rawValue The original raw value (for tooltip display).
 * @property rawUnit The unit of the raw value (e.g., "$" or "%").
 */
data class BalanceHistoryEntry(
    val accountId: String,
    val timestamp: Instant,
    val percentageRemaining: Double,
    val rawValue: String,
    val rawUnit: String
) {
    companion object {
        /**
         * Creates a history entry from a balance snapshot.
         * Normalizes all values to percentage for unified chart display.
         *
         * @param snapshot The balance snapshot to convert.
         * @param maxSeenBalance The maximum balance ever seen for this account (for percentage calculation).
         * @return A history entry with normalized percentage value.
         */
        fun fromSnapshot(snapshot: BalanceSnapshot, maxSeenBalance: BigDecimal?): BalanceHistoryEntry {
            val connectionType = snapshot.connectionType

            // For percentage-based providers (Claude Code, ChatGPT), use metadata
            if (connectionType.usesPercentageDisplay) {
                val usedPercent = extractUsagePercentage(snapshot)
                val remainingPercent = (100.0 - usedPercent).coerceIn(0.0, 100.0)
                return BalanceHistoryEntry(
                    accountId = snapshot.accountId,
                    timestamp = snapshot.timestamp,
                    percentageRemaining = remainingPercent,
                    rawValue = "${remainingPercent.toInt()}%",
                    rawUnit = "remaining"
                )
            }

            // For dollar-based providers, calculate percentage from remaining/total or remaining+used
            val remaining = snapshot.balance.credits?.remaining
            val total = snapshot.balance.credits?.total
            val used = snapshot.balance.credits?.used

            return when {
                // If we have both remaining and total, calculate percentage
                remaining != null && total != null && total > BigDecimal.ZERO -> {
                    val percent = remaining.divide(total, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal(100))
                        .toDouble()
                        .coerceIn(0.0, 100.0)
                    BalanceHistoryEntry(
                        accountId = snapshot.accountId,
                        timestamp = snapshot.timestamp,
                        percentageRemaining = percent,
                        rawValue = "$${remaining.setScale(2, RoundingMode.HALF_UP)}",
                        rawUnit = "of $${total.setScale(2, RoundingMode.HALF_UP)}"
                    )
                }
                // If we have remaining and used (but no total), calculate total = remaining + used
                remaining != null && used != null -> {
                    val calculatedTotal = remaining.add(used)
                    if (calculatedTotal > BigDecimal.ZERO) {
                        val percent = remaining.divide(calculatedTotal, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal(100))
                            .toDouble()
                            .coerceIn(0.0, 100.0)
                        BalanceHistoryEntry(
                            accountId = snapshot.accountId,
                            timestamp = snapshot.timestamp,
                            percentageRemaining = percent,
                            rawValue = "$${remaining.setScale(2, RoundingMode.HALF_UP)}",
                            rawUnit = "of $${calculatedTotal.setScale(2, RoundingMode.HALF_UP)}"
                        )
                    } else {
                        // Edge case: both remaining and used are zero
                        BalanceHistoryEntry(
                            accountId = snapshot.accountId,
                            timestamp = snapshot.timestamp,
                            percentageRemaining = 100.0,
                            rawValue = "$${remaining.setScale(2, RoundingMode.HALF_UP)}",
                            rawUnit = "remaining"
                        )
                    }
                }
                // If we only have remaining and a max seen value, use that
                remaining != null && maxSeenBalance != null && maxSeenBalance > BigDecimal.ZERO -> {
                    val percent = remaining.divide(maxSeenBalance, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal(100))
                        .toDouble()
                        .coerceIn(0.0, 100.0)
                    BalanceHistoryEntry(
                        accountId = snapshot.accountId,
                        timestamp = snapshot.timestamp,
                        percentageRemaining = percent,
                        rawValue = "$${remaining.setScale(2, RoundingMode.HALF_UP)}",
                        rawUnit = "remaining"
                    )
                }
                // Fallback: if we only have remaining, show 100% initially
                remaining != null -> {
                    BalanceHistoryEntry(
                        accountId = snapshot.accountId,
                        timestamp = snapshot.timestamp,
                        percentageRemaining = 100.0,
                        rawValue = "$${remaining.setScale(2, RoundingMode.HALF_UP)}",
                        rawUnit = "remaining"
                    )
                }
                // No meaningful data
                else -> {
                    BalanceHistoryEntry(
                        accountId = snapshot.accountId,
                        timestamp = snapshot.timestamp,
                        percentageRemaining = 0.0,
                        rawValue = "N/A",
                        rawUnit = ""
                    )
                }
            }
        }

        /**
         * Extracts usage percentage from metadata for percentage-based providers.
         */
        private fun extractUsagePercentage(snapshot: BalanceSnapshot): Double {
            // Try various metadata keys that might contain usage percentage
            val keys = listOf(
                "5h_percent", "weekly_percent", // ChatGPT
                "session_percent", "week_percent" // Claude Code
            )

            for (key in keys) {
                snapshot.metadata[key]?.let { value ->
                    val parsed = value.replace("%", "").toDoubleOrNull()
                    if (parsed != null) return parsed
                }
            }

            // Fallback: check tokens if available
            val tokensUsed = snapshot.balance.tokens?.used
            val tokensTotal = snapshot.balance.tokens?.total
            if (tokensUsed != null && tokensTotal != null && tokensTotal > 0) {
                return (tokensUsed.toDouble() / tokensTotal.toDouble() * 100.0)
            }

            return 0.0
        }
    }
}

/**
 * Time range options for chart display.
 */
enum class TimeRange(val displayName: String, val hours: Long) {
    HOURS_24("Last 24 Hours", 24),
    DAYS_7("Last 7 Days", 24 * 7),
    DAYS_30("Last 30 Days", 24 * 30),
    ALL("All Time", Long.MAX_VALUE);

    /**
     * Returns the start instant for this time range.
     */
    fun getStartInstant(): Instant {
        return if (this == ALL) {
            Instant.EPOCH
        } else {
            Instant.now().minusSeconds(hours * 3600)
        }
    }
}

/**
 * Chart type options.
 */
enum class ChartType(val displayName: String) {
    LINE("Line Chart"),
    AREA("Area Chart")
}
