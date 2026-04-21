package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.File

/**
 * Utility functions for Codex CLI availability and execution.
 *
 * Mirrors ClaudeCliExecutor to provide:
 * - CLI availability checks
 * - Version detection
 */
object CodexCliExecutor {

    /**
     * Check if Codex CLI is available on PATH.
     */
    fun isCodexCliAvailable(): Boolean {
        return findCodexCliPath() != null
    }

    /**
     * Get Codex CLI version, or null if not available.
     */
    fun getCodexCliVersion(): String? {
        val (success, version) = verifyCodexCliWorks()
        return if (success) version else null
    }

    /**
     * Verify Codex CLI works and return version string.
     */
    fun verifyCodexCliWorks(): Pair<Boolean, String?> {
        val codexPath = findCodexCliPath() ?: return false to null
        return try {
            val process = ProcessBuilder(codexPath, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                true to output
            } else {
                false to null
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.debug("Codex CLI verification failed: ${e.message}")
            false to null
        }
    }

    /**
     * Find absolute path to codex binary.
     */
    fun findCodexCliPath(): String? {
        val locations = listOf(
            "/usr/local/bin/codex",
            "/opt/homebrew/bin/codex",
            "/usr/bin/codex"
        )

        for (location in locations) {
            if (File(location).exists() && File(location).canExecute()) {
                return location
            }
        }

        return try {
            val process = ProcessBuilder("which", "codex")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotBlank()) output else null
        } catch (_: Exception) {
            null
        }
    }

}
