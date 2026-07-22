package org.zhavoronkov.tokenpulse.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.oauth.OAUTH_USER_AGENT

class PluginVersionTest {

    @Test
    fun `version resolves to a real, substituted value`() {
        val v = PluginVersion.value
        assertTrue(v.isNotBlank(), "version should not be blank")
        // The Gradle placeholder must have been substituted by processResources.
        assertFalse(v.startsWith("$"), "version should not be an unsubstituted \${...} placeholder: $v")
        assertFalse(v.startsWith("@"), "version should not be an unsubstituted @...@ placeholder: $v")
    }

    @Test
    fun `oauth user-agent carries the plugin version`() {
        assertEquals("TokenPulse/${PluginVersion.value}", OAUTH_USER_AGENT)
    }
}
