package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Extracts usage data from Claude CLI by running it via expect script.
 *
 * Uses Unix `expect` for terminal automation on macOS/Linux.
 * Falls back to alternative methods on Windows.
 *
 * The interaction sequence:
 * 1. Start Claude CLI in TUI mode via expect
 * 2. Wait for initial TUI to load
 * 3. Handle safety check if needed (press Enter)
 * 4. Send /usage command
 * 5. Wait for usage dialog to render
 * 6. Capture output
 * 7. Send Escape and /quit to close
 * 8. Parse output for usage percentages
 */
class ClaudeCliUsageExtractor {

    /**
     * Result of usage extraction.
     */
    sealed class ExtractionResult {
        data class Success(val usageData: UsageData) : ExtractionResult()
        data class Error(val message: String, val details: String? = null) : ExtractionResult()
    }

    /**
     * Parsed usage data from Claude CLI.
     */
    data class UsageData(
        val sessionUsedPercent: Int? = null,
        val sessionResetsAt: String? = null,
        val weekUsedPercent: Int? = null,
        val weekResetsAt: String? = null
    )

    /**
     * Check if Claude CLI is available on this system.
     */
    fun isClaudeCliAvailable(): Boolean = ClaudeCliExecutor.isClaudeCliAvailable()

    /**
     * Get Claude CLI version for display.
     */
    fun getClaudeCliVersion(): String? {
        val (success, version) = ClaudeCliExecutor.verifyClaudeCliWorks()
        return if (success) version else null
    }

    /**
     * Extract usage data from Claude CLI.
     *
     * @param timeoutSeconds Maximum time to wait for the operation.
     * @return Extraction result with usage data or error.
     */
    fun extractUsage(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): ExtractionResult {
        if (!ClaudeCliExecutor.isClaudeCliAvailable()) {
            return ExtractionResult.Error(
                "Claude CLI not found",
                "Install via: npm install -g @anthropic-ai/claude-code"
            )
        }

        TokenPulseLogger.Provider.debug("Starting Claude CLI usage extraction via expect script")

        return when (ClaudeCliExecutor.getOsType()) {
            ClaudeCliExecutor.OsType.MACOS,
            ClaudeCliExecutor.OsType.LINUX -> runWithExpect(timeoutSeconds)
            ClaudeCliExecutor.OsType.WINDOWS -> runWithWindowsApproach(timeoutSeconds)
            ClaudeCliExecutor.OsType.UNKNOWN -> ExtractionResult.Error(
                "Unsupported operating system",
                "Claude CLI extraction is only supported on macOS, Linux, and Windows"
            )
        }
    }

    private fun runWithExpect(timeoutSeconds: Long): ExtractionResult {
        // Get absolute path to expect - critical for running from IDE
        val expectPath = findExpectPath()
            ?: return ExtractionResult.Error(
                "expect not found",
                "Install expect: brew install expect (macOS) or apt install expect (Linux)"
            )

        TokenPulseLogger.Provider.debug("Using expect at: $expectPath")

        // Get the absolute path to claude CLI - critical for running from IDE
        val claudePath = ClaudeCliExecutor.findClaudeCliPath()
            ?: return ExtractionResult.Error(
                "Claude CLI not found",
                "Install via: npm install -g @anthropic-ai/claude-code"
            )

        TokenPulseLogger.Provider.debug("Using Claude CLI at: $claudePath")

        val expectScriptFile = File.createTempFile("claude_usage", ".exp")
        val outputFile = File.createTempFile("claude_output", ".log")

        try {
            expectScriptFile.deleteOnExit()
            outputFile.deleteOnExit()

            val expectScript = buildExpectScript(outputFile, claudePath, expectPath)
            expectScriptFile.writeText(expectScript)
            expectScriptFile.setExecutable(true)

            TokenPulseLogger.Provider.debug("Running expect script: ${expectScriptFile.absolutePath}")
            TokenPulseLogger.Provider.debug("Output file: ${outputFile.absolutePath}")

            val workingDir = ClaudeCliExecutor.getWorkingDirectory()
            val process = ProcessBuilder(expectScriptFile.absolutePath)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                return ExtractionResult.Error(
                    "Operation timed out",
                    "Claude CLI did not respond within $timeoutSeconds seconds"
                )
            }

            val exitCode = process.exitValue()
            TokenPulseLogger.Provider.debug("Expect script exit code: $exitCode")

            val output = if (outputFile.exists()) outputFile.readText() else ""
            TokenPulseLogger.Provider.debug("Output length: ${output.length} chars")

            if (output.isEmpty()) {
                return ExtractionResult.Error(
                    "No output captured",
                    "The expect script did not capture any output from Claude CLI"
                )
            }

            return parseOutput(output)
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Expect-based extraction failed", e)
            return ExtractionResult.Error("Extraction failed", e.message)
        } finally {
            cleanupTempFiles(expectScriptFile, outputFile)
        }
    }

    private fun buildExpectScript(outputFile: File, claudePath: String, expectPath: String): String = """
        #!$expectPath -f
        # Robust script for IDE environment with increased timeouts
        set timeout 15
        log_user 1
        log_file -a ${outputFile.absolutePath}
        
        # Spawn claude using absolute path (required when running from IDE)
        spawn -noecho $claudePath
        
        # Wait for TUI prompt, handle safety check if needed (longer timeout for cold start)
        expect {
            "Enter to confirm" { send "\r"; exp_continue }
            "❯" { }
            ">" { }
            -re "\\?" { }
            timeout { }
        }
        
        # Give the TUI a moment to be fully ready
        after 500
        
        # Type /usage command
        send "/usage"
        
        # Wait for autocomplete menu to appear
        expect {
            "Show plan usage limits" { }
            "/usage" { after 200 }
            timeout { }
        }
        
        # Press Enter to select/execute from autocomplete
        send "\r"
        
        # Wait for usage dialog to fully render - look for the percentage display
        expect {
            "% used" { 
                # Wait for both values to render
                after 800
            }
            "Current session" { after 1000 }
            "Resets" { after 600 }
            timeout { }
        }
        
        # Close dialog with Escape
        send "\x1b"
        after 200
        
        # Quit
        send "/quit\r"
        
        # Wait for close with short timeout
        expect {
            eof { }
            timeout { }
        }
        exit 0
    """.trimIndent()

    private fun parseOutput(output: String): ExtractionResult {
        val usageData = ClaudeCliOutputParser.parseUsageOutput(output)

        val errorMsg = ClaudeCliOutputParser.detectError(output)
        if (errorMsg != null) {
            return ExtractionResult.Error(errorMsg)
        }

        if (usageData.sessionUsedPercent == null && usageData.weekUsedPercent == null) {
            val cleanOutput = ClaudeCliOutputParser.stripAnsiCodes(output)
            TokenPulseLogger.Provider.debug("Parsed no usage data. Clean output preview: ${cleanOutput.take(500)}")

            return if (!ClaudeCliOutputParser.hasUsageDialogContent(output)) {
                ExtractionResult.Error(
                    "Could not capture usage data",
                    "The /usage dialog may not have rendered properly. Raw output length: ${output.length}. " +
                        "Preview: ${cleanOutput.take(200).replace("\n", " ")}"
                )
            } else {
                ExtractionResult.Error(
                    "Failed to parse usage data",
                    "Usage dialog was displayed but data extraction failed. " +
                        "Preview: ${cleanOutput.take(200).replace("\n", " ")}"
                )
            }
        }

        return ExtractionResult.Success(usageData)
    }

    /**
     * Find the absolute path to expect binary.
     * Required when running from IDE where PATH may not include /usr/bin.
     */
    private fun findExpectPath(): String? {
        // Common expect locations
        val locations = listOf(
            "/usr/bin/expect",
            "/usr/local/bin/expect",
            "/opt/homebrew/bin/expect"
        )

        for (location in locations) {
            if (File(location).exists() && File(location).canExecute()) {
                return location
            }
        }

        // Try which command as fallback
        return try {
            val process = ProcessBuilder("which", "expect")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotBlank()) output else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Windows implementation using PowerShell for TUI automation.
     * Uses a PowerShell script to interact with Claude CLI.
     */
    private fun runWithWindowsApproach(timeoutSeconds: Long): ExtractionResult {
        val claudePath = ClaudeCliExecutor.findClaudeCliPath()
            ?: return ExtractionResult.Error(
                "Claude CLI not found",
                "Install via: npm install -g @anthropic-ai/claude-code"
            )

        TokenPulseLogger.Provider.debug("Using Claude CLI at: $claudePath")

        val psScriptFile = File.createTempFile("claude_usage", ".ps1")
        val outputFile = File.createTempFile("claude_output", ".log")

        try {
            psScriptFile.deleteOnExit()
            outputFile.deleteOnExit()

            val psScript = buildPowerShellScript(outputFile, claudePath)
            psScriptFile.writeText(psScript)

            TokenPulseLogger.Provider.debug("Running PowerShell script: ${psScriptFile.absolutePath}")

            val workingDir = ClaudeCliExecutor.getWorkingDirectory()
            val process = ProcessBuilder(
                "powershell.exe",
                "-ExecutionPolicy",
                "Bypass",
                "-NoProfile",
                "-File",
                psScriptFile.absolutePath
            )
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                return ExtractionResult.Error(
                    "Operation timed out",
                    "Claude CLI did not respond within $timeoutSeconds seconds"
                )
            }

            val exitCode = process.exitValue()
            TokenPulseLogger.Provider.debug("PowerShell script exit code: $exitCode")

            val output = if (outputFile.exists()) outputFile.readText() else ""
            TokenPulseLogger.Provider.debug("Output length: ${output.length} chars")

            if (output.isEmpty()) {
                return ExtractionResult.Error(
                    "No output captured",
                    "The PowerShell script did not capture any output from Claude CLI"
                )
            }

            return parseOutput(output)
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("PowerShell-based extraction failed", e)
            return ExtractionResult.Error("Extraction failed", e.message)
        } finally {
            cleanupTempFiles(psScriptFile, outputFile)
        }
    }

    /**
     * Build a PowerShell script for Windows to interact with Claude CLI TUI.
     */
    private fun buildPowerShellScript(outputFile: File, claudePath: String): String {
        val outputPath = outputFile.absolutePath.replace("\\", "\\\\")
        val claudePathEscaped = claudePath.replace("\\", "\\\\")

        // Build the script with explicit string concatenation to avoid Kotlin interpolation issues
        return buildString {
            appendLine("# Claude CLI Usage Extraction Script for Windows")
            appendLine("\$ErrorActionPreference = \"SilentlyContinue\"")
            appendLine()
            appendLine("# Start Claude CLI process")
            appendLine("\$psi = New-Object System.Diagnostics.ProcessStartInfo")
            appendLine("\$psi.FileName = \"$claudePathEscaped\"")
            appendLine("\$psi.UseShellExecute = \$false")
            appendLine("\$psi.RedirectStandardOutput = \$true")
            appendLine("\$psi.RedirectStandardError = \$true")
            appendLine("\$psi.RedirectStandardInput = \$true")
            appendLine("\$psi.CreateNoWindow = \$false")
            appendLine()
            appendLine("\$process = New-Object System.Diagnostics.Process")
            appendLine("\$process.StartInfo = \$psi")
            appendLine("\$process.Start() | Out-Null")
            appendLine()
            appendLine("# Wait for TUI to initialize")
            appendLine("Start-Sleep -Seconds 2")
            appendLine()
            appendLine("# Send /usage command")
            appendLine("\$process.StandardInput.WriteLine(\"/usage\")")
            appendLine()
            appendLine("# Wait for usage dialog")
            appendLine("Start-Sleep -Seconds 3")
            appendLine()
            appendLine("# Capture output")
            appendLine("\$output = \"\"")
            appendLine("while (-not \$process.StandardOutput.EndOfStream) {")
            appendLine("    \$line = \$process.StandardOutput.ReadLine()")
            appendLine("    \$output += \$line + [char]10")
            appendLine("}")
            appendLine()
            appendLine("# Send Escape and quit")
            appendLine("\$process.StandardInput.WriteLine([char]27)")
            appendLine("Start-Sleep -Milliseconds 200")
            appendLine("\$process.StandardInput.WriteLine(\"/quit\")")
            appendLine()
            appendLine("# Wait for exit")
            appendLine("\$process.WaitForExit(5000)")
            appendLine()
            appendLine("# Save output")
            appendLine("\$output | Out-File -FilePath \"$outputPath\" -Encoding UTF8")
            appendLine()
            appendLine("# Force kill if still running")
            appendLine("if (-not \$process.HasExited) {")
            appendLine("    \$process.Kill()")
            appendLine("}")
        }
    }

    private fun cleanupTempFiles(vararg files: File) {
        files.forEach { file ->
            try {
                file.delete()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    companion object {
        /** Process timeout - allows for slow Claude CLI cold starts in IDE environment */
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
    }
}
