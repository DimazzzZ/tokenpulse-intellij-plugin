package org.zhavoronkov.tokenpulse.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Provides theme-aware color selection for usage and balance percentages.
 *
 * The bars themselves are drawn as real Swing components by
 * [TokenPulseTooltipPanel]; this object only decides the fill color for a
 * given percentage.
 */
object ProgressBarRenderer {

    /** Threshold percentage for red (critical) color. */
    private const val CRITICAL_THRESHOLD = 90

    /** Threshold percentage for orange (warning) color. */
    private const val WARNING_THRESHOLD = 70

    /** Red color for critical usage (>= 90%) — theme-aware. */
    private val COLOR_CRITICAL = JBColor(Color(0xCC4444), Color(0xFF7777))

    /** Orange color for warning usage (>= 70%) — theme-aware. */
    private val COLOR_WARNING = JBColor(Color(0xCC8800), Color(0xFFBB55))

    /** Green color for normal usage (< 70%) — theme-aware. */
    private val COLOR_NORMAL = JBColor(Color(0x44AA44), Color(0x66DD66))

    /**
     * Get color for usage percentage.
     */
    fun getUsageColor(percent: Int): Color {
        return when {
            percent >= CRITICAL_THRESHOLD -> COLOR_CRITICAL
            percent >= WARNING_THRESHOLD -> COLOR_WARNING
            else -> COLOR_NORMAL
        }
    }

    /**
     * Get color for balance percentage (inverted from usage).
     * High remaining = green, low remaining = red.
     */
    fun getBalanceColor(remainingPercent: Int): Color {
        return when {
            remainingPercent <= 10 -> COLOR_CRITICAL
            remainingPercent <= 30 -> COLOR_WARNING
            else -> COLOR_NORMAL
        }
    }
}
