package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeAccountIdentityReader
import java.io.File

class ClaudeAccountIdentityReaderTest {

    @Test
    fun `reads oauthAccount fields from HOME dot claude json for default dir`(@TempDir home: File) {
        File(home, ".claude.json").writeText(
            """{"oauthAccount":{"emailAddress":"me@ex.com","organizationName":"Acme","displayName":"Me"}}"""
        )
        val identity = ClaudeAccountIdentityReader.read(configDir = null, home = home.absolutePath)
        assertNotNull(identity)
        assertEquals("me@ex.com", identity!!.emailAddress)
        assertEquals("Acme", identity.organizationName)
        assertEquals("Me", identity.displayName)
        assertTrue(identity.hasAny())
    }

    @Test
    fun `reads from configDir dot claude json for non-default dir`(@TempDir home: File) {
        val workDir = File(home, ".claude-work").apply { mkdirs() }
        File(workDir, ".claude.json").writeText(
            """{"oauthAccount":{"emailAddress":"work@ex.com"}}"""
        )
        val identity = ClaudeAccountIdentityReader.read(configDir = workDir.absolutePath, home = home.absolutePath)
        assertNotNull(identity)
        assertEquals("work@ex.com", identity!!.emailAddress)
        assertNull(identity.organizationName)
        assertNull(identity.displayName)
    }

    @Test
    fun `missing file yields null`(@TempDir home: File) {
        assertNull(ClaudeAccountIdentityReader.read(configDir = null, home = home.absolutePath))
    }

    @Test
    fun `missing oauthAccount yields null`(@TempDir home: File) {
        File(home, ".claude.json").writeText("""{"other":"stuff"}""")
        assertNull(ClaudeAccountIdentityReader.read(configDir = null, home = home.absolutePath))
    }

    @Test
    fun `malformed JSON yields null without throwing`(@TempDir home: File) {
        File(home, ".claude.json").writeText("{not json")
        assertNull(ClaudeAccountIdentityReader.read(configDir = null, home = home.absolutePath))
    }

    @Test
    fun `non-object root yields null`(@TempDir home: File) {
        File(home, ".claude.json").writeText("[]")
        assertNull(ClaudeAccountIdentityReader.read(configDir = null, home = home.absolutePath))
    }

    @Test
    fun `null valued fields deserialize to null`(@TempDir home: File) {
        File(home, ".claude.json").writeText(
            """{"oauthAccount":{"emailAddress":null,"organizationName":null,"displayName":null}}"""
        )
        val identity = ClaudeAccountIdentityReader.read(configDir = null, home = home.absolutePath)
        assertNotNull(identity)
        assertNull(identity!!.emailAddress)
        assertNull(identity.organizationName)
        assertNull(identity.displayName)
    }
}
