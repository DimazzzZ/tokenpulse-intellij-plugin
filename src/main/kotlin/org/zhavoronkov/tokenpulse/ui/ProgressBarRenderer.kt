package org.zhavoronkov.tokenpulse.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Utility object for progress bar rendering and colors.
 *
 * Provides color selection for usage and balance percentages,
 * plus HTML/CSS progress bar generation for the status bar tooltip.
 *
 * The HTML output uses table-based layouts (not nested `<div>` with
 * `width:`, `display:inline-block`, `border-radius`, etc.) because
 * Swing's tooltip HTML renderer only reliably supports a small subset
 * of CSS — primarily inline attributes like `bgcolor` on table cells.
 */
object ProgressBarRenderer {

    /** Threshold percentage for red (critical) color. */
    private const val CRITICAL_THRESHOLD = 90

    /** Threshold percentage for orange (warning) color. */
    private const val WARNING_THRESHOLD = 70

    /** Default bar width in pixels. Kept modest so the tooltip fits on screen. */
    private const val DEFAULT_BAR_WIDTH = 96

    /** Default bar height in pixels. */
    private const val DEFAULT_BAR_HEIGHT = 10

    /** Red color for critical usage (>= 90%) — theme-aware. */
    private val COLOR_CRITICAL = JBColor(Color(0xCC4444), Color(0xFF7777))

    /** Orange color for warning usage (>= 70%) — theme-aware. */
    private val COLOR_WARNING = JBColor(Color(0xCC8800), Color(0xFFBB55))

    /** Green color for normal usage (< 70%) — theme-aware. */
    private val COLOR_NORMAL = JBColor(Color(0x44AA44), Color(0x66DD66))

    /** Empty/unfilled portion of the bar. */
    private val COLOR_EMPTY = JBColor(Color(0xDDDDDD), Color(0x555555))

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

    /**
     * Builds an HTML progress bar suitable for Swing tooltips.
     *
     * Uses nested `<table>` cells with `bgcolor` and explicit width
     * attributes — the only reliable way to draw a fixed-width bar in
     * a Swing-rendered tooltip.
     */
    fun buildProgressBarHtml(
        percent: Int,
        color: Color,
        width: Int = DEFAULT_BAR_WIDTH,
        height: Int = DEFAULT_BAR_HEIGHT
    ): String {
        val clamped = percent.coerceIn(0, 100)
        val filledWidth = (width * clamped / 100.0)
        val emptyWidth = (width - filledWidth).coerceAtLeast(0.0)

        val filledColor = color.toHtml()
        val emptyColor = COLOR_EMPTY.toHtml()

        return buildString {
            append("<span style=\"white-space:nowrap;\">")
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
            append("style=\"display:inline;border-collapse:collapse;vertical-align:middle;\">")
            append("<tr>")
            if (filledWidth >= 1.0) {
                append("<td width=\"" + filledWidth.toInt() + "\" height=\"" + height + "\" bgcolor=\"" + filledColor + "\" ")
                append("style=\"background:" + filledColor + ";width:" + filledWidth.toInt() + "px;height:" + height + "px;line-height:" + height + "px;font-size:1px;\">&nbsp;</td>")
            }
            if (emptyWidth >= 1.0) {
                append("<td width=\"" + emptyWidth.toInt() + "\" height=\"" + height + "\" bgcolor=\"" + emptyColor + "\" ")
                append("style=\"background:" + emptyColor + ";width:" + emptyWidth.toInt() + "px;height:" + height + "px;line-height:" + height + "px;font-size:1px;\">&nbsp;</td>")
            } else if (filledWidth < 1.0) {
                append("<td width=\"" + width + "\" height=\"" + height + "\" bgcolor=\"" + emptyColor + "\" ")
                append("style=\"background:" + emptyColor + ";width:" + width + "px;height:" + height + "px;line-height:" + height + "px;font-size:1px;\">&nbsp;</td>")
            }
            append("</tr></table>")
            append("</span>")
        }
    }

    /**
     * Builds a usage section with a label, percentage, and progress bar.
     * Returns a single `<tr>` that fits inside the per-account table.
     */
    fun buildUsageSection(label: String, percent: Int, resetAt: String? = null): String {
        val clamped = percent.coerceIn(0, 100)
        val resetText = resetAt?.takeIf { it.isNotBlank() }?.let {
            " <span style=\"color:#888888;font-size:11px;\">" + it.escapeHtml() + "</span>"
        } ?: ""
        return buildUsageRow(label, clamped, resetText)
    }

    /**
     * Builds a usage section with a label, percentage, and progress bar,
     * but does NOT include the reset timestamp inline.
     *
     * This variant is used by [TokenPulseStatusTooltipPanel] when rendering
     * ClinePass metrics, because reset timestamps are placed on their own
     * subtle row below instead of next to the percentage.
     */
    fun buildUsageSectionNoReset(label: String, percent: Int): String {
        val clamped = percent.coerceIn(0, 100)
        return buildUsageRow(label, clamped, "")
    }

    /**
     * Internal helper that builds a single usage row HTML string.
     * Shared by [buildUsageSection] and [buildUsageSectionNoReset] to avoid
     * duplication and keep future layout changes in one place.
     */
    private fun buildUsageRow(label: String, percent: Int, resetText: String): String {
        val color = getUsageColor(percent)
        val bar = buildProgressBarHtml(percent, color)
        val safeLabel = label.escapeHtml()
        return "<tr>" +
            "<td style=\"padding:2px 8px 2px 0;color:#888888;white-space:nowrap;\">" + safeLabel + "</td>" +
            "<td style=\"padding:2px 0;white-space:nowrap;\">" +
            bar + " " +
            "<span style=\"font-weight:bold;\">" + percent + "%</span>" +
            resetText +
            "</td></tr>"
    }

    /**
     * Builds a balance section with a label, percentage, and progress bar.
     * Returns a single `<tr>` that fits inside the per-account table.
     */
    fun buildBalanceSection(label: String, remainingPercent: Int, resetAt: String? = null): String {
        val color = getBalanceColor(remainingPercent)
        val bar = buildProgressBarHtml(remainingPercent, color)
        val resetText = resetAt?.takeIf { it.isNotBlank() }?.let {
            " <span style=\"color:#888888;font-size:11px;\">" + it.escapeHtml() + "</span>"
        } ?: ""
        val safeLabel = label.escapeHtml()
        val clamped = remainingPercent.coerceIn(0, 100)
        return "<tr>" +
            "<td style=\"padding:2px 8px 2px 0;color:#888888;white-space:nowrap;\">" + safeLabel + "</td>" +
            "<td style=\"padding:2px 0;white-space:nowrap;\">" +
            bar + " " +
            "<span style=\"font-weight:bold;\">" + clamped + "%</span>" +
            resetText +
            "</td></tr>"
    }

    /**
     * Converts a [Color] to a hex HTML color string.
     */
    private fun Color.toHtml(): String {
        val r = red.toString(16).padStart(2, '0')
        val g = green.toString(16).padStart(2, '0')
        val b = blue.toString(16).padStart(2, '0')
        return "#" + r + g + b
    }

    /**
     * Minimal HTML escape used by the section/row builders.
     */
    private fun String.escapeHtml(): String {
        return this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
