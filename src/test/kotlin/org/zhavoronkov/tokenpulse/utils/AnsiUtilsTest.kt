package org.zhavoronkov.tokenpulse.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnsiUtilsTest {

    @Test
    fun `stripAnsiCodes removes SGR sequences`() {
        val text = "\u001B[31mRed\u001B[0m"
        assertEquals("Red", AnsiUtils.stripAnsiCodes(text))
    }

    @Test
    fun `stripAnsiCodes removes bold and color combined`() {
        val text = "\u001B[1;32mBold Green\u001B[0m"
        assertEquals("Bold Green", AnsiUtils.stripAnsiCodes(text))
    }

    @Test
    fun `stripAnsiCodes removes cursor movement sequences`() {
        val text = "\u001B[2J\u001B[HScreen cleared"
        assertEquals("Screen cleared", AnsiUtils.stripAnsiCodes(text))
    }

    @Test
    fun `stripAnsiCodes handles empty string`() {
        assertEquals("", AnsiUtils.stripAnsiCodes(""))
    }

    @Test
    fun `stripAnsiCodes handles plain text with no escapes`() {
        val text = "Hello world, no ANSI here"
        assertEquals(text, AnsiUtils.stripAnsiCodes(text))
    }

    @Test
    fun `stripAnsiCodes handles multiple escape sequences in one string`() {
        val text = "\u001B[31mRed\u001B[0m Normal \u001B[32mGreen\u001B[0m"
        assertEquals("Red Normal Green", AnsiUtils.stripAnsiCodes(text))
    }

    @Test
    fun `stripAnsiCodes handles Fe sequences without bracket`() {
        val text = "test\u001BMtest"
        assertEquals("testtest", AnsiUtils.stripAnsiCodes(text))
    }

    @Test
    fun `stripAnsiCodes handles 256-color sequences`() {
        val text = "\u001B[38;5;82mColor256\u001B[0m"
        assertEquals("Color256", AnsiUtils.stripAnsiCodes(text))
    }

    @Test
    fun `stripAnsiCodes handles truecolor sequences`() {
        val text = "\u001B[38;2;255;128;0mTrueColor\u001B[0m"
        assertEquals("TrueColor", AnsiUtils.stripAnsiCodes(text))
    }

    @Test
    fun `stripAnsiCodes returns empty for string that is entirely ANSI codes`() {
        val text = "\u001B[31m\u001B[0m\u001B[1m\u001B[0m"
        assertEquals("", AnsiUtils.stripAnsiCodes(text))
    }

    @Test
    fun `stripAnsiCodes preserves newlines and whitespace`() {
        val text = "\u001B[31mLine1\u001B[0m\n  Line2\n\u001B[32m  Line3\u001B[0m"
        assertEquals("Line1\n  Line2\n  Line3", AnsiUtils.stripAnsiCodes(text))
    }
}
