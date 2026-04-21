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
