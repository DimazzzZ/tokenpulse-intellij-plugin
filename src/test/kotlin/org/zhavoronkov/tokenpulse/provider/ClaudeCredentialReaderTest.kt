package org.zhavoronkov.tokenpulse.provider

import com.google.gson.JsonParser
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

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

    // ── writeTokens: credential write-back (file mode) ─────────────────────

    @Test
    fun `writeTokens preserves unrelated fields and updates rotating tokens`(@TempDir dir: Path) {
        val configDir = writeCredentials(
            dir,
            """
            {
              "claudeAiOauth": {
                "accessToken": "old-a",
                "refreshToken": "old-r",
                "expiresAt": 1000,
                "scopes": ["user:profile", "user:inference"],
                "subscriptionType": "pro",
                "rateLimitTier": "tier-2",
                "profile": { "name": "Dev" },
                "tokenAccount": { "uuid": "u-1", "emailAddress": "dev@example.com" }
              },
              "topLevelExtra": "keep-me"
            }
            """.trimIndent()
        )
        val r = reader(configDir)
        assertTrue(r.writeTokens("new-a", "new-r", 9999999999999L))

        val root = JsonParser.parseString(File(configDir, ".credentials.json").readText()).asJsonObject
        val oauth = root.getAsJsonObject("claudeAiOauth")
        assertEquals("new-a", oauth.get("accessToken").asString)
        assertEquals("new-r", oauth.get("refreshToken").asString)
        assertEquals(9999999999999L, oauth.get("expiresAt").asLong)
        // Unrelated fields survive the round-trip.
        assertEquals("pro", oauth.get("subscriptionType").asString)
        assertEquals("tier-2", oauth.get("rateLimitTier").asString)
        assertEquals("Dev", oauth.getAsJsonObject("profile").get("name").asString)
        assertEquals("dev@example.com", oauth.getAsJsonObject("tokenAccount").get("emailAddress").asString)
        assertEquals(2, oauth.getAsJsonArray("scopes").size())
        assertEquals("keep-me", root.get("topLevelExtra").asString)
    }

    @Test
    fun `writeTokens keeps existing refreshToken when new one is null`(@TempDir dir: Path) {
        val configDir = writeCredentials(
            dir,
            """{ "claudeAiOauth": { "accessToken": "old-a", "refreshToken": "old-r", "expiresAt": 1000 } }"""
        )
        val r = reader(configDir)
        assertTrue(r.writeTokens("new-a", null, 2000L))
        assertEquals("new-a", r.readAccessToken())
        assertEquals("old-r", r.readRefreshToken())
        assertEquals(2000L, r.readExpiresAt())
    }

    @Test
    fun `writeTokens round-trips via reader accessors`(@TempDir dir: Path) {
        val configDir = writeCredentials(
            dir,
            """{ "claudeAiOauth": { "accessToken": "a", "refreshToken": "r", "expiresAt": 1 } }"""
        )
        val r = reader(configDir)
        assertTrue(r.writeTokens("acc-2", "ref-2", 42L))
        assertEquals("acc-2", r.readAccessToken())
        assertEquals("ref-2", r.readRefreshToken())
        assertEquals(42L, r.readExpiresAt())
    }

    @Test
    fun `writeTokens sets owner-only permissions and leaves no temp file`(@TempDir dir: Path) {
        val configDir = writeCredentials(
            dir,
            """{ "claudeAiOauth": { "accessToken": "a", "expiresAt": 1 } }"""
        )
        assertTrue(reader(configDir).writeTokens("b", "c", 2L))

        val credFile = File(configDir, ".credentials.json")
        val perms = Files.getPosixFilePermissions(credFile.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
        // Atomic write must not leave a .tmp sibling behind.
        val leftovers = dir.toFile().listFiles { f -> f.name.contains(".json.tmp") }
        assertTrue(leftovers == null || leftovers.isEmpty())
    }

    @Test
    fun `writeTokens returns false when existing credentials unreadable`(@TempDir dir: Path) {
        // No .credentials.json => nothing to round-trip => best-effort false.
        assertFalse(reader(dir.toFile().absolutePath).writeTokens("a", "b", 1L))
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
