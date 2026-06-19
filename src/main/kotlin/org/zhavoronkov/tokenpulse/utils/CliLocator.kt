package org.zhavoronkov.tokenpulse.utils

import java.io.File

/**
 * Utility for locating CLI binaries on the system.
 * Shared between CodexCliExecutor and ClaudeCliExecutor.
 */
object CliLocator {

    /**
     * Find absolute path to a CLI binary by name.
     *
     * @param binaryName The name of the binary (e.g., "codex", "claude")
     * @param knownLocations Absolute paths to check first
     * @return The absolute path to the binary, or null if not found
     */
    fun findBinary(binaryName: String, knownLocations: List<String> = emptyList()): String? {
        for (location in knownLocations) {
            if (File(location).exists() && File(location).canExecute()) {
                return location
            }
        }

        return try {
            val process = ProcessBuilder("which", binaryName)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotBlank()) output else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Verify a CLI binary works and return its version.
     *
     * @param binaryPath Absolute path to the binary
     * @param versionArg The argument to get version (default: "--version")
     * @return Pair of (success, version string or null)
     */
    fun verifyBinaryWorks(binaryPath: String, versionArg: String = "--version"): Pair<Boolean, String?> {
        return try {
            val process = ProcessBuilder(binaryPath, versionArg)
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
            TokenPulseLogger.Provider.debug("CLI verification failed for $binaryPath: ${e.message}")
            false to null
        }
    }
}
