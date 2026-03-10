package org.zhavoronkov.tokenpulse.ui

/**
 * Utility object for rendering progress bars in HTML tooltips.
 *
 * Used by status bar widget to display usage percentages for various providers
 * (Claude Code, ChatGPT, etc.) with color-coded progress indicators.
 */
object ProgressBarRenderer {

    /** Threshold percentage for red (critical) color. */
    private const val CRITICAL_THRESHOLD = 90

    /** Threshold percentage for orange (warning) color. */
    private const val WARNING_THRESHOLD = 70

    /** Red color for critical usage (>= 90%). */
    private const val COLOR_CRITICAL = "#CC4444"

    /** Orange color for warning usage (>= 70%). */
    private const val COLOR_WARNING = "#CC8800"

    /** Green color for normal usage (< 70%). */
    private const val COLOR_NORMAL = "#44AA44"

    /**
     * Get color for usage percentage.
     *
     * @param percent Usage percentage (0-100)
     * @return HTML color code:
     *   - Red (#CC4444) for >= 90%
     *   - Orange (#CC8800) for >= 70%
     *   - Green (#44AA44) for < 70%
     */
    fun getUsageColor(percent: Int): String {
        return when {
            percent >= CRITICAL_THRESHOLD -> COLOR_CRITICAL
            percent >= WARNING_THRESHOLD -> COLOR_WARNING
            else -> COLOR_NORMAL
        }
    }

    /**
     * Build HTML for a horizontal progress bar using Unicode block characters.
     * Uses text-based rendering with fixed-width characters for consistent display.
     *
     * @param percent Usage percentage (0-100)
     * @param color HTML color for the filled portion
     * @param showLabel Whether to show "XX% used" label next to the bar
     * @param barWidth Number of block characters (default: 15 for compact display)
     * @param emptyColor Color for the unfilled portion (default: gray)
     * @return HTML string containing a text-based progress bar
     */
    fun buildProgressBarHtml(
        percent: Int,
        color: String = getUsageColor(percent),
        showLabel: Boolean = true,
        barWidth: Int = 15,
        emptyColor: String = "#444"
    ): String {
        val pct = percent.coerceIn(0, 100)
        val filledBlocks = (pct * barWidth / 100).coerceAtLeast(if (pct > 0) 1 else 0)
        val emptyBlocks = barWidth - filledBlocks

        // Use full block character for solid bar, lower half block for empty
        val filled = "█".repeat(filledBlocks)
        val empty = "░".repeat(emptyBlocks)

        val barHtml = "<font color='$color'>$filled</font><font color='$emptyColor'>$empty</font>"

        return if (showLabel) {
            "$barHtml $pct%"
        } else {
            barHtml
        }
    }

    /**
     * Build a compact usage row with label and progress bar.
     *
     * @param label Label for the usage metric (e.g., "Session", "Weekly")
     * @param percent Usage percentage (0-100)
     * @param resetTime Optional reset time string to display
     * @return HTML string for a complete usage section
     */
    fun buildUsageSection(
        label: String,
        percent: Int,
        resetTime: String? = null
    ): String {
        val color = getUsageColor(percent)
        val barHtml = buildProgressBarHtml(percent, color, showLabel = false, barWidth = 10)
        val resetRow = if (resetTime != null) {
            "<tr><td colspan='2'><font size='-2' color='gray'>Resets: $resetTime</font></td></tr>"
        } else {
            ""
        }

        return """
            <tr><td>$label:</td><td align='right'>$barHtml $percent%</td></tr>
            $resetRow
        """.trimIndent()
    }

    /**
     * Build a balance/remaining progress bar section.
     * Uses inverted colors: high remaining (green) is good, low remaining (red) is warning.
     *
     * @param label Label for the balance metric (e.g., "Balance")
     * @param remainingPercent Percentage of balance remaining (0-100)
     * @param note Optional note to display below the bar
     * @return HTML string for a complete balance section
     */
    fun buildBalanceSection(
        label: String,
        remainingPercent: Int,
        note: String? = null
    ): String {
        // Invert the color logic: high remaining = green, low remaining = red
        val color = getBalanceColor(remainingPercent)
        val noteRow = if (note != null) {
            "<tr><td colspan='2' style='padding-left: 30px;'>" +
                "<font size='-2' color='gray'>$note</font></td></tr>"
        } else {
            ""
        }

        // Build compact bar with percentage - use shorter bar width for balance
        val pct = remainingPercent.coerceIn(0, 100)
        val barHtml = buildProgressBarHtml(pct, color, showLabel = false, barWidth = 10)

        return """
            <tr><td>$label:</td><td align='right'>$barHtml $pct%</td></tr>
            $noteRow
        """.trimIndent()
    }

    /**
     * Get color for balance percentage (inverted from usage).
     * High remaining = green, low remaining = red.
     *
     * @param remainingPercent Balance remaining percentage (0-100)
     * @return HTML color code
     */
    private fun getBalanceColor(remainingPercent: Int): String {
        return when {
            remainingPercent <= 10 -> COLOR_CRITICAL // Almost empty
            remainingPercent <= 30 -> COLOR_WARNING // Getting low
            else -> COLOR_NORMAL // Plenty left
        }
    }

}
