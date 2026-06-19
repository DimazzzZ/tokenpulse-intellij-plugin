package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import org.zhavoronkov.tokenpulse.utils.CliLocator

/**
 * Utility functions for Codex CLI availability and execution.
 */
object CodexCliExecutor {

    private val KNOWN_LOCATIONS = listOf(
        "/usr/local/bin/codex",
        "/opt/homebrew/bin/codex",
        "/usr/bin/codex"
    )

    fun isCodexCliAvailable(): Boolean = findCodexCliPath() != null

    fun getCodexCliVersion(): String? {
        val (success, version) = verifyCodexCliWorks()
        return if (success) version else null
    }

    fun verifyCodexCliWorks(): Pair<Boolean, String?> {
        val codexPath = findCodexCliPath() ?: return false to null
        return CliLocator.verifyBinaryWorks(codexPath)
    }

    fun findCodexCliPath(): String? = CliLocator.findBinary("codex", KNOWN_LOCATIONS)
}
