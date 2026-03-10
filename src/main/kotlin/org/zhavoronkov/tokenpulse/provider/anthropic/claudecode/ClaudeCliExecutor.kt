package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.File

/**
 * Platform-specific executor for Claude CLI.
 *
 * Handles:
 * - OS detection (Windows, macOS, Linux)
 * - Claude CLI location detection
 * - Claude CLI availability checks
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
object ClaudeCliExecutor {

    /**
     * Operating system type.
     */
    enum class OsType {
        WINDOWS,
        MACOS,
        LINUX,
        UNKNOWN
    }

    /**
     * Get the current operating system type.
     */
    fun getOsType(): OsType {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("windows") -> OsType.WINDOWS
            osName.contains("mac") || osName.contains("darwin") -> OsType.MACOS
            osName.contains("linux") || osName.contains("unix") -> OsType.LINUX
            else -> OsType.UNKNOWN
        }
    }

    /**
     * Check if Claude CLI is installed and available.
     */
    fun isClaudeCliAvailable(): Boolean = findClaudeCliPath() != null

    /**
     * Find the path to Claude CLI executable.
     *
     * @return Full path to claude executable, or null if not found.
     */
    fun findClaudeCliPath(): String? {
        val osType = getOsType()

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
    fun getClaudeCommand(): List<String>? {
        val claudePath = findClaudeCliPath() ?: return null

        return when (getOsType()) {
            OsType.WINDOWS -> {
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
     * Get environment variables for running Claude CLI.
     *
     * Ensures proper terminal emulation.
     */
    fun getEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        // Copy relevant environment variables
        val relevantEnvVars = setOf(
            "PATH", "HOME", "USER", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "PROGRAMFILES", "PROGRAMFILES(X86)", "HOMEDRIVE", "HOMEPATH",
            "XDG_CONFIG_HOME", "XDG_DATA_HOME"
        )

        System.getenv().filterKeys { it in relevantEnvVars }.forEach { (key, value) ->
            env[key] = value
        }

        // Set terminal type for proper TUI rendering
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"

        // Disable any interactive prompts
        env["CI"] = "false"

        return env
    }

    /**
     * Get a working directory that Claude CLI can trust.
     *
     * Claude CLI has workspace trust checking, so we need to use a directory
     * that's either already trusted or the user's home directory.
     */
    fun getWorkingDirectory(): File {
        // Try current working directory first
        val cwd = File(System.getProperty("user.dir"))
        if (cwd.exists() && cwd.canRead()) {
            return cwd
        }

        // Fall back to home directory
        val home = File(System.getProperty("user.home"))
        if (home.exists() && home.canRead()) {
            return home
        }

        // Last resort: temp directory
        return File(System.getProperty("java.io.tmpdir"))
    }

    /**
     * Verify that the Claude CLI is actually functional by running --version.
     *
     * @return Pair of (success, version string or error message).
     */
    fun verifyClaudeCliWorks(): Pair<Boolean, String?> {
        val command = getClaudeCommand() ?: return false to "Claude CLI not found"

        return try {
            val process = ProcessBuilder(command + "--version")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

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

    private fun findClaudeViaCommand(osType: OsType): String? {
        val command = when (osType) {
            OsType.WINDOWS -> listOf("where", "claude")
            else -> listOf("which", "claude")
        }

        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotBlank()) {
                // On Windows, 'where' may return multiple lines; take the first
                val path = output.lines().firstOrNull()?.trim()
                path?.takeIf { File(it).exists() }
            } else {
                null
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.debug("Failed to find claude via command: ${e.message}")
            null
        }
    }

    private fun findClaudeInKnownLocations(osType: OsType): String? {
        val homeDir = System.getProperty("user.home")

        val locations = when (osType) {
            OsType.WINDOWS -> listOf(
                "${System.getenv("APPDATA")}\\npm\\claude.cmd",
                "${System.getenv("APPDATA")}\\npm\\claude",
                "${System.getenv("LOCALAPPDATA")}\\npm\\claude.cmd",
                "${System.getenv("PROGRAMFILES")}\\nodejs\\claude.cmd",
                "$homeDir\\AppData\\Roaming\\npm\\claude.cmd",
                "$homeDir\\AppData\\Local\\npm\\claude.cmd"
            )
            OsType.MACOS -> listOf(
                "/usr/local/bin/claude",
                "/opt/homebrew/bin/claude",
                "$homeDir/.npm-global/bin/claude",
                "$homeDir/.local/bin/claude",
                "$homeDir/bin/claude",
                "/usr/bin/claude"
            )
            OsType.LINUX -> listOf(
                "/usr/local/bin/claude",
                "/usr/bin/claude",
                "$homeDir/.npm-global/bin/claude",
                "$homeDir/.local/bin/claude",
                "$homeDir/bin/claude",
                "$homeDir/.nvm/versions/node/*/bin/claude" // NVM installs
            )
            OsType.UNKNOWN -> emptyList()
        }

        for (location in locations) {
            // Handle glob patterns for NVM
            if (location.contains("*")) {
                findGlobMatch(location)?.let { return it }
            } else {
                val file = File(location)
                if (file.exists() && file.canExecute()) {
                    return file.absolutePath
                }
            }
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
