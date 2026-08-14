package org.zhavoronkov.tokenpulse.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Provides theme-aware color selection for the remaining% shown by every
 * usage bar.
 *
 * The bars themselves are drawn as real Swing components by
 * [TokenPulseTooltipPanel]; this object only decides the fill color for a
 * given percentage.
 */
object ProgressBarRenderer {

    /** Remaining% at or below this is red (critical). */
    private const val CRITICAL_THRESHOLD = 10

    /** Remaining% at or below this is orange (warning). */
    private const val WARNING_THRESHOLD = 30

    /** Red color for critical remaining (<= 10%) — theme-aware. */
    private val COLOR_CRITICAL = JBColor(Color(0xCC4444), Color(0xFF7777))

    /** Orange color for warning remaining (<= 30%) — theme-aware. */
    private val COLOR_WARNING = JBColor(Color(0xCC8800), Color(0xFFBB55))

    /** Green color for healthy remaining (> 30%) — theme-aware. */
    private val COLOR_NORMAL = JBColor(Color(0x44AA44), Color(0x66DD66))

    /**
     * Get color for a remaining percentage: high remaining = green, low
     * remaining = red.
     */
    fun getUsageColor(percent: Int): Color {
        return when {
            percent <= CRITICAL_THRESHOLD -> COLOR_CRITICAL
            percent <= WARNING_THRESHOLD -> COLOR_WARNING
            else -> COLOR_NORMAL
        }
    }
}
