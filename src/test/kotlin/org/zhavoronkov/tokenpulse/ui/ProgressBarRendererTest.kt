package org.zhavoronkov.tokenpulse.ui

import com.intellij.ui.JBColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Tests for [ProgressBarRenderer] color utilities and HTML progress bar generation.
 */
class ProgressBarRendererTest {

    @Test
    fun `getUsageColor returns green for low usage`() {
        val green = ProgressBarRenderer.getUsageColor(0)
        assertEquals(JBColor(Color(0x44AA44), Color(0x66DD66)), green)

        val green50 = ProgressBarRenderer.getUsageColor(50)
        assertEquals(JBColor(Color(0x44AA44), Color(0x66DD66)), green50)

        val green69 = ProgressBarRenderer.getUsageColor(69)
        assertEquals(JBColor(Color(0x44AA44), Color(0x66DD66)), green69)
    }

    @Test
    fun `getUsageColor returns orange for medium usage`() {
        val orange70 = ProgressBarRenderer.getUsageColor(70)
        assertEquals(JBColor(Color(0xCC8800), Color(0xFFBB55)), orange70)

        val orange80 = ProgressBarRenderer.getUsageColor(80)
        assertEquals(JBColor(Color(0xCC8800), Color(0xFFBB55)), orange80)

        val orange89 = ProgressBarRenderer.getUsageColor(89)
        assertEquals(JBColor(Color(0xCC8800), Color(0xFFBB55)), orange89)
    }

    @Test
    fun `getUsageColor returns red for high usage`() {
        val red90 = ProgressBarRenderer.getUsageColor(90)
        assertEquals(JBColor(Color(0xCC4444), Color(0xFF7777)), red90)

        val red95 = ProgressBarRenderer.getUsageColor(95)
        assertEquals(JBColor(Color(0xCC4444), Color(0xFF7777)), red95)

        val red100 = ProgressBarRenderer.getUsageColor(100)
        assertEquals(JBColor(Color(0xCC4444), Color(0xFF7777)), red100)
    }

    @Test
    fun `getBalanceColor returns green for high remaining`() {
        val green = ProgressBarRenderer.getBalanceColor(100)
        assertEquals(JBColor(Color(0x44AA44), Color(0x66DD66)), green)

        val green50 = ProgressBarRenderer.getBalanceColor(50)
        assertEquals(JBColor(Color(0x44AA44), Color(0x66DD66)), green50)

        val green31 = ProgressBarRenderer.getBalanceColor(31)
        assertEquals(JBColor(Color(0x44AA44), Color(0x66DD66)), green31)
    }

    @Test
    fun `getBalanceColor returns orange for low remaining`() {
        val orange30 = ProgressBarRenderer.getBalanceColor(30)
        assertEquals(JBColor(Color(0xCC8800), Color(0xFFBB55)), orange30)

        val orange20 = ProgressBarRenderer.getBalanceColor(20)
        assertEquals(JBColor(Color(0xCC8800), Color(0xFFBB55)), orange20)

        val orange11 = ProgressBarRenderer.getBalanceColor(11)
        assertEquals(JBColor(Color(0xCC8800), Color(0xFFBB55)), orange11)
    }

    @Test
    fun `getBalanceColor returns red for critical remaining`() {
        val red10 = ProgressBarRenderer.getBalanceColor(10)
        assertEquals(JBColor(Color(0xCC4444), Color(0xFF7777)), red10)

        val red5 = ProgressBarRenderer.getBalanceColor(5)
        assertEquals(JBColor(Color(0xCC4444), Color(0xFF7777)), red5)

        val red0 = ProgressBarRenderer.getBalanceColor(0)
        assertEquals(JBColor(Color(0xCC4444), Color(0xFF7777)), red0)
    }

    @Test
    fun `buildProgressBarHtml uses table cells for Swing compatibility`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(50, Color.RED)
        // Should be a table-based bar, not nested divs.
        assertTrue(html.contains("<table"), "Expected <table> in progress bar HTML")
        assertTrue(html.contains("</table>"))
        assertTrue(html.contains("<td"), "Expected <td> cells in progress bar HTML")
        assertTrue(html.contains("bgcolor="), "Expected bgcolor attribute for color")
        assertTrue(html.contains("width="), "Expected width attribute on cells")
        // Should not use unsupported CSS features.
        assertTrue(!html.contains("display:inline-block"), "Should not use inline-block")
        assertTrue(!html.contains("border-radius"), "Should not use border-radius")
        assertTrue(!html.contains("overflow:hidden"), "Should not use overflow:hidden")
    }

    @Test
    fun `buildProgressBarHtml at zero percent draws empty bar`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(0, Color.RED)
        // When percent is 0 the empty color should still occupy the full width.
        assertTrue(html.contains("width=\"96\""), "Expected full-width empty cell at 0%")
        assertTrue(html.contains("bgcolor=\"#dddddd\""), "Expected empty bar color")
    }

    @Test
    fun `buildProgressBarHtml at one hundred percent draws full bar`() {
        val html = ProgressBarRenderer.buildProgressBarHtml(100, Color.RED)
        // Filled cell should span the full bar width.
        assertTrue(html.contains("width=\"96\""), "Expected full-width filled cell at 100%")
    }

    @Test
    fun `buildProgressBarHtml clamps out-of-range percent`() {
        val overHtml = ProgressBarRenderer.buildProgressBarHtml(150, Color.RED)
        assertTrue(overHtml.contains("width=\"96\""), "Should clamp to 100% width")

        val negHtml = ProgressBarRenderer.buildProgressBarHtml(-10, Color.RED)
        assertTrue(negHtml.contains("width=\"96\""), "Should clamp to 0% (full empty)")
    }

    @Test
    fun `buildUsageSection contains label and percentage`() {
        val html = ProgressBarRenderer.buildUsageSection("5-hour", 75)
        assertTrue(html.contains("5-hour"))
        assertTrue(html.contains("75%"))
        assertTrue(html.contains("<tr>"))
        assertTrue(html.contains("<table"))
    }

    @Test
    fun `buildUsageSection escapes label HTML`() {
        val html = ProgressBarRenderer.buildUsageSection("<script>", 10)
        assertTrue(!html.contains("<script>"), "Label should be HTML-escaped")
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `buildUsageSection with resetAt includes reset text`() {
        val html = ProgressBarRenderer.buildUsageSection("Weekly", 40, "in 3 days")
        assertTrue(html.contains("Weekly"))
        assertTrue(html.contains("40%"))
        assertTrue(html.contains("in 3 days"))
    }

    @Test
    fun `buildUsageSection with blank resetAt omits reset text`() {
        val html = ProgressBarRenderer.buildUsageSection("Weekly", 40, "  ")
        assertTrue(!html.contains("</span></td></tr><span"),
            "Blank resetAt should not produce extra span content")
    }

    @Test
    fun `buildBalanceSection contains label and percentage`() {
        val html = ProgressBarRenderer.buildBalanceSection("Credits", 25)
        assertTrue(html.contains("Credits"))
        assertTrue(html.contains("25%"))
        assertTrue(html.contains("<tr>"))
        assertTrue(html.contains("<table"))
    }

    @Test
    fun `buildBalanceSection uses balance color thresholds`() {
        // 5% remaining → red (critical)
        val redHtml = ProgressBarRenderer.buildBalanceSection("Credits", 5)
        assertTrue(redHtml.contains("bgcolor=\"#cc4444\""))

        // 20% remaining → orange
        val orangeHtml = ProgressBarRenderer.buildBalanceSection("Credits", 20)
        assertTrue(orangeHtml.contains("bgcolor=\"#cc8800\""))

        // 60% remaining → green
        val greenHtml = ProgressBarRenderer.buildBalanceSection("Credits", 60)
        assertTrue(greenHtml.contains("bgcolor=\"#44aa44\""))
    }
}
