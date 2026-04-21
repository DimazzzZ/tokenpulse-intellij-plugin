package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

/**
 * Parses output from Codex CLI /status command to extract usage data.
 *
 * Handles output from `codex exec /status` which shows:
 * - 5-hour usage percentage and reset time
 * - Weekly usage percentage and reset time
 *
 * Example output:
 * ```
 * 5-hour usage: 45% used
 * Resets at 6:00 PM
 *
 * Weekly usage: 12% used
 * Resets at Mar 13 at 3:00 PM
 * ```
 *
 * Also handles error states:
 * - token_expired
 * - refresh_token_reused
 * - not_authenticated
 */
object CodexCliOutputParser {

    private val ANSI_PATTERN = Regex("\\x1B(?:[@-Z\\-_]|\\[[0-?]*[ -/]*[@-~])")

    /**
     * Strips ANSI escape codes from terminal output.
     */
    fun stripAnsiCodes(input: String): String = ANSI_PATTERN.replace(input, "")

    /**
     * Result of parsing Codex CLI status output.
     */
    sealed class ParseResult {
        data class Success(val statusData: StatusData) : ParseResult()
        data class Error(val errorCode: String, val message: String) : ParseResult()
        data class NoData(val reason: String) : ParseResult()
    }

    /**
     * Parsed status data from Codex CLI.
     */
    data class StatusData(
        val fiveHourUsedPercent: Int? = null,
        val fiveHourResetsAt: String? = null,
        val weeklyUsedPercent: Int? = null,
        val weeklyResetsAt: String? = null
    )

    /**
     * Parse status data from Codex CLI output.
     *
     * @param rawOutput The raw output from `codex exec /status`.
     * @return ParseResult with status data or error.
     */
    fun parseStatusOutput(rawOutput: String): ParseResult {
        val cleanOutput = stripAnsiCodes(rawOutput)

        // First check for errors
        val error = detectError(cleanOutput)
        if (error != null) {
            return error
        }

        // Check for auth issues
        val authError = detectAuthIssue(cleanOutput)
        if (authError != null) {
            return authError
        }

        // Try to parse usage data
        val fiveHourUsed = parseUsagePercent(cleanOutput, "5-hour")
            ?: parseUsagePercent(cleanOutput, "5h")
            ?: parseUsagePercent(cleanOutput, "session")
        val weeklyUsed = parseUsagePercent(cleanOutput, "weekly")
            ?: parseUsagePercent(cleanOutput, "week")

        val fiveHourReset = parseResetTime(cleanOutput, "5-hour")
            ?: parseResetTime(cleanOutput, "5h")
            ?: parseResetTime(cleanOutput, "session")
        val weeklyReset = parseResetTime(cleanOutput, "weekly")
            ?: parseResetTime(cleanOutput, "week")

        if (fiveHourUsed == null && weeklyUsed == null) {
            return ParseResult.NoData("No usage data found in output. Preview: ${cleanOutput.take(200)}")
        }

        return ParseResult.Success(
            StatusData(
                fiveHourUsedPercent = fiveHourUsed,
                fiveHourResetsAt = fiveHourReset,
                weeklyUsedPercent = weeklyUsed,
                weeklyResetsAt = weeklyReset
            )
        )
    }

    /**
     * Parse usage percentage for a given period.
     *
     * Looks for patterns like:
     * - "5-hour usage: XX% used"
     * - "Weekly usage: XX% used"
     * - "session: XX%"
     * - "XX% of 5-hour limit used"
     */
    fun parseUsagePercent(output: String, period: String): Int? {
        // Map period aliases: session -> 5-hour patterns, week -> weekly patterns
        val periodPattern = when {
            period == "5-hour" || period == "5h" || period == "session" ->
                "(5-hour|5h|5\\s*hour|session)"
            else ->
                "(weekly|week)"
        }

        // Pattern 1: "X usage: XX% used"
        val pattern1 = Regex(
            "$periodPattern\\s*usage:\\s*(\\d+)%\\s*used",
            setOf(RegexOption.IGNORE_CASE)
        )
        pattern1.find(output)?.groupValues?.lastOrNull()?.toIntOrNull()?.let { return it }

        // Pattern 2: "X: XX%"
        val pattern2 = Regex(
            "$periodPattern:\\s*(\\d+)%",
            setOf(RegexOption.IGNORE_CASE)
        )
        pattern2.find(output)?.groupValues?.lastOrNull()?.toIntOrNull()?.let { return it }

        // Pattern 3: "used XX% of X limit"
        val pattern3 = Regex(
            "used\\s*(\\d+)%.*?$periodPattern",
            setOf(RegexOption.IGNORE_CASE)
        )
        pattern3.find(output)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // Pattern 4: "XX% X used"
        val pattern4 = Regex(
            "(\\d+)%\\s*$periodPattern\\s*used",
            setOf(RegexOption.IGNORE_CASE)
        )
        pattern4.find(output)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // Pattern 5: Generic - period followed by percentage
        val pattern5 = Regex(
            "$periodPattern.*?(\\d+)%",
            setOf(RegexOption.IGNORE_CASE)
        )
        return pattern5.find(output)?.groupValues?.lastOrNull()?.toIntOrNull()
    }

    /**
     * Parse reset time for a given period.
     *
     * Looks for patterns like:
     * - "Resets at 6:00 PM"
     * - "Resets Mar 13 at 3:00 PM"
     * - "resets at..."
     */
    fun parseResetTime(output: String, period: String): String? {
        val periodPattern = if (period == "5-hour" || period == "5h") {
            "(5-hour|5h|5\\s*hour|session)"
        } else {
            "(weekly|week)"
        }

        // Find the section for the given period
        val sectionPattern = Regex(
            "$periodPattern.*?(?=$periodPattern|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val periodSection = sectionPattern.find(output)?.value ?: return null

        // Look for reset time in this section
        val resetPattern = Regex(
            "Resets?\\s*(?:at\\s*)?([^\\n]+)",
            setOf(RegexOption.IGNORE_CASE)
        )
        val rawTime = resetPattern.find(periodSection)?.groupValues?.get(1)?.trim() ?: return null

        // Clean up the time string
        return rawTime
            .replace("…", "")
            .replace("...", "")
            .trim()
            .ifBlank { null }
    }

    /**
     * Detect known error states in output.
     *
     * @return ParseResult.Error if error detected, null otherwise.
     */
    fun detectError(output: String): ParseResult.Error? {
        // token_expired
        if (output.contains("token_expired", ignoreCase = true) ||
            output.contains("token is expired", ignoreCase = true) ||
            output.contains("authentication token is expired", ignoreCase = true)
        ) {
            return ParseResult.Error("token_expired", "Codex session expired. Please run 'codex login' to re-authenticate.")
        }

        // refresh_token_reused
        if (output.contains("refresh_token_reused", ignoreCase = true) ||
            output.contains("refresh token has already been used", ignoreCase = true) ||
            output.contains("refresh token was already used", ignoreCase = true)
        ) {
            return ParseResult.Error("refresh_token_reused", "Codex refresh token already used. Please run 'codex login' to re-authenticate.")
        }

        // Rate limit / transient errors
        if (output.contains("rate limit", ignoreCase = true) ||
            output.contains("too many requests", ignoreCase = true)
        ) {
            return ParseResult.Error("rate_limited", "Codex rate limit reached. Please wait before retrying.")
        }

        return null
    }

    /**
     * Detect authentication issues that aren't explicit errors.
     *
     * @return ParseResult.Error if auth issue detected, null otherwise.
     */
    fun detectAuthIssue(output: String): ParseResult.Error? {
        if (output.contains("not authenticated", ignoreCase = true) ||
            output.contains("please log in", ignoreCase = true) ||
            output.contains("please sign in", ignoreCase = true) ||
            output.contains("log out and sign in again", ignoreCase = true)
        ) {
            return ParseResult.Error("not_authenticated", "Codex not authenticated. Please run 'codex login' in terminal.")
        }

        if (output.contains("401", ignoreCase = true) &&
            output.contains("unauthorized", ignoreCase = true)
        ) {
            return ParseResult.Error("unauthorized", "Codex authentication failed (401). Please run 'codex login' to re-authenticate.")
        }

        return null
    }

    /**
     * Check if output contains status dialog content.
     */
    fun hasStatusDialogContent(output: String): Boolean {
        val cleanOutput = stripAnsiCodes(output)
        val statusIndicators = listOf(
            "5-hour",
            "5h usage",
            "weekly usage",
            "week usage",
            "usage:",
            "Resets",
            "workdir:",
            "model:",
            "provider:"
        )
        return statusIndicators.any { cleanOutput.contains(it, ignoreCase = true) }
    }
}
