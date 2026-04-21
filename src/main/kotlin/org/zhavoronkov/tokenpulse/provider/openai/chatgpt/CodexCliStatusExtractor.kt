package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.File
import java.util.concurrent.TimeUnit

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
        data class NoData(val reason: String) : ExtractionResult()
    }

    /**
     * Check if Codex CLI is available on this system.
     */
    fun isCodexCliAvailable(): Boolean = CodexCliExecutor.isCodexCliAvailable()

    /**
     * Get Codex CLI version for display.
     */
    fun getCodexCliVersion(): String? = CodexCliExecutor.getCodexCliVersion()

    /**
     * Extract status data from Codex CLI.
     *
     * @param timeoutSeconds Maximum time to wait for the operation.
     * @return Extraction result with status data or error.
     */
    fun extractStatus(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): ExtractionResult {
        if (!CodexCliExecutor.isCodexCliAvailable()) {
            return ExtractionResult.Error(
                "not_installed",
                "Codex CLI not found",
                "Install via: npm install -g @openai/codex"
            )
        }

        TokenPulseLogger.Provider.debug("Starting Codex CLI status extraction via exec command")

        return when (CodexCliExecutor.getOsType()) {
            CodexCliExecutor.OsType.MACOS,
            CodexCliExecutor.OsType.LINUX -> runWithExecCommand(timeoutSeconds)
            CodexCliExecutor.OsType.WINDOWS -> runWithWindowsApproach(timeoutSeconds)
            CodexCliExecutor.OsType.UNKNOWN -> ExtractionResult.Error(
                "unsupported_os",
                "Unsupported operating system",
                "Codex CLI extraction is only supported on macOS, Linux, and Windows"
            )
        }
    }

    /**
     * Run Codex CLI using exec command (non-interactive).
     */
    private fun runWithExecCommand(timeoutSeconds: Long): ExtractionResult {
        val codexPath = CodexCliExecutor.findCodexCliPath()
            ?: return ExtractionResult.Error(
                "cli_not_found",
                "Codex CLI not found in PATH",
                "Install via: npm install -g @openai/codex"
            )

        TokenPulseLogger.Provider.debug("Using Codex CLI at: $codexPath")

        val outputFile = File.createTempFile("codex_status", ".log")

        try {
            outputFile.deleteOnExit()

            // Build the exec command
            // Using --skip-git-repo-check to avoid git-related delays
            val command = listOf(
                codexPath,
                "exec",
                "--skip-git-repo-check",
                "/status"
            )

            TokenPulseLogger.Provider.debug("Running: ${command.joinToString(" ")}")

            val workingDir = CodexCliExecutor.getWorkingDirectory()
            val env = CodexCliExecutor.getEnvironment()

            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(env)
                }
                .start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                return ExtractionResult.Error(
                    "timeout",
                    "Operation timed out",
                    "Codex CLI did not respond within $timeoutSeconds seconds"
                )
            }

            val exitCode = process.exitValue()
            TokenPulseLogger.Provider.debug("Codex exec exit code: $exitCode")

            val output = process.inputStream.bufferedReader().readText()
            TokenPulseLogger.Provider.debug("Output length: ${output.length} chars")

            // Write to file for debugging
            outputFile.writeText(output)

            return parseOutput(output)
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Exec-based extraction failed", e)
            return ExtractionResult.Error("extraction_failed", "Extraction failed", e.message)
        } finally {
            if (outputFile.exists()) {
                try {
                    outputFile.delete()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Windows implementation using PowerShell.
     */
    private fun runWithWindowsApproach(timeoutSeconds: Long): ExtractionResult {
        val codexPath = CodexCliExecutor.findCodexCliPath()
            ?: return ExtractionResult.Error(
                "cli_not_found",
                "Codex CLI not found",
                "Install via: npm install -g @openai/codex"
            )

        TokenPulseLogger.Provider.debug("Using Codex CLI at: $codexPath")

        val outputFile = File.createTempFile("codex_status", ".log")

        try {
            outputFile.deleteOnExit()

            // Build PowerShell command
            val psCommand = "& '$codexPath' exec --skip-git-repo-check '/status' 2>&1"

            TokenPulseLogger.Provider.debug("Running PowerShell: $psCommand")

            val workingDir = CodexCliExecutor.getWorkingDirectory()

            val process = ProcessBuilder(
                "powershell.exe",
                "-ExecutionPolicy",
                "Bypass",
                "-NoProfile",
                "-Command",
                psCommand
            )
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                return ExtractionResult.Error(
                    "timeout",
                    "Operation timed out",
                    "Codex CLI did not respond within $timeoutSeconds seconds"
                )
            }

            val exitCode = process.exitValue()
            TokenPulseLogger.Provider.debug("PowerShell exit code: $exitCode")

            val output = process.inputStream.bufferedReader().readText()
            TokenPulseLogger.Provider.debug("Output length: ${output.length} chars")

            outputFile.writeText(output)

            return parseOutput(output)
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("PowerShell-based extraction failed", e)
            return ExtractionResult.Error("extraction_failed", "Extraction failed", e.message)
        } finally {
            if (outputFile.exists()) {
                try {
                    outputFile.delete()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Parse the output from Codex CLI.
     */
    private fun parseOutput(output: String): ExtractionResult {
        val parseResult = CodexCliOutputParser.parseStatusOutput(output)

        return when (parseResult) {
            is CodexCliOutputParser.ParseResult.Success -> {
                ExtractionResult.Success(parseResult.statusData)
            }
            is CodexCliOutputParser.ParseResult.Error -> {
                ExtractionResult.Error(parseResult.errorCode, parseResult.message)
            }
            is CodexCliOutputParser.ParseResult.NoData -> {
                // Check if status dialog was displayed but parsing failed
                if (CodexCliOutputParser.hasStatusDialogContent(output)) {
                    ExtractionResult.Error(
                        "parse_failed",
                        "Failed to parse status data",
                        "Status dialog was displayed but data extraction failed. Preview: ${output.take(200)}"
                    )
                } else {
                    ExtractionResult.NoData(parseResult.reason)
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
    }
}
