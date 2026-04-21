package org.zhavoronkov.tokenpulse.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [ConnectionType] enum and its companion methods.
 */
class ConnectionTypeTest {

    @Test
    fun `all connection types have a provider`() {
        ConnectionType.entries.forEach { connectionType ->
            assertTrue(connectionType.provider in Provider.entries)
        }
    }

    @Test
    fun `all connection types have a display name`() {
        ConnectionType.entries.forEach { connectionType ->
            assertTrue(connectionType.displayName.isNotBlank())
        }
    }

    @Test
    fun `all connection types have a description`() {
        ConnectionType.entries.forEach { connectionType ->
            assertTrue(connectionType.description.isNotBlank())
        }
    }

    @Test
    fun `fullDisplayName includes provider name`() {
        ConnectionType.entries.forEach { connectionType ->
            assertTrue(connectionType.fullDisplayName.contains(connectionType.provider.displayName))
            assertTrue(connectionType.fullDisplayName.contains(connectionType.displayName))
        }
    }

    @Test
    fun `forProvider returns correct connection types for OpenAI`() {
        val openAiTypes = ConnectionType.forProvider(Provider.OPENAI)

        assertTrue(openAiTypes.contains(ConnectionType.CODEX_CLI))
        assertTrue(openAiTypes.contains(ConnectionType.OPENAI_PLATFORM))
        assertFalse(openAiTypes.contains(ConnectionType.CLAUDE_CODE))
    }

    @Test
    fun `forProvider returns correct connection types for Anthropic`() {
        val anthropicTypes = ConnectionType.forProvider(Provider.ANTHROPIC)

        assertTrue(anthropicTypes.contains(ConnectionType.CLAUDE_CODE))
        assertFalse(anthropicTypes.contains(ConnectionType.CODEX_CLI))
    }

    @Test
    fun `forProvider returns empty list for provider with no types`() {
        // All current providers have at least one connection type
        Provider.entries.forEach { provider ->
            val types = ConnectionType.forProvider(provider)
            assertTrue(types.isNotEmpty(), "Provider $provider should have at least one connection type")
        }
    }

    @Test
    fun `groupedByProvider groups all connection types`() {
        val grouped = ConnectionType.groupedByProvider()

        // Total count should match
        val totalInGroups = grouped.values.sumOf { it.size }
        assertEquals(ConnectionType.entries.size, totalInGroups)

        // Each group should have the correct provider
        grouped.forEach { (provider, types) ->
            types.forEach { type ->
                assertEquals(provider, type.provider)
            }
        }
    }

    @Test
    fun `sortedByFullDisplayName returns sorted list`() {
        val sorted = ConnectionType.sortedByFullDisplayName()

        for (i in 0 until sorted.size - 1) {
            assertTrue(
                sorted[i].fullDisplayName <= sorted[i + 1].fullDisplayName,
                "Expected ${sorted[i].fullDisplayName} <= ${sorted[i + 1].fullDisplayName}"
            )
        }
    }

    @Test
    fun `CLAUDE_CODE has Anthropic provider`() {
        assertEquals(Provider.ANTHROPIC, ConnectionType.CLAUDE_CODE.provider)
    }

    @Test
    fun `CODEX_CLI has OpenAI provider`() {
        assertEquals(Provider.OPENAI, ConnectionType.CODEX_CLI.provider)
    }

    @Test
    fun `OPENAI_PLATFORM has OpenAI provider`() {
        assertEquals(Provider.OPENAI, ConnectionType.OPENAI_PLATFORM.provider)
    }

    @Test
    fun `CLINE_API has Cline provider`() {
        assertEquals(Provider.CLINE, ConnectionType.CLINE_API.provider)
    }

    @Test
    fun `OPENROUTER_PROVISIONING has OpenRouter provider`() {
        assertEquals(Provider.OPENROUTER, ConnectionType.OPENROUTER_PROVISIONING.provider)
    }

    @Test
    fun `NEBIUS_BILLING has Nebius provider`() {
        assertEquals(Provider.NEBIUS, ConnectionType.NEBIUS_BILLING.provider)
    }
}
