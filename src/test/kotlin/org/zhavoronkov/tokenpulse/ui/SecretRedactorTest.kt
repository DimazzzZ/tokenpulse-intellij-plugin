package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [SecretRedactor].
 *
 * The regex matches:
 * - `sk-` followed by 20+ alphanumeric chars (no dashes/underscores after sk-)
 * - Any 32+ alphanumeric chars (hex strings, API keys)
 * - Optional `Bearer ` prefix is preserved
 */
class SecretRedactorTest {

    @Test
    fun `redact returns empty string for null input`() {
        assertEquals("", SecretRedactor.redact(null))
    }

    @Test
    fun `redact returns original text when no secrets`() {
        assertEquals("Hello world", SecretRedactor.redact("Hello world"))
    }

    @Test
    fun `redact handles empty string`() {
        assertEquals("", SecretRedactor.redact(""))
    }

    @Test
    fun `redact replaces OpenAI sk- tokens`() {
        // sk- followed by 20+ alphanumeric chars
        val text = "Key is sk-abcdef123456789012345678"
        assertEquals("Key is [REDACTED]", SecretRedactor.redact(text))
    }

    @Test
    fun `redact replaces Bearer tokens`() {
        val text = "Authorization: Bearer sk-abcdef123456789012345678"
        assertEquals("Authorization: Bearer [REDACTED]", SecretRedactor.redact(text))
    }

    @Test
    fun `redact replaces long hex strings 32+ chars`() {
        val text = "Token: abcdef1234567890abcdef1234567890"
        assertEquals("Token: [REDACTED]", SecretRedactor.redact(text))
    }

    @Test
    fun `redact replaces long alphanumeric tokens 32+ chars`() {
        val text = "API key is ABCDEFGHIJKLMNOPQRSTUVWXYZ123456"
        assertEquals("API key is [REDACTED]", SecretRedactor.redact(text))
    }

    @Test
    fun `redact handles multiple secrets in same text`() {
        val text = "Key1: sk-abc12345678901234567890 and Key2: sk-def98765432109876543210"
        assertEquals("Key1: [REDACTED] and Key2: [REDACTED]", SecretRedactor.redact(text))
    }

    @Test
    fun `redact preserves text around secrets`() {
        val text = "Before sk-abc12345678901234567890 After"
        assertEquals("Before [REDACTED] After", SecretRedactor.redact(text))
    }

    @Test
    fun `redact ignores short sk- tokens under 20 chars`() {
        val text = "Key is sk-short123"
        assertEquals("Key is sk-short123", SecretRedactor.redact(text))
    }

    @Test
    fun `redact ignores short alphanumeric under 32 chars`() {
        val text = "Token: abc123def456ghi789"
        assertEquals("Token: abc123def456ghi789", SecretRedactor.redact(text))
    }

    @Test
    fun `redact is case insensitive for Bearer`() {
        val text = "bearer sk-abc12345678901234567890"
        assertEquals("bearer [REDACTED]", SecretRedactor.redact(text))
    }

    @Test
    fun `redact handles multiline text`() {
        val text = """
            Line1: sk-abc12345678901234567890
            Line2: normal text
            Line3: anotherSecret1234567890abcdef1234
        """.trimIndent()
        val expected = """
            Line1: [REDACTED]
            Line2: normal text
            Line3: [REDACTED]
        """.trimIndent()
        assertEquals(expected, SecretRedactor.redact(text))
    }

    @Test
    fun `redact handles JSON with secrets`() {
        val text = """{"api_key": "sk-abc12345678901234567890"}"""
        assertEquals("""{"api_key": "[REDACTED]"}""", SecretRedactor.redact(text))
    }

    @Test
    fun `redact does not match sk- with dashes in middle`() {
        // sk-proj-xxx style has dashes which break the regex
        val text = "Key is sk-proj-abc"
        assertEquals("Key is sk-proj-abc", SecretRedactor.redact(text))
    }

    @Test
    fun `redact does not match underscores in token`() {
        val text = "Token: abc_123_def_456_ghi_789_jkl_012"
        assertEquals("Token: abc_123_def_456_ghi_789_jkl_012", SecretRedactor.redact(text))
    }
}
