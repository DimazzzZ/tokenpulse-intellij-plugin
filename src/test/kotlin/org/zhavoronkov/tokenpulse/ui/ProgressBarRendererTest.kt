package org.zhavoronkov.tokenpulse.ui

import com.intellij.ui.JBColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Tests for [ProgressBarRenderer] color utilities.
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
}
