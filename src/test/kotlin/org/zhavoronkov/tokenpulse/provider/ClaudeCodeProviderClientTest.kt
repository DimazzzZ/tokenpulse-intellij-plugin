package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeAccountIdentity
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.enrichedAccountLabel

/**
 * Tests for the Claude Code provider's pure name-enrichment decision.
 * Usage now comes from the OAuth API
 * ([org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeOAuthUsageClient]),
 * so there is no CLI-output parser to unit-test here anymore. OS detection is
 * covered by [org.zhavoronkov.tokenpulse.utils.HostOsTest].
 */
class ClaudeCodeProviderClientTest {

    // ── enrichedAccountLabel (lazy name-enrichment decision) ─────────────

    @Test
    fun `enrichedAccountLabel combines email and org`() {
        val identity = ClaudeAccountIdentity(
            emailAddress = "me@example.com",
            organizationName = "Acme",
            displayName = null
        )
        assertEquals("me@example.com • Acme", enrichedAccountLabel(identity, null))
    }

    @Test
    fun `enrichedAccountLabel uses email when org missing`() {
        val identity = ClaudeAccountIdentity(
            emailAddress = "me@example.com",
            organizationName = null,
            displayName = "Me"
        )
        assertEquals("me@example.com", enrichedAccountLabel(identity, null))
    }

    @Test
    fun `enrichedAccountLabel falls back to displayName`() {
        val identity = ClaudeAccountIdentity(
            emailAddress = null,
            organizationName = null,
            displayName = "Just Me"
        )
        assertEquals("Just Me", enrichedAccountLabel(identity, null))
    }

    @Test
    fun `enrichedAccountLabel is null for null identity`() {
        assertNull(enrichedAccountLabel(null, "/Users/me/.claude-work"))
    }

    @Test
    fun `enrichedAccountLabel is null when identity has no fields`() {
        val identity = ClaudeAccountIdentity(
            emailAddress = null,
            organizationName = null,
            displayName = null
        )
        // hasAny() is false, so we never write the ".claude-work" basename.
        assertNull(enrichedAccountLabel(identity, "/Users/me/.claude-work"))
    }
}
