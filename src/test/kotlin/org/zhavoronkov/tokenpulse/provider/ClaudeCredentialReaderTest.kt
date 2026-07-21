package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.CLAUDE_TOKEN_EXPIRY_SKEW_MS
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCredentialReader
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.isClaudeTokenExpired
import org.zhavoronkov.tokenpulse.utils.HostOs
import java.io.File
import java.nio.file.Path

/**
 * Tests for [ClaudeCredentialReader]'s file-mode path. Constructed with
 * `osType = LINUX` so the reader takes the plaintext-file branch and never
 * touches the macOS Keychain subprocess (the dev/CI host may be macOS).
 *
 * Credentials live at `<configDir>/.credentials.json` with the shape
 * `{ "claudeAiOauth": { accessToken, refreshToken, expiresAt } }`.
 */
class ClaudeCredentialReaderTest {

    private fun writeCredentials(dir: Path, json: String): String {
        File(dir.toFile(), ".credentials.json").writeText(json)
        return dir.toFile().absolutePath
    }

    private fun reader(configDir: String) =
        ClaudeCredentialReader(configDir, osType = HostOs.LINUX)

    @Test
    fun `reads access, refresh, and expiresAt from valid credentials`(@TempDir dir: Path) {
        val configDir = writeCredentials(
            dir,
            """
            {
              "claudeAiOauth": {
                "accessToken": "acc-token-123",
                "refreshToken": "ref-token-456",
                "expiresAt": 9999999999999
              }
            }
            """.trimIndent()
        )
        val r = reader(configDir)
        assertEquals("acc-token-123", r.readAccessToken())
        assertEquals("ref-token-456", r.readRefreshToken())
        assertEquals(9999999999999L, r.readExpiresAt())
    }

    @Test
    fun `isTokenExpired true when expiresAt is in the past`(@TempDir dir: Path) {
        val configDir = writeCredentials(
            dir,
            """{ "claudeAiOauth": { "accessToken": "a", "expiresAt": 1000 } }"""
        )
        assertTrue(reader(configDir).isTokenExpired())
    }

    @Test
    fun `isTokenExpired false when expiresAt is in the future`(@TempDir dir: Path) {
        val future = System.currentTimeMillis() + 3_600_000L
        val configDir = writeCredentials(
            dir,
            """{ "claudeAiOauth": { "accessToken": "a", "expiresAt": $future } }"""
        )
        assertFalse(reader(configDir).isTokenExpired())
    }

    @Test
    fun `isTokenExpired false when expiresAt is missing (unknown != expired)`(@TempDir dir: Path) {
        // A missing/unparseable expiresAt must NOT force a "session expired":
        // the access token may still be valid, and the usage API's real 401 is
        // the authoritative signal.
        val configDir = writeCredentials(
            dir,
            """{ "claudeAiOauth": { "accessToken": "a" } }"""
        )
        assertFalse(reader(configDir).isTokenExpired())
    }

    @Test
    fun `missing credentials file returns null token`(@TempDir dir: Path) {
        // No .credentials.json written.
        assertNull(reader(dir.toFile().absolutePath).readAccessToken())
    }

    @Test
    fun `malformed json returns null token`(@TempDir dir: Path) {
        val configDir = writeCredentials(dir, "{ not valid json ]")
        assertNull(reader(configDir).readAccessToken())
    }

    @Test
    fun `json array root returns null token`(@TempDir dir: Path) {
        val configDir = writeCredentials(dir, "[1, 2, 3]")
        assertNull(reader(configDir).readAccessToken())
    }

    @Test
    fun `missing accessToken field returns null`(@TempDir dir: Path) {
        val configDir = writeCredentials(
            dir,
            """{ "claudeAiOauth": { "refreshToken": "r" } }"""
        )
        assertNull(reader(configDir).readAccessToken())
    }

    @Test
    fun `blank accessToken returns null`(@TempDir dir: Path) {
        val configDir = writeCredentials(
            dir,
            """{ "claudeAiOauth": { "accessToken": "   " } }"""
        )
        assertNull(reader(configDir).readAccessToken())
    }

    @Test
    fun `missing claudeAiOauth object returns null`(@TempDir dir: Path) {
        val configDir = writeCredentials(dir, """{ "somethingElse": {} }""")
        assertNull(reader(configDir).readAccessToken())
    }

    // ── isClaudeTokenExpired: pure expiry decision ─────────────────────────

    @Test
    fun `isClaudeTokenExpired treats null expiresAt as NOT expired`() {
        // Unknown/unparseable expiry must not force a "session expired" for a
        // user whose access token is otherwise valid — let the usage API's
        // real 401 be the authoritative signal.
        assertFalse(isClaudeTokenExpired(expiresAt = null, now = 1_000_000L))
    }

    @Test
    fun `isClaudeTokenExpired reports expired well past expiry`() {
        val expiresAt = 1_000_000L
        val now = expiresAt + 10 * CLAUDE_TOKEN_EXPIRY_SKEW_MS
        assertTrue(isClaudeTokenExpired(expiresAt, now))
    }

    @Test
    fun `isClaudeTokenExpired reports NOT expired well before expiry`() {
        val expiresAt = 1_000_000_000L
        val now = expiresAt - 10 * CLAUDE_TOKEN_EXPIRY_SKEW_MS
        assertFalse(isClaudeTokenExpired(expiresAt, now))
    }

    @Test
    fun `isClaudeTokenExpired trips exactly at the skew boundary`() {
        val expiresAt = 1_000_000_000L
        // now == expiresAt - skew  =>  expired (>= in the pure helper).
        assertTrue(isClaudeTokenExpired(expiresAt, expiresAt - CLAUDE_TOKEN_EXPIRY_SKEW_MS))
        // One millisecond earlier still counts as valid.
        assertFalse(isClaudeTokenExpired(expiresAt, expiresAt - CLAUDE_TOKEN_EXPIRY_SKEW_MS - 1))
    }
}
