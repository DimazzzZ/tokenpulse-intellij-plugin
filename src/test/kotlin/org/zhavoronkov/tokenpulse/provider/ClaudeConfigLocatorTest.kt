package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeConfigLocator
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Verifies the config-dir → storage-location rules that must match claude-code
 * byte-for-byte. Purely deterministic; no I/O beyond `java.io.File`.
 */
class ClaudeConfigLocatorTest {

    private val home = "/tmp/tp-home"

    @Test
    fun `default keychain service name has no suffix`() {
        assertEquals("Claude Code-credentials", ClaudeConfigLocator.keychainServiceName(null, home))
        assertEquals("Claude Code-credentials", ClaudeConfigLocator.keychainServiceName("", home))
        assertEquals("Claude Code-credentials", ClaudeConfigLocator.keychainServiceName("$home/.claude", home))
    }

    @Test
    fun `non-default keychain service name is base + sha256 8-hex suffix of NFC(dir)`() {
        val dir = "/tmp/tp-home/.claude-work"
        val expectedHex = MessageDigest.getInstance("SHA-256")
            .digest(Normalizer.normalize(dir, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 8)
        assertEquals("Claude Code-credentials-$expectedHex", ClaudeConfigLocator.keychainServiceName(dir, home))
    }

    @Test
    fun `NFC normalization collapses decomposed vs composed unicode dirs`() {
        // é (U+00E9) vs e + combining acute (U+0065 U+0301) NFC-normalize equal.
        val composed = "/tmp/caf\u00e9"
        val decomposed = "/tmp/cafe\u0301"
        assertEquals(
            ClaudeConfigLocator.keychainServiceName(composed, home),
            ClaudeConfigLocator.keychainServiceName(decomposed, home)
        )
    }

    @Test
    fun `isDefault treats null blank and canonical ~ dot claude as default`() {
        assertTrue(ClaudeConfigLocator.isDefault(null, home))
        assertTrue(ClaudeConfigLocator.isDefault("", home))
        assertTrue(ClaudeConfigLocator.isDefault("$home/.claude", home))
        assertTrue(ClaudeConfigLocator.isDefault("$home/.claude/", home))
        assertFalse(ClaudeConfigLocator.isDefault("$home/.claude-work", home))
        assertFalse(ClaudeConfigLocator.isDefault("/somewhere/else", home))
    }

    @Test
    fun `identityFile is HOME dot claude json for default, else dir dot claude json`() {
        assertEquals("$home/.claude.json", ClaudeConfigLocator.identityFile(null, home).path)
        assertEquals("$home/.claude.json", ClaudeConfigLocator.identityFile("$home/.claude", home).path)
        assertEquals(
            "$home/.claude-work/.claude.json",
            ClaudeConfigLocator.identityFile("$home/.claude-work", home).path,
        )
    }

    @Test
    fun `credentialsFile is dir dot credentials json falling back to ~ dot claude`() {
        assertEquals("$home/.claude/.credentials.json", ClaudeConfigLocator.credentialsFile(null, home).path)
        assertEquals(
            "$home/.claude-work/.credentials.json",
            ClaudeConfigLocator.credentialsFile("$home/.claude-work", home).path,
        )
    }
}
