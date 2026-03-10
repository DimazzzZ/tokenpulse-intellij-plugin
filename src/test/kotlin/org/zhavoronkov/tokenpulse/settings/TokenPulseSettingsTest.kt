package org.zhavoronkov.tokenpulse.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType

/**
 * Tests for [TokenPulseSettings] data class.
 */
class TokenPulseSettingsTest {

    @Test
    fun `TokenPulseSettings has default values`() {
        val settings = TokenPulseSettings()

        assertTrue(settings.accounts.isEmpty())
        assertEquals(15, settings.refreshIntervalMinutes)
        assertTrue(settings.autoRefreshEnabled)
        assertTrue(settings.showCredits)
        assertTrue(settings.showTokens)
        assertFalse(settings.hasSeenWelcome)
        assertEquals("", settings.lastSeenVersion)
    }

    @Test
    fun `TokenPulseSettings can hold accounts`() {
        val accounts = listOf(
            Account(connectionType = ConnectionType.OPENROUTER_PROVISIONING),
            Account(connectionType = ConnectionType.CLINE_API)
        )
        val settings = TokenPulseSettings(accounts = accounts)

        assertEquals(2, settings.accounts.size)
    }

    @Test
    fun `TokenPulseSettings can customize refresh interval`() {
        val settings = TokenPulseSettings(refreshIntervalMinutes = 5)

        assertEquals(5, settings.refreshIntervalMinutes)
    }

    @Test
    fun `TokenPulseSettings can disable auto refresh`() {
        val settings = TokenPulseSettings(autoRefreshEnabled = false)

        assertFalse(settings.autoRefreshEnabled)
    }

    @Test
    fun `TokenPulseSettings can hide credits`() {
        val settings = TokenPulseSettings(showCredits = false)

        assertFalse(settings.showCredits)
    }

    @Test
    fun `TokenPulseSettings can hide tokens`() {
        val settings = TokenPulseSettings(showTokens = false)

        assertFalse(settings.showTokens)
    }

    @Test
    fun `TokenPulseSettings tracks welcome state`() {
        val settings = TokenPulseSettings(hasSeenWelcome = true)

        assertTrue(settings.hasSeenWelcome)
    }

    @Test
    fun `TokenPulseSettings tracks last seen version`() {
        val settings = TokenPulseSettings(lastSeenVersion = "1.2.3")

        assertEquals("1.2.3", settings.lastSeenVersion)
    }

    @Test
    fun `TokenPulseSettings copy works correctly`() {
        val original = TokenPulseSettings(refreshIntervalMinutes = 10)
        val copy = original.copy(autoRefreshEnabled = false)

        assertEquals(10, copy.refreshIntervalMinutes)
        assertFalse(copy.autoRefreshEnabled)
    }

    @Test
    fun `TokenPulseSettings accounts list is mutable`() {
        val settings = TokenPulseSettings()
        settings.accounts = listOf(Account())

        assertEquals(1, settings.accounts.size)
    }
}
