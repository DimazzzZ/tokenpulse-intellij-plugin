package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for [CodexCliOutputParser].
 */
class CodexCliOutputParserTest {

    @Test
    fun `stripAnsiCodes removes escape sequences`() {
        val input = "\u001B[31mRed Text\u001B[0m Normal"
        assertEquals("Red Text Normal", CodexCliOutputParser.stripAnsiCodes(input))
    }

    @Test
    fun `stripAnsiCodes handles empty string`() {
        assertEquals("", CodexCliOutputParser.stripAnsiCodes(""))
    }

    @Test
    fun `stripAnsiCodes handles plain text`() {
        val input = "Plain text without codes"
        assertEquals(input, CodexCliOutputParser.stripAnsiCodes(input))
    }

    @Test
    fun `parseUsagePercent finds 5-hour usage pattern`() {
        val output = "5-hour usage: 45% used"
        assertEquals(45, CodexCliOutputParser.parseUsagePercent(output, "5-hour"))
    }

    @Test
    fun `parseUsagePercent finds weekly usage pattern`() {
        val output = "Weekly usage: 12% used"
        assertEquals(12, CodexCliOutputParser.parseUsagePercent(output, "weekly"))
    }

    @Test
    fun `parseUsagePercent returns null for missing data`() {
        val output = "Some unrelated output"
        assertNull(CodexCliOutputParser.parseUsagePercent(output, "5-hour"))
    }

    @Test
    fun `parseUsagePercent handles 5h shorthand`() {
        val output = "5h usage: 75% used"
        assertEquals(75, CodexCliOutputParser.parseUsagePercent(output, "5h"))
    }

    @Test
    fun `parseUsagePercent handles session pattern`() {
        // Session is aliased to 5-hour pattern in the parser
        val output = "5-hour usage: 50% used"
        assertEquals(50, CodexCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `parseUsagePercent handles week pattern`() {
        val output = "week: 30% used"
        assertEquals(30, CodexCliOutputParser.parseUsagePercent(output, "week"))
    }

    @Test
    fun `parseResetTime finds reset time`() {
        val output = "5-hour usage: 45% used\nResets at 6:00 PM"
        val resetTime = CodexCliOutputParser.parseResetTime(output, "5-hour")
        assertTrue(resetTime?.contains("6:00 PM") == true)
    }

    @Test
    fun `parseResetTime returns null for missing section`() {
        val output = "Some random text without reset information"
        assertNull(CodexCliOutputParser.parseResetTime(output, "5-hour"))
    }

    @Test
    fun `detectError finds token_expired`() {
        val output = "auth error: 401, auth error code: token_expired"
        val error = CodexCliOutputParser.detectError(output)
        assertEquals("token_expired", error?.errorCode)
    }

    @Test
    fun `detectError finds refresh_token_reused`() {
        val output = "refresh token has already been used"
        val error = CodexCliOutputParser.detectError(output)
        assertEquals("refresh_token_reused", error?.errorCode)
    }

    @Test
    fun `detectError finds rate_limited`() {
        val output = "Rate limit exceeded. Too many requests."
        val error = CodexCliOutputParser.detectError(output)
        assertEquals("rate_limited", error?.errorCode)
    }

    @Test
    fun `detectError returns null for normal output`() {
        val output = "5-hour usage: 45% used\nWeekly usage: 12% used"
        assertNull(CodexCliOutputParser.detectError(output))
    }

    @Test
    fun `detectAuthIssue finds not authenticated`() {
        val output = "You are not authenticated. Please log in."
        val error = CodexCliOutputParser.detectAuthIssue(output)
        assertEquals("not_authenticated", error?.errorCode)
    }

    @Test
    fun `detectAuthIssue finds 401 unauthorized`() {
        val output = "401 Unauthorized: token expired"
        val error = CodexCliOutputParser.detectAuthIssue(output)
        assertEquals("unauthorized", error?.errorCode)
    }

    @Test
    fun `detectAuthIssue returns null for valid output`() {
        val output = "5-hour usage: 45% used"
        assertNull(CodexCliOutputParser.detectAuthIssue(output))
    }

    @Test
    fun `hasStatusDialogContent detects status indicators`() {
        assertTrue(CodexCliOutputParser.hasStatusDialogContent("5-hour usage: 45%"))
        assertTrue(CodexCliOutputParser.hasStatusDialogContent("weekly usage: 12%"))
        assertTrue(CodexCliOutputParser.hasStatusDialogContent("Resets at 6pm"))
        assertTrue(CodexCliOutputParser.hasStatusDialogContent("workdir: /home/user"))
        assertTrue(CodexCliOutputParser.hasStatusDialogContent("model: gpt-5.3-codex"))
    }

    @Test
    fun `hasStatusDialogContent returns false for unrelated output`() {
        assertFalse(CodexCliOutputParser.hasStatusDialogContent("Hello, how can I help?"))
        assertFalse(CodexCliOutputParser.hasStatusDialogContent("Random text"))
    }

    @Test
    fun `parseStatusOutput returns success for valid output`() {
        val output = """
            5-hour usage: 45% used
            Resets at 6:00 PM

            Weekly usage: 12% used
            Resets at Mar 13 at 3:00 PM
        """.trimIndent()

        val result = CodexCliOutputParser.parseStatusOutput(output)
        assertTrue(result is CodexCliOutputParser.ParseResult.Success)
        if (result is CodexCliOutputParser.ParseResult.Success) {
            assertEquals(45, result.statusData.fiveHourUsedPercent)
            assertEquals(12, result.statusData.weeklyUsedPercent)
        }
    }

    @Test
    fun `parseStatusOutput returns error for token_expired`() {
        val output = "auth error: 401, auth error code: token_expired"
        val result = CodexCliOutputParser.parseStatusOutput(output)
        assertTrue(result is CodexCliOutputParser.ParseResult.Error)
        if (result is CodexCliOutputParser.ParseResult.Error) {
            assertEquals("token_expired", result.errorCode)
        }
    }

    @Test
    fun `parseStatusOutput returns error for refresh_token_reused`() {
        val output = "refresh token has already been used to generate a new access token"
        val result = CodexCliOutputParser.parseStatusOutput(output)
        assertTrue(result is CodexCliOutputParser.ParseResult.Error)
        if (result is CodexCliOutputParser.ParseResult.Error) {
            assertEquals("refresh_token_reused", result.errorCode)
        }
    }

    @Test
    fun `parseStatusOutput returns NoData for empty output`() {
        val output = ""
        val result = CodexCliOutputParser.parseStatusOutput(output)
        assertTrue(result is CodexCliOutputParser.ParseResult.NoData)
    }

    @Test
    fun `parseStatusOutput handles ANSI codes in output`() {
        val output = "\u001B[32m5-hour usage: 45% used\u001B[0m"
        val result = CodexCliOutputParser.parseStatusOutput(output)
        assertTrue(result is CodexCliOutputParser.ParseResult.Success)
        if (result is CodexCliOutputParser.ParseResult.Success) {
            assertEquals(45, result.statusData.fiveHourUsedPercent)
        }
    }

    @Test
    fun `parseStatusOutput handles 5h shorthand`() {
        val output = "5h usage: 75% used\nResets at 8pm"
        val result = CodexCliOutputParser.parseStatusOutput(output)
        assertTrue(result is CodexCliOutputParser.ParseResult.Success)
        if (result is CodexCliOutputParser.ParseResult.Success) {
            assertEquals(75, result.statusData.fiveHourUsedPercent)
        }
    }

    @Test
    fun `parseStatusOutput handles week shorthand`() {
        val output = "week usage: 30% used\nResets tomorrow"
        val result = CodexCliOutputParser.parseStatusOutput(output)
        assertTrue(result is CodexCliOutputParser.ParseResult.Success)
        if (result is CodexCliOutputParser.ParseResult.Success) {
            assertEquals(30, result.statusData.weeklyUsedPercent)
        }
    }
}
