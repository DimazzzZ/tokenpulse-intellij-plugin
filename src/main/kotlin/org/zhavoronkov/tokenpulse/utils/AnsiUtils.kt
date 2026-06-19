package org.zhavoronkov.tokenpulse.utils

/**
 * Utility for stripping ANSI escape codes from terminal output.
 */
object AnsiUtils {

    private val ANSI_PATTERN = Regex("\\x1B(?:[@-Z\\-_]|\\[[0-?]*[ -/]*[@-~])")

    /**
     * Strips ANSI escape codes from terminal output.
     */
    fun stripAnsiCodes(input: String): String = ANSI_PATTERN.replace(input, "")
}
