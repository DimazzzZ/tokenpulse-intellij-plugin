package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.Base64

class CodexCredentialReaderTest {

    @TempDir
    lateinit var tempDir: File

    private fun authFile(): File = File(tempDir, "auth.json")

    private fun reader(): CodexCredentialReader = CodexCredentialReader(authFile())

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val body = enc.encodeToString(payload.toByteArray())
        return "$header.$body.sig"
    }

    @Test
    fun `read returns null when file missing`() {
        assertNull(reader().read())
        assertNull(reader().accessToken())
    }

    @Test
    fun `read parses tokens and account id`() {
        authFile().writeText(
            """{"auth_mode":"chatgpt","tokens":{"id_token":"i","access_token":"a","refresh_token":"r","account_id":"acc-1"}}"""
        )
        val auth = reader().read()!!
        assertEquals("chatgpt", auth.authMode)
        assertEquals("a", auth.tokens?.accessToken)
        assertEquals("acc-1", reader().accountId())
    }

    @Test
    fun `accountId falls back to id_token claim when top-level absent`() {
        val idToken = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"claim-acc"}}""")
        authFile().writeText(
            """{"tokens":{"id_token":"$idToken","access_token":"a","refresh_token":"r"}}"""
        )
        assertEquals("claim-acc", reader().accountId())
    }

    @Test
    fun `isAccessTokenExpired true when exp is in the past`() {
        val past = Instant.now().minusSeconds(3600).epochSecond
        val token = jwt("""{"exp":$past}""")
        authFile().writeText("""{"tokens":{"access_token":"$token"}}""")
        assertTrue(reader().isAccessTokenExpired())
    }

    @Test
    fun `isAccessTokenExpired false when exp is well in the future`() {
        val future = Instant.now().plusSeconds(3600).epochSecond
        val token = jwt("""{"exp":$future}""")
        authFile().writeText("""{"tokens":{"access_token":"$token"}}""")
        assertFalse(reader().isAccessTokenExpired())
    }

    @Test
    fun `isAccessTokenExpired false when exp missing (unknown assume usable)`() {
        val token = jwt("""{"sub":"x"}""")
        authFile().writeText("""{"tokens":{"access_token":"$token"}}""")
        assertFalse(reader().isAccessTokenExpired())
    }

    @Test
    fun `writeAtomic preserves unknown fields and updates tokens`() {
        authFile().writeText(
            """{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"agent_identity":{"keep":"me"},"tokens":{"id_token":"old-id","access_token":"old-a","refresh_token":"old-r","account_id":"acc-1"},"last_refresh":"2020-01-01T00:00:00Z"}"""
        )
        val now = Instant.parse("2026-07-19T12:00:00Z")
        val ok = reader().writeAtomic(idToken = "new-id", accessToken = "new-a", refreshToken = "new-r", now = now)
        assertTrue(ok)

        val root = JsonParser.parseString(authFile().readText()).asJsonObject
        // Unknown / unmodeled fields preserved.
        assertEquals("me", root.getAsJsonObject("agent_identity").get("keep").asString)
        assertEquals("chatgpt", root.get("auth_mode").asString)
        // Tokens rotated, account_id preserved.
        val tokens = root.getAsJsonObject("tokens")
        assertEquals("new-a", tokens.get("access_token").asString)
        assertEquals("new-r", tokens.get("refresh_token").asString)
        assertEquals("new-id", tokens.get("id_token").asString)
        assertEquals("acc-1", tokens.get("account_id").asString)
        assertEquals("2026-07-19T12:00:00Z", root.get("last_refresh").asString)
    }

    @Test
    fun `writeAtomic keeps old refresh token when new one is null`() {
        authFile().writeText(
            """{"tokens":{"access_token":"old-a","refresh_token":"old-r"}}"""
        )
        reader().writeAtomic(idToken = null, accessToken = "new-a", refreshToken = null)
        val tokens = JsonParser.parseString(authFile().readText()).asJsonObject.getAsJsonObject("tokens")
        assertEquals("new-a", tokens.get("access_token").asString)
        assertEquals("old-r", tokens.get("refresh_token").asString)
    }

    @Test
    fun `writeAtomic sets owner-only permissions on posix`() {
        val supportsPosix = authFile().toPath().fileSystem.supportedFileAttributeViews().contains("posix")
        org.junit.jupiter.api.Assumptions.assumeTrue(supportsPosix, "POSIX filesystem required")
        authFile().writeText("""{"tokens":{"access_token":"a"}}""")
        reader().writeAtomic(idToken = null, accessToken = "b", refreshToken = null)
        val perms = Files.getPosixFilePermissions(authFile().toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test
    fun `writeAtomic returns false when file missing`() {
        assertFalse(reader().writeAtomic(idToken = null, accessToken = "a", refreshToken = null))
    }
}

class CodexResetNormalizationTest {
    @Test
    fun `epoch seconds passthrough`() {
        assertEquals(1_784_000_000L, normalizeResetAt(1_784_000_000L))
    }

    @Test
    fun `epoch millis divided to seconds`() {
        assertEquals(1_784_000_000L, normalizeResetAt(1_784_000_000_000L))
    }

    @Test
    fun `non-positive becomes null`() {
        assertNull(normalizeResetAt(0L))
        assertNull(normalizeResetAt(-5L))
        assertNull(normalizeResetAt(null))
    }
}
