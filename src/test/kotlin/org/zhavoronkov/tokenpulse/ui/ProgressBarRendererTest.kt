package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [ProgressBarRenderer].
 */
class ProgressBarRendererTest {

    @Test
    fun `getUsageColor returns green for low usage`() {
        assertEquals("#44AA44", ProgressBarRenderer.getUsageColor(0))
        assertEquals("#44AA44", ProgressBarRenderer.getUsageColor(50))
        assertEquals("#44AA44", ProgressBarRenderer.getUsageColor(69))
    }

    @Test
    fun `getUsageColor returns orange for medium usage`() {
        assertEquals("#CC8800", ProgressBarRenderer.getUsageColor(70))
        assertEquals("#CC8800", ProgressBarRenderer.getUsageColor(80))
        assertEquals("#CC8800", ProgressBarRenderer.getUsageColor(89))
    }

    @Test
    fun `getUsageColor returns red for high usage`() {
        assertEquals("#CC4444", ProgressBarRenderer.getUsageColor(90))
        assertEquals("#CC4444", ProgressBarRenderer.getUsageColor(95))
        assertEquals("#CC4444", ProgressBarRenderer.getUsageColor(100))
    }

    @Test
    fun `buildProgressBarHtml contains filled blocks`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(50)

        assertTrue(html.contains("█"))
        assertTrue(html.contains("░"))
    }

    @Test
    fun `buildProgressBarHtml shows percentage with label`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(75, showLabel = true)

        assertTrue(html.contains("75%"))
    }

    @Test
    fun `buildProgressBarHtml hides percentage without label`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(75, showLabel = false)

        assertFalse(html.contains("75%"))
    }

    @Test
    fun `buildProgressBarHtml uses correct color`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(50, color = "#00FF00")

        assertTrue(html.contains("#00FF00"))
    }

    @Test
    fun `buildProgressBarHtml coerces percent to 0-100`() {
        val htmlNegative = ProgressBarRenderer.buildProgressBarHtml(-10)
        val htmlOver = ProgressBarRenderer.buildProgressBarHtml(150)

        assertTrue(htmlNegative.contains("0%"))
        assertTrue(htmlOver.contains("100%"))
    }

    @Test
    fun `buildProgressBarHtml has at least one filled block for non-zero percent`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(1, barWidth = 15)

        assertTrue(html.contains("█"))
    }

    @Test
    fun `buildProgressBarHtml is all empty for zero percent`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(0, barWidth = 5)

        // Should have 5 empty blocks and 0 filled
        assertTrue(html.contains("░░░░░"))
        assertFalse(html.contains("█"))
    }

    @Test
    fun `buildProgressBarHtml uses custom bar width`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(100, barWidth = 10)

        // Should have 10 filled blocks
        assertTrue(html.contains("██████████"))
    }

    @Test
    fun `buildUsageSection contains label`() {
        val html = ProgressBarRenderer.buildUsageSection("Session", 50)

        assertTrue(html.contains("Session"))
    }

    @Test
    fun `buildUsageSection includes progress bar`() {
        val html = ProgressBarRenderer.buildUsageSection("Weekly", 75)

        assertTrue(html.contains("█"))
        assertTrue(html.contains("75%"))
    }

    @Test
    fun `buildUsageSection shows reset time when provided`() {
        val html = ProgressBarRenderer.buildUsageSection("Daily", 30, "Tomorrow at midnight")

        assertTrue(html.contains("Resets:"))
        assertTrue(html.contains("Tomorrow at midnight"))
    }

    @Test
    fun `buildUsageSection omits reset row when not provided`() {
        val html = ProgressBarRenderer.buildUsageSection("Session", 50, null)

        assertFalse(html.contains("Resets:"))
    }

    @Test
    fun `buildUsageSection has table rows`() {
        val html = ProgressBarRenderer.buildUsageSection("Test", 25)

        assertTrue(html.contains("<tr>"))
        assertTrue(html.contains("<td"))
    }
}
