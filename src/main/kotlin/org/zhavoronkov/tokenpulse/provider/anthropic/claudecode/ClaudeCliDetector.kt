package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import org.zhavoronkov.tokenpulse.utils.CliLocator
import org.zhavoronkov.tokenpulse.utils.HostOs
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import org.zhavoronkov.tokenpulse.utils.detectHostOs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Detects the Claude CLI (`claude`) on the host.
 *
 * Handles:
 * - Locating the `claude` binary
 * - Reporting whether it is installed and functional (`--version`)
 *
 * Claude CLI is typically installed via npm:
 * ```
 * npm install -g @anthropic-ai/claude-code
 * ```
 *
 * Locations by platform:
 * - macOS/Linux: /usr/local/bin/claude, ~/.npm-global/bin/claude, ~/.local/bin/claude
 * - Windows: %APPDATA%\npm\claude.cmd, %PROGRAMFILES%\nodejs\claude.cmd
 */
object ClaudeCliDetector {

    /**
     * Check if Claude CLI is installed and available.
     */
    fun isInstalled(): Boolean = findClaudeCliPath() != null

    /**
     * Find the path to Claude CLI executable.
     *
     * @return Full path to claude executable, or null if not found.
     */
    private fun findClaudeCliPath(): String? {
        val osType = detectHostOs()

        // First try using which/where command
        findClaudeViaCommand(osType)?.let { path ->
            TokenPulseLogger.Provider.debug("Found Claude CLI via command: $path")
            return path
        }

        // Then check common installation paths
        findClaudeInKnownLocations(osType)?.let { path ->
            TokenPulseLogger.Provider.debug("Found Claude CLI in known location: $path")
            return path
        }

        TokenPulseLogger.Provider.debug("Claude CLI not found on system")
        return null
    }

    /**
     * Get the command to invoke Claude CLI.
     *
     * On Windows, we may need to invoke via cmd.exe or use .cmd extension.
     * On Unix-like systems, we can invoke directly.
     */
    private fun getClaudeCommand(): List<String>? {
        val claudePath = findClaudeCliPath() ?: return null

        return when (detectHostOs()) {
            HostOs.WINDOWS -> {
                if (claudePath.endsWith(".cmd") || claudePath.endsWith(".bat")) {
                    listOf("cmd.exe", "/c", claudePath)
                } else {
                    listOf(claudePath)
                }
            }
            else -> listOf(claudePath)
        }
    }

    /**
     * Maximum time to wait for Claude CLI --version to complete.
     */
    private const val VERSION_TIMEOUT_SECONDS = 8L

    /**
     * Verify that the Claude CLI is actually functional by running --version.
     * Uses a bounded timeout to prevent the UI from hanging indefinitely.
     *
     * @return Pair of (success, version string or error message).
     */
    fun verifyVersion(): Pair<Boolean, String?> {
        val command = getClaudeCommand() ?: return false to "Claude CLI not found"

        return try {
            val process = ProcessBuilder(command + "--version")
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                TokenPulseLogger.Provider.debug("Claude CLI version check timed out after ${VERSION_TIMEOUT_SECONDS}s")
                return false to "Claude CLI version check timed out"
            }

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.exitValue()

            if (exitCode == 0 && output.isNotBlank()) {
                TokenPulseLogger.Provider.debug("Claude CLI version: $output")
                true to output
            } else {
                false to "Claude CLI returned exit code $exitCode"
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.debug("Failed to verify Claude CLI: ${e.message}")
            false to e.message
        }
    }

    private fun findClaudeViaCommand(osType: HostOs): String? {
        val locations = getKnownLocations(osType)
        return CliLocator.findBinary("claude", locations)
    }

    private fun getKnownLocations(osType: HostOs): List<String> {
        val homeDir = System.getProperty("user.home")

        return when (osType) {
            HostOs.WINDOWS -> listOf(
                "${System.getenv("APPDATA")}\\npm\\claude.cmd",
                "${System.getenv("APPDATA")}\\npm\\claude",
                "${System.getenv("LOCALAPPDATA")}\\npm\\claude.cmd",
                "${System.getenv("PROGRAMFILES")}\\nodejs\\claude.cmd",
                "$homeDir\\AppData\\Roaming\\npm\\claude.cmd",
                "$homeDir\\AppData\\Local\\npm\\claude.cmd"
            )
            HostOs.MACOS -> listOf(
                "/usr/local/bin/claude",
                "/opt/homebrew/bin/claude",
                "$homeDir/.npm-global/bin/claude",
                "$homeDir/.local/bin/claude",
                "$homeDir/bin/claude",
                "/usr/bin/claude"
            )
            HostOs.LINUX -> listOf(
                "/usr/local/bin/claude",
                "/usr/bin/claude",
                "$homeDir/.npm-global/bin/claude",
                "$homeDir/.local/bin/claude",
                "$homeDir/bin/claude"
            )
            HostOs.UNKNOWN -> emptyList()
        }
    }

    private fun findClaudeInKnownLocations(osType: HostOs): String? {
        val locations = getKnownLocations(osType)

        for (location in locations) {
            val file = File(location)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }

        // Handle NVM glob patterns on Linux
        if (osType == HostOs.LINUX) {
            val homeDir = System.getProperty("user.home")
            findGlobMatch("$homeDir/.nvm/versions/node/*/bin/claude")?.let { return it }
        }

        return null
    }

    private fun findGlobMatch(pattern: String): String? {
        val parts = pattern.split("*")
        if (parts.size != 2) return null

        val prefix = parts[0]
        val suffix = parts[1]

        val parentDir = File(prefix).parentFile ?: return null
        if (!parentDir.exists()) return null

        return parentDir.listFiles()?.firstNotNullOfOrNull { dir ->
            val candidate = File(dir.absolutePath + suffix)
            candidate.absolutePath.takeIf { candidate.exists() && candidate.canExecute() }
        }
    }
}
