package org.zhavoronkov.tokenpulse.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [Provider] enum.
 */
class ProviderTest {

    @Test
    fun `all providers have a display name`() {
        Provider.entries.forEach { provider ->
            assertTrue(provider.displayName.isNotBlank())
        }
    }

    @Test
    fun `sortedByDisplayName returns alphabetically sorted list`() {
        val sorted = Provider.sortedByDisplayName()

        for (i in 0 until sorted.size - 1) {
            assertTrue(
                sorted[i].displayName <= sorted[i + 1].displayName,
                "Expected ${sorted[i].displayName} <= ${sorted[i + 1].displayName}"
            )
        }
    }

    @Test
    fun `sortedByDisplayName contains all providers`() {
        val sorted = Provider.sortedByDisplayName()
        assertEquals(Provider.entries.size, sorted.size)
        assertTrue(sorted.containsAll(Provider.entries))
    }

    @Test
    fun `ANTHROPIC has correct display name`() {
        assertEquals("Anthropic", Provider.ANTHROPIC.displayName)
    }

    @Test
    fun `OPENAI has correct display name`() {
        assertEquals("OpenAI", Provider.OPENAI.displayName)
    }

    @Test
    fun `CLINE has correct display name`() {
        assertEquals("Cline", Provider.CLINE.displayName)
    }

    @Test
    fun `OPENROUTER has correct display name`() {
        assertEquals("OpenRouter", Provider.OPENROUTER.displayName)
    }

    @Test
    fun `NEBIUS has correct display name`() {
        assertEquals("Nebius", Provider.NEBIUS.displayName)
    }

    @Test
    fun `there are exactly 5 providers`() {
        assertEquals(5, Provider.entries.size)
    }
}
