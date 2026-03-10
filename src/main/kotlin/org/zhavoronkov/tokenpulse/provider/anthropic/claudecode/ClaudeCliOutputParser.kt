package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

/**
 * Parses output from Claude CLI TUI to extract usage data.
 *
 * Handles the output from the `/usage` command which shows:
 * - Current session usage percentage and reset time
 * - Current week (all models) usage percentage and reset time
 *
 * Example TUI output:
 * ```
 * Current session    ████████████████████████████████████████████████  96% used
 * Resets 6pm (Europe/Belgrade)
 *
 * Current week (all models)
 * ███████                                           14% used
 * Resets Mar 13 at 3pm (Europe/Belgrade)
 * ```
 */
object ClaudeCliOutputParser {

    private val ANSI_PATTERN = Regex("\\x1B(?:[@-Z\\-_]|\\[[0-?]*[ -/]*[@-~])")

    /**
     * Strips ANSI escape codes from terminal output.
     */
    fun stripAnsiCodes(input: String): String = ANSI_PATTERN.replace(input, "")

    /**
     * Parse usage data from Claude CLI output.
     *
     * @param rawOutput The raw output from the Claude CLI TUI.
     * @return Parsed usage data with extracted percentages and reset times.
     */
    fun parseUsageOutput(rawOutput: String): ClaudeCliUsageExtractor.UsageData {
        val cleanOutput = stripAnsiCodes(rawOutput)

        return ClaudeCliUsageExtractor.UsageData(
            sessionUsedPercent = parseUsagePercent(cleanOutput, "session"),
            sessionResetsAt = parseResetTime(cleanOutput, "session"),
            weekUsedPercent = parseUsagePercent(cleanOutput, "week"),
            weekResetsAt = parseResetTime(cleanOutput, "week")
        )
    }

    /**
     * Parse usage percentage for a given period (session or week).
     *
     * Looks for patterns like:
     * - "Current session...XX%used" (from /usage dialog, spaces may be stripped)
     * - "used XX% of your session limit" (from status bar)
     * - "XX% session used"
     * - "5-hour: XX% used" or "5h: XX% used" (newer format)
     * - "daily: XX% used" (potential alternative label)
     */
    fun parseUsagePercent(output: String, period: String): Int? {
        // For "session", also match "5-hour", "5h", "5 hour", "daily" variations
        val periodPattern = if (period == "session") {
            "(session|5-hour|5h|5\\s*hour|daily)"
        } else {
            period
        }

        // Pattern 1: "Current session" or "Currentweek" followed by "XX%used"
        val pattern1 = Regex(
            "Current\\s*$periodPattern.*?(\\d+)%\\s*used",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        pattern1.find(output)?.groupValues?.lastOrNull()?.toIntOrNull()?.let { return it }

        // Pattern 2: "5-hour:" or "5h:" or "session:" followed by progress and percentage
        val pattern2a = Regex(
            "$periodPattern\\s*:?.*?(\\d+)%",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        pattern2a.find(output)?.groupValues?.lastOrNull()?.toIntOrNull()?.let { return it }

        // Pattern 3: "used X% of your session/week limit" (status bar format)
        val pattern3 = Regex("used\\s*(\\d+)%.*?$periodPattern", RegexOption.IGNORE_CASE)
        pattern3.find(output)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // Pattern 4: "X% session/week used"
        val pattern4 = Regex("(\\d+)%\\s*$periodPattern\\s*used", RegexOption.IGNORE_CASE)
        pattern4.find(output)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // Pattern 5: Generic - period followed by percentage anywhere
        val pattern5 = Regex("$periodPattern.*?(\\d+)%", RegexOption.IGNORE_CASE)
        return pattern5.find(output)?.groupValues?.lastOrNull()?.toIntOrNull()
    }

    /**
     * Parse reset time for a given period.
     *
     * Looks for patterns like:
     * - "Resets 6pm (Europe/Belgrade)"
     * - "resets Mar 13 at 3pm (Europe/Belgrade)"
     * - "· resets 6pm (Europe/Be…"
     */
    fun parseResetTime(output: String, period: String): String? {
        // Find the section for the given period
        val periodPattern = Regex(
            "Current\\s*$period.*?(?=Current|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val periodSection = periodPattern.find(output)?.value ?: return null

        // Look for reset time in this section
        val resetPattern = Regex("Resets?\\s+([^\\n]+)", RegexOption.IGNORE_CASE)
        val rawTime = resetPattern.find(periodSection)?.groupValues?.get(1)?.trim() ?: return null

        // Clean up the time string
        return rawTime
            .replace("…", "")
            .replace("...", "")
            .trim()
            .ifBlank { null }
    }

    /**
     * Check if the output indicates the usage dialog was displayed.
     *
     * @return true if the output contains usage dialog indicators.
     */
    fun hasUsageDialogContent(output: String): Boolean {
        val cleanOutput = stripAnsiCodes(output)
        val usageIndicators = listOf(
            "Current session",
            "Currentsession",
            "Current week",
            "Currentweek",
            "% used",
            "Loading usage data",
            "Settings:"  // The usage dialog header
        )
        return usageIndicators.any { cleanOutput.contains(it, ignoreCase = true) }
    }

    /**
     * Check if the output indicates an error state.
     *
     * @return Error message if detected, null otherwise.
     */
    fun detectError(output: String): String? {
        val cleanOutput = stripAnsiCodes(output)

        val errorPatterns = mapOf(
            listOf("not authenticated", "Please log in") to
                "Claude CLI not authenticated. Please run 'claude' manually and log in.",
            listOf("rate_limit_error", "rate limit", "too many requests", "Rate limited") to
                "Rate limit reached. Please wait before retrying.",
            listOf("Failed to load usage data") to
                "Failed to load usage data from Claude API. Please try again later.",
            listOf("network error", "connection refused") to
                "Network error. Please check your internet connection."
        )

        return errorPatterns.entries.firstOrNull { (patterns, _) ->
            patterns.any { cleanOutput.contains(it, ignoreCase = true) }
        }?.value
    }
}
