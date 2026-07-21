package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeAccountDiscovery
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeAccountIdentity
import java.io.File

class ClaudeAccountDiscoveryTest {

    @Test
    fun `candidateDirs finds dot claude and dot claude-star and dot config claude-star`(@TempDir home: File) {
        File(home, ".claude").mkdirs()
        File(home, ".claude-work").mkdirs()
        File(home, ".claude-personal").mkdirs()
        File(home, ".claudex").mkdirs() // decoy (no dash)
        File(home, ".config/claude").mkdirs()
        File(home, ".config/notclaude").mkdirs() // decoy
        File(home, "regular.txt").writeText("x")

        val dirs = ClaudeAccountDiscovery.candidateDirs(home.absolutePath).map { File(it).name }.toSet()
        assertTrue(dirs.contains(".claude"))
        assertTrue(dirs.contains(".claude-work"))
        assertTrue(dirs.contains(".claude-personal"))
        assertTrue(dirs.contains("claude"))
        assertFalse(dirs.contains(".claudex"))
        assertFalse(dirs.contains("notclaude"))
    }

    @Test
    fun `discover normalizes default dir to null configDir and dedups`(@TempDir home: File) {
        File(home, ".claude").mkdirs()
        File(home, ".claude-work").mkdirs()
        val out = ClaudeAccountDiscovery.discover(home.absolutePath, credProbe = { true })
        val defaultRow = out.single { it.isDefault }
        assertNull(defaultRow.configDir)
        val workRow = out.single { !it.isDefault }
        assertEquals(File(home, ".claude-work").absolutePath, workRow.configDir)
        assertEquals(out.size, out.distinctBy { it.configDir }.size)
    }

    @Test
    fun `credProbe result flows into hasValidCreds`(@TempDir home: File) {
        File(home, ".claude").mkdirs()
        val ok = ClaudeAccountDiscovery.discover(home.absolutePath, credProbe = { true }).single()
        assertTrue(ok.hasValidCreds)
        val bad = ClaudeAccountDiscovery.discover(home.absolutePath, credProbe = { false }).single()
        assertFalse(bad.hasValidCreds)
    }

    @Test
    fun `credProbe throwing yields hasValidCreds false and does not propagate`(@TempDir home: File) {
        File(home, ".claude").mkdirs()
        val row = ClaudeAccountDiscovery.discover(
            home.absolutePath,
            credProbe = { throw RuntimeException("nope") },
        ).single()
        assertFalse(row.hasValidCreds)
    }

    @Test
    fun `labelFor prefers email plus org, then email, then displayName, else basename`() {
        val full = ClaudeAccountIdentity(emailAddress = "a@b", organizationName = "Org", displayName = "Al")
        assertEquals("a@b • Org", ClaudeAccountDiscovery.labelFor(full, "/x/.claude"))
        val emailOnly = ClaudeAccountIdentity(emailAddress = "a@b", organizationName = null, displayName = null)
        assertEquals("a@b", ClaudeAccountDiscovery.labelFor(emailOnly, "/x/.claude"))
        val dispOnly = ClaudeAccountIdentity(emailAddress = null, organizationName = null, displayName = "Al")
        assertEquals("Al", ClaudeAccountDiscovery.labelFor(dispOnly, "/x/.claude"))
        assertEquals(".claude-work", ClaudeAccountDiscovery.labelFor(null, "/x/.claude-work"))
        assertEquals(
            ".claude-work",
            ClaudeAccountDiscovery.labelFor(ClaudeAccountIdentity(null, null, null), "/x/.claude-work"),
        )
    }

    @Test
    fun `labelFor drops auto-named personal org that just repeats the email`() {
        val email = "dimaz.lark@gmail.com"
        val straight = ClaudeAccountIdentity(
            emailAddress = email,
            organizationName = "$email's Organization",
            displayName = null,
        )
        assertEquals(email, ClaudeAccountDiscovery.labelFor(straight, "/x/.claude"))

        // Curly-apostrophe variant should also be treated as auto-named.
        val curly = ClaudeAccountIdentity(
            emailAddress = email,
            organizationName = "$email\u2019s Organization",
            displayName = null,
        )
        assertEquals(email, ClaudeAccountDiscovery.labelFor(curly, "/x/.claude"))
    }

    @Test
    fun `labelFor keeps a real custom org name`() {
        val identity = ClaudeAccountIdentity(
            emailAddress = "me@example.com",
            organizationName = "Acme",
            displayName = null,
        )
        assertEquals("me@example.com • Acme", ClaudeAccountDiscovery.labelFor(identity, "/x/.claude"))
    }

    @Test
    fun `probe reads identity from configDir and stores raw dir for non-default`(@TempDir home: File) {
        val workDir = File(home, ".claude-work").apply { mkdirs() }
        File(workDir, ".claude.json").writeText(
            """{"oauthAccount":{"emailAddress":"w@ex.com","organizationName":"Team"}}"""
        )
        val probed = ClaudeAccountDiscovery.probe(workDir.absolutePath, home.absolutePath, credProbe = { true })
        assertNotNull(probed.identity)
        assertEquals("w@ex.com", probed.identity!!.emailAddress)
        assertEquals("w@ex.com • Team", probed.label)
        assertFalse(probed.isDefault)
        assertEquals(workDir.absolutePath, probed.configDir)
    }
}
