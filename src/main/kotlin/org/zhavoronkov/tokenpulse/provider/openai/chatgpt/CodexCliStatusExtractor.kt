package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

/**
 * Extracts status data from Codex CLI by running it via exec command.
 *
 * Uses `codex exec --skip-git-repo-check /status` for non-interactive execution.
 * Falls back to expect script on Unix if exec doesn't produce usable output.
 *
 * The interaction sequence:
 * 1. Run codex exec with /status command
 * 2. Capture stdout/stderr output
 * 3. Parse output for usage percentages and reset times
 * 4. Handle auth errors gracefully
 */
class CodexCliStatusExtractor {

    /**
     * Result of status extraction.
     */
    sealed class ExtractionResult {
        data class Success(val statusData: CodexCliOutputParser.StatusData) : ExtractionResult()
        data class Error(val errorCode: String, val message: String, val details: String? = null) : ExtractionResult()
    }

    /**
     * Check if Codex CLI is available on this system.
     */
    fun isCodexCliAvailable(): Boolean = CodexCliExecutor.isCodexCliAvailable()

    /**
     * Get Codex CLI version for display.
     */
    fun getCodexCliVersion(): String? = CodexCliExecutor.getCodexCliVersion()
}
