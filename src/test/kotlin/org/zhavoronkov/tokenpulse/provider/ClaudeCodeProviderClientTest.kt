package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliExecutor
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliOutputParser

/**
 * Tests for the Claude Code provider (CLI-based).
 *
 * Since the provider requires the Claude CLI to be installed and authenticated,
 * most tests focus on the output parser which can be tested in isolation.
 */
class ClaudeCodeProviderClientTest {

    @Test
    fun `ClaudeCliOutputParser parses session and week percentages`() {
        val output = """
            Settings:  Status   Config   Usage  (←/→ or tab to cycle)
            
            
            Current session
                                                                 0% used
            Resets 11pm (Europe/Belgrade)
            
            Current week (all models)
            ██████████████████████████                         52% used
            Resets Mar 6 at 3pm (Europe/Belgrade)
        """.trimIndent()

        val result = ClaudeCliOutputParser.parseUsageOutput(output)

        assertEquals(0, result.sessionUsedPercent)
        assertEquals(52, result.weekUsedPercent)
    }

    @Test
    fun `ClaudeCliOutputParser parses high usage percentages`() {
        val output = """
            Current session    ████████████████████████████████████████████████  96% used
            Resets 6pm (Europe/Belgrade)
            
            Current week (all models)
            ███████                                           14% used
            Resets Mar 13 at 3pm (Europe/Belgrade)
        """.trimIndent()

        val result = ClaudeCliOutputParser.parseUsageOutput(output)

        assertEquals(96, result.sessionUsedPercent)
        assertEquals(14, result.weekUsedPercent)
    }

    @Test
    fun `ClaudeCliOutputParser strips ANSI codes`() {
        val withAnsi = "\u001B[32mCurrent session\u001B[0m 50% used"
        val clean = ClaudeCliOutputParser.stripAnsiCodes(withAnsi)
        assertEquals("Current session 50% used", clean)
    }

    @Test
    fun `ClaudeCliOutputParser detects usage dialog content`() {
        val withContent = "Some text Current session more text"
        val withoutContent = "Some random text without keywords"

        assertTrue(ClaudeCliOutputParser.hasUsageDialogContent(withContent))
        assertTrue(!ClaudeCliOutputParser.hasUsageDialogContent(withoutContent))
    }

    @Test
    fun `ClaudeCliOutputParser detects error states`() {
        val authError = "You are not authenticated. Please log in."
        val rateLimitError = "Rate limit exceeded. Too many requests."
        val normalOutput = "Current session 50% used"

        assertNotNull(ClaudeCliOutputParser.detectError(authError))
        assertNotNull(ClaudeCliOutputParser.detectError(rateLimitError))
        assertNull(ClaudeCliOutputParser.detectError(normalOutput))
    }

    @Test
    fun `ClaudeCliOutputParser handles various session percentage formats`() {
        // Format 1: "Current session ... XX% used"
        val format1 = "Current session ████████████ 75% used"
        assertEquals(75, ClaudeCliOutputParser.parseUsagePercent(format1, "session"))

        // Format 2: Just percentage on its own line
        val format2 = "Current session\n                                     75% used"
        assertEquals(75, ClaudeCliOutputParser.parseUsagePercent(format2, "session"))
    }

    @Test
    fun `ClaudeCliOutputParser returns null for missing data`() {
        val noUsageData = "Some text without any usage information"
        val result = ClaudeCliOutputParser.parseUsageOutput(noUsageData)

        assertNull(result.sessionUsedPercent)
        assertNull(result.weekUsedPercent)
    }

    @Test
    fun `ClaudeCliExecutor detects OS correctly`() {
        val osType = ClaudeCliExecutor.getOsType()
        val osName = System.getProperty("os.name").lowercase()

        when {
            osName.contains("mac") -> assertEquals(ClaudeCliExecutor.OsType.MACOS, osType)
            osName.contains("windows") -> assertEquals(ClaudeCliExecutor.OsType.WINDOWS, osType)
            osName.contains("linux") -> assertEquals(ClaudeCliExecutor.OsType.LINUX, osType)
        }
    }

    @Test
    fun `ClaudeCliOutputParser parses reset times`() {
        val output = """
            Current session
                                                                 25% used
            Resets 11pm (Europe/Belgrade)
            
            Current week (all models)
            ██████████████████████████                         52% used
            Resets Mar 6 at 3pm (Europe/Belgrade)
        """.trimIndent()

        val result = ClaudeCliOutputParser.parseUsageOutput(output)

        assertEquals("11pm (Europe/Belgrade)", result.sessionResetsAt)
        assertEquals("Mar 6 at 3pm (Europe/Belgrade)", result.weekResetsAt)
    }

    @Test
    fun `ClaudeCliOutputParser parses status bar format`() {
        val output = "used 75% of your session limit"
        assertEquals(75, ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `ClaudeCliOutputParser parses X percent session used format`() {
        val output = "85% session used today"
        assertEquals(85, ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `ClaudeCliOutputParser parses week variants`() {
        val output = """
            Current week (all models)
            ███████████████████                               38% used
        """.trimIndent()

        assertEquals(38, ClaudeCliOutputParser.parseUsagePercent(output, "week"))
    }

    @Test
    fun `ClaudeCliOutputParser detectError returns correct messages`() {
        assertEquals(
            "Claude CLI not authenticated. Please run 'claude' manually and log in.",
            ClaudeCliOutputParser.detectError("You are not authenticated. Please log in.")
        )
        assertEquals(
            "Rate limit reached. Please wait before retrying.",
            ClaudeCliOutputParser.detectError("Rate limit exceeded")
        )
        assertEquals(
            "Network error. Please check your internet connection.",
            ClaudeCliOutputParser.detectError("Connection refused to server")
        )
    }

    @Test
    fun `ClaudeCliOutputParser hasUsageDialogContent detects variants`() {
        assertTrue(ClaudeCliOutputParser.hasUsageDialogContent("Currentsession 50%"))
        assertTrue(ClaudeCliOutputParser.hasUsageDialogContent("Currentweek 30%"))
        assertTrue(ClaudeCliOutputParser.hasUsageDialogContent("75% used today"))
    }

    @Test
    fun `ClaudeCliOutputParser handles spaces-stripped output`() {
        // TUI sometimes strips spaces when rendering
        val output = "Currentsession██████████75%used"
        assertTrue(ClaudeCliOutputParser.hasUsageDialogContent(output))
    }

    @Test
    fun `ClaudeCliOutputParser parseResetTime returns null for missing section`() {
        val output = "Some random text without reset information"
        assertNull(ClaudeCliOutputParser.parseResetTime(output, "session"))
    }

    @Test
    fun `ClaudeCliOutputParser parseResetTime handles truncated time`() {
        val output = """
            Current session 50% used
            Resets 6pm (Europe/Be...
        """.trimIndent()

        val resetTime = ClaudeCliOutputParser.parseResetTime(output, "session")
        assertEquals("6pm (Europe/Be", resetTime)
    }

    @Test
    fun `ClaudeCliOutputParser handles complex ANSI sequences`() {
        val withComplexAnsi = "\u001B[38;5;196mCurrent session\u001B[0m \u001B[1m50%\u001B[0m used"
        val clean = ClaudeCliOutputParser.stripAnsiCodes(withComplexAnsi)
        assertEquals("Current session 50% used", clean)
    }

    @Test
    fun `ClaudeCliOutputParser parses 0 percent usage`() {
        val output = """
            Current session
                                                                 0% used
        """.trimIndent()

        assertEquals(0, ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }

    @Test
    fun `ClaudeCliOutputParser parses 100 percent usage`() {
        val output = """
            Current session
            ██████████████████████████████████████████████████  100% used
        """.trimIndent()

        assertEquals(100, ClaudeCliOutputParser.parseUsagePercent(output, "session"))
    }
}
