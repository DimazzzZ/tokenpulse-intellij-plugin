package org.zhavoronkov.tokenpulse.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType

/**
 * Tests for [Account] data class and related functions.
 */
class AccountTest {

    @Test
    fun `Account has unique ID by default`() {
        val account1 = Account()
        val account2 = Account()

        assertNotEquals(account1.id, account2.id)
    }

    @Test
    fun `Account has default values`() {
        val account = Account()

        assertEquals("", account.name)
        assertEquals(ConnectionType.CLINE_API, account.connectionType)
        assertEquals(AuthType.CLINE_API_KEY, account.authType)
        assertTrue(account.isEnabled)
        assertEquals("", account.keyPreview)
    }

    @Test
    fun `displayLabel returns provider name when no key preview`() {
        val account = Account(connectionType = ConnectionType.OPENROUTER_PROVISIONING)

        val label = account.displayLabel()

        assertTrue(label.contains("OpenRouter"))
        assertFalse(label.contains("•"))
    }

    @Test
    fun `displayLabel includes key preview when present`() {
        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            keyPreview = "sk-or-…91bc"
        )

        val label = account.displayLabel()

        assertTrue(label.contains("OpenRouter"))
        assertTrue(label.contains("•"))
        assertTrue(label.contains("sk-or-…91bc"))
    }

    @Test
    fun `displayLabel uses fullDisplayName from connectionType`() {
        val account = Account(connectionType = ConnectionType.CODEX_CLI)

        val label = account.displayLabel()

        assertEquals("OpenAI: Codex CLI", label)
    }

    @Test
    fun `displayLabel prefers name over keyPreview when name is set`() {
        val account = Account(
            connectionType = ConnectionType.CLAUDE_CODE,
            name = "work@ex.com • Team",
            keyPreview = "cli-mode"
        )
        assertTrue(account.displayLabel().contains("work@ex.com • Team"))
        // keyPreview must not leak into the label once a name is set.
        assertFalse(account.displayLabel().contains("cli-mode"))
    }

    @Test
    fun `displayLabel falls back to keyPreview when name is blank`() {
        val account = Account(
            connectionType = ConnectionType.CLAUDE_CODE,
            name = "   ",
            keyPreview = "cli-mode"
        )
        assertTrue(account.displayLabel().contains("cli-mode"))
    }

    @Test
    fun `claudeConfigDir defaults to null and survives copy`() {
        val a = Account(connectionType = ConnectionType.CLAUDE_CODE)
        assertEquals(null, a.claudeConfigDir)

        val b = a.copy(claudeConfigDir = "/home/x/.claude-work")
        assertEquals("/home/x/.claude-work", b.claudeConfigDir)

        // copy without touching the field must preserve it (back-compat for
        // sanitizeAccounts / normalizeConnectionAuthTypes).
        val c = b.copy(isEnabled = false)
        assertEquals("/home/x/.claude-work", c.claudeConfigDir)
    }

    @Test
    fun `Account fields are mutable (var not val) for XStream serialization`() {
        val account = Account(
            id = "test-id",
            name = "Test",
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY,
            keyPreview = "sk-or-…91bc"
        )

        // Verify all fields can be modified (var not val)
        account.id = "new-id"
        account.name = "New Name"
        account.connectionType = ConnectionType.XIAOMI_API
        account.authType = AuthType.XIAOMI_API_KEY
        account.isEnabled = false
        account.keyPreview = "new-preview"

        assertEquals("new-id", account.id)
        assertEquals("New Name", account.name)
        assertEquals(ConnectionType.XIAOMI_API, account.connectionType)
        assertEquals(AuthType.XIAOMI_API_KEY, account.authType)
        assertFalse(account.isEnabled)
        assertEquals("new-preview", account.keyPreview)
    }

    @Test
    fun `Account copy preserves all fields`() {
        val original = Account(
            id = "test-id",
            name = "Test",
            connectionType = ConnectionType.XIAOMI_TOKEN_PLAN,
            authType = AuthType.XIAOMI_TOKEN_PLAN_KEY,
            isEnabled = false,
            keyPreview = "tp-…1234"
        )

        val copy = original.copy()

        assertEquals(original.id, copy.id)
        assertEquals(original.name, copy.name)
        assertEquals(original.connectionType, copy.connectionType)
        assertEquals(original.authType, copy.authType)
        assertEquals(original.isEnabled, copy.isEnabled)
        assertEquals(original.keyPreview, copy.keyPreview)
    }

    @Test
    fun `Account connectionType and authType are preserved after copy`() {
        val original = Account(
            connectionType = ConnectionType.NEBIUS_BILLING,
            authType = AuthType.NEBIUS_BILLING_SESSION
        )

        val copy = original.copy(name = "Updated")

        assertEquals(ConnectionType.NEBIUS_BILLING, copy.connectionType)
        assertEquals(AuthType.NEBIUS_BILLING_SESSION, copy.authType)
    }
}

/**
 * Tests for [generateKeyPreview] function.
 */
class GenerateKeyPreviewTest {

    @Test
    fun `generateKeyPreview returns ellipsis for very short key`() {
        assertEquals("…", generateKeyPreview("short"))
        assertEquals("…", generateKeyPreview("abc"))
        assertEquals("…", generateKeyPreview(""))
    }

    @Test
    fun `generateKeyPreview masks middle of key`() {
        val key = "sk-or-v1-abc123def456"
        val preview = generateKeyPreview(key)

        assertTrue(preview.startsWith("sk-or-"))
        assertTrue(preview.endsWith("f456"))
        assertTrue(preview.contains("…"))
    }

    @Test
    fun `generateKeyPreview keeps first 6 and last 4 characters`() {
        val key = "1234567890abcdefghij"
        val preview = generateKeyPreview(key)

        assertEquals("123456…ghij", preview)
    }

    @Test
    fun `generateKeyPreview handles exactly 10 character key`() {
        val key = "1234567890"
        val preview = generateKeyPreview(key)

        assertEquals("123456…7890", preview)
    }
}

/**
 * Tests for [claudeConfigDirLabel] — the config-dir display used in the
 * accounts table and stored as keyPreview for Claude Code accounts.
 */
class ClaudeConfigDirLabelTest {

    @Test
    fun `null or blank config dir maps to default label`() {
        assertEquals("~/.claude", claudeConfigDirLabel(null))
        assertEquals("~/.claude", claudeConfigDirLabel(""))
        assertEquals("~/.claude", claudeConfigDirLabel("   "))
    }

    @Test
    fun `non-default config dir maps to tilde plus basename`() {
        assertEquals("~/.claude-work", claudeConfigDirLabel("/Users/me/.claude-work"))
        assertEquals("~/.claude", claudeConfigDirLabel("/Users/me/.claude"))
        assertEquals("~/claude", claudeConfigDirLabel("/Users/me/.config/claude"))
    }
}

/**
 * Tests for [normalizeConnectionAuthTypes] extension function.
 */
class NormalizeConnectionAuthTypesTest {

    @Test
    fun `normalizeConnectionAuthTypes fixes mismatched auth type`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.OPENROUTER_PROVISIONING,
                authType = AuthType.CLINE_API_KEY // Wrong type
            )
        )

        val normalized = accounts.normalizeConnectionAuthTypes()

        assertEquals(AuthType.OPENROUTER_PROVISIONING_KEY, normalized[0].authType)
    }

    @Test
    fun `normalizeConnectionAuthTypes preserves correct auth type`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLINE_API,
                authType = AuthType.CLINE_API_KEY
            )
        )

        val normalized = accounts.normalizeConnectionAuthTypes()

        assertEquals(AuthType.CLINE_API_KEY, normalized[0].authType)
    }

    @Test
    fun `normalizeConnectionAuthTypes allows both OAuth and API key for OpenAI Platform`() {
        val accountsWithOAuth = listOf(
            Account(
                connectionType = ConnectionType.OPENAI_PLATFORM,
                authType = AuthType.OPENAI_OAUTH
            )
        )

        val accountsWithApiKey = listOf(
            Account(
                connectionType = ConnectionType.OPENAI_PLATFORM,
                authType = AuthType.OPENAI_API_KEY
            )
        )

        val normalizedOAuth = accountsWithOAuth.normalizeConnectionAuthTypes()
        val normalizedApiKey = accountsWithApiKey.normalizeConnectionAuthTypes()

        assertEquals(AuthType.OPENAI_OAUTH, normalizedOAuth[0].authType)
        assertEquals(AuthType.OPENAI_API_KEY, normalizedApiKey[0].authType)
    }

    @Test
    fun `normalizeConnectionAuthTypes handles empty list`() {
        val accounts = emptyList<Account>()

        val normalized = accounts.normalizeConnectionAuthTypes()

        assertTrue(normalized.isEmpty())
    }

    @Test
    fun `normalizeConnectionAuthTypes handles multiple accounts`() {
        val accounts = listOf(
            Account(connectionType = ConnectionType.CLINE_API),
            Account(connectionType = ConnectionType.OPENROUTER_PROVISIONING),
            Account(connectionType = ConnectionType.NEBIUS_BILLING)
        )

        val normalized = accounts.normalizeConnectionAuthTypes()

        assertEquals(3, normalized.size)
        assertEquals(AuthType.CLINE_API_KEY, normalized[0].authType)
        assertEquals(AuthType.OPENROUTER_PROVISIONING_KEY, normalized[1].authType)
        assertEquals(AuthType.NEBIUS_BILLING_SESSION, normalized[2].authType)
    }
}

/**
 * Tests for [sanitizeAccounts] extension function.
 */
class SanitizeAccountsTest {

    @Test
    fun `sanitizeAccounts preserves valid accounts`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLINE_API,
                authType = AuthType.CLINE_API_KEY,
                isEnabled = true
            )
        )

        val sanitized = accounts.sanitizeAccounts()

        assertEquals(1, sanitized.size)
        assertEquals(ConnectionType.CLINE_API, sanitized[0].connectionType)
        assertEquals(AuthType.CLINE_API_KEY, sanitized[0].authType)
        assertTrue(sanitized[0].isEnabled)
    }

    @Test
    fun `sanitizeAccounts fixes mismatched auth type`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.NEBIUS_BILLING,
                authType = AuthType.CLINE_API_KEY // Wrong
            )
        )

        val sanitized = accounts.sanitizeAccounts()

        assertEquals(AuthType.NEBIUS_BILLING_SESSION, sanitized[0].authType)
    }

    @Test
    fun `sanitizeAccounts preserves isEnabled state`() {
        val accounts = listOf(
            Account(isEnabled = false),
            Account(isEnabled = true)
        )

        val sanitized = accounts.sanitizeAccounts()

        assertFalse(sanitized[0].isEnabled)
        assertTrue(sanitized[1].isEnabled)
    }

    @Test
    fun `sanitizeAccounts handles empty list`() {
        val accounts = emptyList<Account>()

        val sanitized = accounts.sanitizeAccounts()

        assertTrue(sanitized.isEmpty())
    }

    @Test
    fun `sanitizeAccounts allows OAuth for OpenAI Platform`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.OPENAI_PLATFORM,
                authType = AuthType.OPENAI_OAUTH
            )
        )

        val sanitized = accounts.sanitizeAccounts()

        assertEquals(AuthType.OPENAI_OAUTH, sanitized[0].authType)
    }

    // ── Claude Code keyPreview backfill (auto-migration on load) ──────────

    @Test
    fun `sanitizeAccounts backfills blank keyPreview for default Claude account`() {
        // Old account: no claudeConfigDir, no keyPreview (pre-multi-account state).
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLAUDE_CODE,
                authType = AuthType.CLAUDE_CODE_LOCAL,
                keyPreview = "",
                claudeConfigDir = null
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("~/.claude", sanitized[0].keyPreview)
    }

    @Test
    fun `sanitizeAccounts backfills blank keyPreview for non-default Claude account`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLAUDE_CODE,
                authType = AuthType.CLAUDE_CODE_LOCAL,
                keyPreview = "",
                claudeConfigDir = "/Users/me/.claude-work"
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("~/.claude-work", sanitized[0].keyPreview)
    }

    @Test
    fun `sanitizeAccounts replaces legacy 'CLI' keyPreview with config dir label`() {
        // Older single-account flow used to store "CLI" as the preview.
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLAUDE_CODE,
                authType = AuthType.CLAUDE_CODE_LOCAL,
                keyPreview = "CLI",
                claudeConfigDir = null
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("~/.claude", sanitized[0].keyPreview)
    }

    @Test
    fun `sanitizeAccounts does not touch already-set Claude keyPreview`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLAUDE_CODE,
                authType = AuthType.CLAUDE_CODE_LOCAL,
                keyPreview = "~/.claude-personal",
                claudeConfigDir = "/Users/me/.claude-personal"
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("~/.claude-personal", sanitized[0].keyPreview)
    }

    @Test
    fun `sanitizeAccounts does not touch non-Claude keyPreview`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.OPENROUTER_PROVISIONING,
                authType = AuthType.OPENROUTER_PROVISIONING_KEY,
                keyPreview = "",
                claudeConfigDir = null
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("", sanitized[0].keyPreview)
    }

    @Test
    fun `sanitizeAccounts collapses stale auto-org Claude name`() {
        // Pre-fix persisted form: "email • email's Organization" (straight apostrophe).
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLAUDE_CODE,
                authType = AuthType.CLAUDE_CODE_LOCAL,
                name = "dimaz.lark@gmail.com • dimaz.lark@gmail.com's Organization",
                keyPreview = "~/.claude",
                claudeConfigDir = null
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("dimaz.lark@gmail.com", sanitized[0].name)
    }

    @Test
    fun `sanitizeAccounts collapses stale auto-org Claude name with curly apostrophe`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLAUDE_CODE,
                authType = AuthType.CLAUDE_CODE_LOCAL,
                name = "a@b.com • a@b.com\u2019s Organization",
                keyPreview = "~/.claude",
                claudeConfigDir = null
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("a@b.com", sanitized[0].name)
    }

    @Test
    fun `sanitizeAccounts keeps real org name on Claude account`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLAUDE_CODE,
                authType = AuthType.CLAUDE_CODE_LOCAL,
                name = "me@example.com • Acme",
                keyPreview = "~/.claude",
                claudeConfigDir = null
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("me@example.com • Acme", sanitized[0].name)
    }

    @Test
    fun `sanitizeAccounts leaves Claude name without separator untouched`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.CLAUDE_CODE,
                authType = AuthType.CLAUDE_CODE_LOCAL,
                name = "a@b.com",
                keyPreview = "~/.claude",
                claudeConfigDir = null
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("a@b.com", sanitized[0].name)
    }

    @Test
    fun `sanitizeAccounts does not touch auto-org-shaped name on non-Claude account`() {
        // A non-Claude account with a name that happens to have this shape
        // must NOT be rewritten — the migration is Claude-only.
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.OPENROUTER_PROVISIONING,
                authType = AuthType.OPENROUTER_PROVISIONING_KEY,
                name = "user@ex.com • user@ex.com's Organization",
                keyPreview = "",
                claudeConfigDir = null
            )
        )
        val sanitized = accounts.sanitizeAccounts()
        assertEquals("user@ex.com • user@ex.com's Organization", sanitized[0].name)
    }

    @Test
    fun `sanitizeAccounts migrates legacy XIAOMI_API to unified XIAOMI with session auth`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.XIAOMI_API,
                authType = AuthType.XIAOMI_API_KEY
            )
        )

        val sanitized = accounts.sanitizeAccounts()

        assertEquals(ConnectionType.XIAOMI, sanitized[0].connectionType)
        assertEquals(AuthType.XIAOMI_SESSION, sanitized[0].authType)
    }

    @Test
    fun `sanitizeAccounts migrates legacy XIAOMI_TOKEN_PLAN to unified XIAOMI with session auth`() {
        val accounts = listOf(
            Account(
                connectionType = ConnectionType.XIAOMI_TOKEN_PLAN,
                authType = AuthType.XIAOMI_TOKEN_PLAN_KEY
            )
        )

        val sanitized = accounts.sanitizeAccounts()

        assertEquals(ConnectionType.XIAOMI, sanitized[0].connectionType)
        assertEquals(AuthType.XIAOMI_SESSION, sanitized[0].authType)
    }
}

/**
 * Tests for [AuthType] enum.
 */
class AuthTypeTest {

    @Test
    fun `all auth types have display names`() {
        AuthType.entries.forEach { authType ->
            assertTrue(authType.displayName.isNotBlank())
        }
    }

    @Test
    fun `CLAUDE_CODE_LOCAL has correct display name`() {
        assertEquals("Local Config", AuthType.CLAUDE_CODE_LOCAL.displayName)
    }

    @Test
    fun `OPENROUTER_PROVISIONING_KEY has correct display name`() {
        assertEquals("Provisioning Key", AuthType.OPENROUTER_PROVISIONING_KEY.displayName)
    }

    @Test
    fun `NEBIUS_BILLING_SESSION has correct display name`() {
        assertEquals("Billing Session", AuthType.NEBIUS_BILLING_SESSION.displayName)
    }

    @Test
    fun `OPENAI_API_KEY has correct display name`() {
        assertEquals("API Key", AuthType.OPENAI_API_KEY.displayName)
    }
}
