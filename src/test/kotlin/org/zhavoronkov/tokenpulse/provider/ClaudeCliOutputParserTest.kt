package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliOutputParser

/**
 * Unit tests for [ClaudeCliOutputParser].
 *
 * Tests pure parsing functions without requiring Claude CLI installation.
 */
class ClaudeCliOutputParserTest {

    @Test
    fun `stripAnsiCodes removes ANSI escape sequences`() {
        val input = "\u001B[31mRed Text\u001B[0m Normal"
        assertEquals("Red Text Normal", ClaudeCliOutputParser.stripAnsiCodes(input))
    }

    @Test
    fun `stripAnsiCodes handles empty string`() {
        assertEquals("", ClaudeCliOutputParser.stripAnsiCodes(""))
    }

    @Test
    fun `stripAnsiCodes handles string without ANSI codes`() {
        val input = "Plain text without codes"
        assertEquals(input, ClaudeCliOutputParser.stripAnsiCodes(input))
    }

    @Test
    fun `stripAnsiCodes removes multiple escape sequences`() {
        val input = "\u001B[1m\u001B[34mBold Blue\u001B[0m and \u001B[32mGreen\u001B[0m"
        assertEquals("Bold Blue and Green", ClaudeCliOutputParser.stripAnsiCodes(input))
    }

    @Test
    fun `parseUsagePercent extracts session percentage from TUI output`() {
        val output = """
            Current session    ████████████████████████████████████████████████  96% used
            Resets 6pm (Europe/Belgrade)
        """.trimIndent()

        assertEquals(96, ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `parseUsagePercent extracts week percentage from TUI output`() {
        val output = """
            Current week (all models)
            ███████                                           14% used
            Resets Mar 13 at 3pm (Europe/Belgrade)
        """.trimIndent()

        assertEquals(14, ClaudeCliOutputParser.parseUsagePercent(output, "week"))
    }

    @Test
    fun `parseUsagePercent returns null when period not found`() {
        val output = "Some unrelated output"
        assertNull(ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `parseUsagePercent handles zero percent`() {
        val output = "Current session  0% used"
        assertEquals(0, ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `parseUsagePercent handles 100 percent`() {
        val output = "Current session  100% used"
        assertEquals(100, ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `parseUsagePercent handles stripped spaces in TUI`() {
        // Sometimes TUI output has spaces stripped
        val output = "Currentsession96%used"
        assertEquals(96, ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `parseUsagePercent handles status bar format`() {
        val output = "used 50% of your session limit"
        assertEquals(50, ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `parseResetTime extracts session reset time`() {
        val output = """
            Current session    96% used
            Resets 6pm (Europe/Belgrade)
        """.trimIndent()

        val resetTime = ClaudeCliOutputParser.parseResetTime(output, "session")
        assertTrue(resetTime?.contains("6pm") == true)
    }

    @Test
    fun `parseResetTime extracts week reset time with date`() {
        val output = """
            Current week (all models)
            14% used
            Resets Mar 13 at 3pm (Europe/Belgrade)
        """.trimIndent()

        val resetTime = ClaudeCliOutputParser.parseResetTime(output, "week")
        assertTrue(resetTime?.contains("Mar 13") == true)
    }

    @Test
    fun `parseResetTime returns null when not found`() {
        val output = "Current session 50% used"
        assertNull(ClaudeCliOutputParser.parseResetTime(output, "session"))
    }

    @Test
    fun `parseUsageOutput extracts complete usage data`() {
        val output = """
            Current session    ████████████████████████████████████████████████  96% used
            Resets 6pm (Europe/Belgrade)

            Current week (all models)
            ███████                                           14% used
            Resets Mar 13 at 3pm (Europe/Belgrade)
        """.trimIndent()

        val data = ClaudeCliOutputParser.parseUsageOutput(output)

        assertEquals(96, data.sessionUsedPercent)
        assertEquals(14, data.weekUsedPercent)
        assertTrue(data.sessionResetsAt?.contains("6pm") == true)
        assertTrue(data.weekResetsAt?.contains("Mar 13") == true)
    }

    @Test
    fun `hasUsageDialogContent returns true for valid output`() {
        val output = "Current session 50% used"
        assertTrue(ClaudeCliOutputParser.hasUsageDialogContent(output))
    }

    @Test
    fun `hasUsageDialogContent returns false for unrelated output`() {
        val output = "Hello, how can I help you?"
        assertFalse(ClaudeCliOutputParser.hasUsageDialogContent(output))
    }

    @Test
    fun `hasUsageDialogContent handles ANSI codes`() {
        val output = "\u001B[31mCurrent session\u001B[0m 50% used"
        assertTrue(ClaudeCliOutputParser.hasUsageDialogContent(output))
    }

    @Test
    fun `detectError returns null for normal output`() {
        val output = "Current session 50% used"
        assertNull(ClaudeCliOutputParser.detectError(output))
    }

    @Test
    fun `detectError detects authentication error`() {
        val output = "Error: not authenticated. Please log in first."
        val error = ClaudeCliOutputParser.detectError(output)
        assertTrue(error?.contains("not authenticated") == true)
    }

    @Test
    fun `detectError detects rate limit error`() {
        val output = "Rate limit exceeded. Too many requests."
        val error = ClaudeCliOutputParser.detectError(output)
        assertTrue(error?.contains("Rate limit") == true)
    }

    @Test
    fun `detectError detects network error`() {
        val output = "Network error: connection refused"
        val error = ClaudeCliOutputParser.detectError(output)
        assertTrue(error?.contains("Network error") == true)
    }

    @Test
    fun `parseUsageOutput handles empty string`() {
        val data = ClaudeCliOutputParser.parseUsageOutput("")
        assertNull(data.sessionUsedPercent)
        assertNull(data.weekUsedPercent)
    }

    @Test
    fun `parseUsageOutput handles partial data`() {
        val output = "Current session 25% used"
        val data = ClaudeCliOutputParser.parseUsageOutput(output)
        assertEquals(25, data.sessionUsedPercent)
        assertNull(data.weekUsedPercent)
    }
}
