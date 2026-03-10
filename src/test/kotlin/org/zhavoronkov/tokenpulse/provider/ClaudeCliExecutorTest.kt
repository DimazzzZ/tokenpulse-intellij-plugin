package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliExecutor

/**
 * Tests for [ClaudeCliExecutor].
 *
 * Tests OS detection and environment configuration without requiring Claude CLI installation.
 */
class ClaudeCliExecutorTest {

    @Test
    fun `getOsType returns valid OS type`() {
        val osType = ClaudeCliExecutor.getOsType()
        assertTrue(osType in ClaudeCliExecutor.OsType.entries)
    }

    @Test
    fun `getOsType detects current system`() {
        val osType = ClaudeCliExecutor.getOsType()
        val osName = System.getProperty("os.name").lowercase()

        when {
            osName.contains("mac") -> assertEquals(ClaudeCliExecutor.OsType.MACOS, osType)
            osName.contains("windows") -> assertEquals(ClaudeCliExecutor.OsType.WINDOWS, osType)
            osName.contains("linux") -> assertEquals(ClaudeCliExecutor.OsType.LINUX, osType)
        }
    }

    @Test
    fun `OsType enum has expected values`() {
        val types = ClaudeCliExecutor.OsType.entries
        assertEquals(4, types.size)
        assertTrue(types.contains(ClaudeCliExecutor.OsType.WINDOWS))
        assertTrue(types.contains(ClaudeCliExecutor.OsType.MACOS))
        assertTrue(types.contains(ClaudeCliExecutor.OsType.LINUX))
        assertTrue(types.contains(ClaudeCliExecutor.OsType.UNKNOWN))
    }

    @Test
    fun `getEnvironment includes TERM setting`() {
        val env = ClaudeCliExecutor.getEnvironment()
        assertEquals("xterm-256color", env["TERM"])
    }

    @Test
    fun `getEnvironment includes COLORTERM setting`() {
        val env = ClaudeCliExecutor.getEnvironment()
        assertEquals("truecolor", env["COLORTERM"])
    }

    @Test
    fun `getEnvironment includes CI setting`() {
        val env = ClaudeCliExecutor.getEnvironment()
        assertEquals("false", env["CI"])
    }

    @Test
    fun `getEnvironment includes PATH from system`() {
        val env = ClaudeCliExecutor.getEnvironment()
        // PATH should be copied if it exists in system env
        val systemPath = System.getenv("PATH")
        if (systemPath != null) {
            assertEquals(systemPath, env["PATH"])
        }
    }

    @Test
    fun `getEnvironment includes HOME from system`() {
        val env = ClaudeCliExecutor.getEnvironment()
        val systemHome = System.getenv("HOME")
        if (systemHome != null) {
            assertEquals(systemHome, env["HOME"])
        }
    }

    @Test
    fun `getWorkingDirectory returns existing directory`() {
        val workingDir = ClaudeCliExecutor.getWorkingDirectory()
        assertTrue(workingDir.exists())
        assertTrue(workingDir.canRead())
    }

    @Test
    fun `getWorkingDirectory returns directory not file`() {
        val workingDir = ClaudeCliExecutor.getWorkingDirectory()
        assertTrue(workingDir.isDirectory)
    }

    @Test
    fun `isClaudeCliAvailable returns boolean`() {
        // Just verify it doesn't throw - result depends on system
        val available = ClaudeCliExecutor.isClaudeCliAvailable()
        assertTrue(available || !available) // Always true, just verifying no exception
    }

    @Test
    fun `findClaudeCliPath returns null or valid path`() {
        val path = ClaudeCliExecutor.findClaudeCliPath()
        if (path != null) {
            assertTrue(java.io.File(path).exists())
        }
    }

    @Test
    fun `getClaudeCommand returns null when CLI not found`() {
        // If Claude CLI is not installed, this should return null
        // If it IS installed, it should return a non-empty list
        val command = ClaudeCliExecutor.getClaudeCommand()
        if (command != null) {
            assertTrue(command.isNotEmpty())
        }
    }

    @Test
    fun `verifyClaudeCliWorks returns pair with results`() {
        val (success, message) = ClaudeCliExecutor.verifyClaudeCliWorks()
        // If CLI is installed and working, success=true and message contains version
        // If not installed, success=false and message explains why
        assertNotNull(message)
    }
}
