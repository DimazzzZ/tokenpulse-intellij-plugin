package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliDetector

/**
 * Tests for [ClaudeCliDetector].
 *
 * Exercises the public surface (install detection + version check) without
 * requiring the Claude CLI to actually be installed.
 */
class ClaudeCliDetectorTest {

    @Test
    fun `isInstalled returns boolean`() {
        // Result depends on the host; the contract is simply that it never throws.
        assertDoesNotThrow { ClaudeCliDetector.isInstalled() }
    }

    @Test
    fun `verifyVersion returns pair with results`() {
        val (_, message) = ClaudeCliDetector.verifyVersion()
        // If the CLI is installed and working, `message` holds the version;
        // if not, it holds the reason. Either way it is non-null.
        assertNotNull(message)
    }
}
