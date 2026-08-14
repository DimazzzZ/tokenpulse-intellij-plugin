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
    fun `getUsageColor returns green for high remaining`() {
        val green = ProgressBarRenderer.getUsageColor(100)
        assertEquals(JBColor(Color(0x44AA44), Color(0x66DD66)), green)

        val green50 = ProgressBarRenderer.getUsageColor(50)
        assertEquals(JBColor(Color(0x44AA44), Color(0x66DD66)), green50)

        val green31 = ProgressBarRenderer.getUsageColor(31)
        assertEquals(JBColor(Color(0x44AA44), Color(0x66DD66)), green31)
    }

    @Test
    fun `getUsageColor returns orange for low remaining`() {
        val orange30 = ProgressBarRenderer.getUsageColor(30)
        assertEquals(JBColor(Color(0xCC8800), Color(0xFFBB55)), orange30)

        val orange20 = ProgressBarRenderer.getUsageColor(20)
        assertEquals(JBColor(Color(0xCC8800), Color(0xFFBB55)), orange20)

        val orange11 = ProgressBarRenderer.getUsageColor(11)
        assertEquals(JBColor(Color(0xCC8800), Color(0xFFBB55)), orange11)
    }

    @Test
    fun `getUsageColor returns red for critical remaining`() {
        val red10 = ProgressBarRenderer.getUsageColor(10)
        assertEquals(JBColor(Color(0xCC4444), Color(0xFF7777)), red10)

        val red5 = ProgressBarRenderer.getUsageColor(5)
        assertEquals(JBColor(Color(0xCC4444), Color(0xFF7777)), red5)

        val red0 = ProgressBarRenderer.getUsageColor(0)
        assertEquals(JBColor(Color(0xCC4444), Color(0xFF7777)), red0)
    }
}
