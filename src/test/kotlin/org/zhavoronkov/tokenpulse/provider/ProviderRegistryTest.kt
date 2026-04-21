package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCodeProviderClient
import org.zhavoronkov.tokenpulse.provider.cline.ClineProviderClient
import org.zhavoronkov.tokenpulse.provider.nebius.NebiusProviderClient
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.CodexProviderClient
import org.zhavoronkov.tokenpulse.provider.openai.platform.OpenAiPlatformProviderClient
import org.zhavoronkov.tokenpulse.provider.openrouter.OpenRouterProviderClient

/**
 * Tests for [ProviderRegistry].
 */
class ProviderRegistryTest {

    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private val registry = DefaultProviderRegistry(httpClient, gson)

    @Test
    fun `getClient returns OpenRouterProviderClient for OPENROUTER_PROVISIONING`() {
        val client = registry.getClient(ConnectionType.OPENROUTER_PROVISIONING)
        assertTrue(client is OpenRouterProviderClient)
    }

    @Test
    fun `getClient returns ClineProviderClient for CLINE_API`() {
        val client = registry.getClient(ConnectionType.CLINE_API)
        assertTrue(client is ClineProviderClient)
    }

    @Test
    fun `getClient returns NebiusProviderClient for NEBIUS_BILLING`() {
        val client = registry.getClient(ConnectionType.NEBIUS_BILLING)
        assertTrue(client is NebiusProviderClient)
    }

    @Test
    fun `getClient returns OpenAiPlatformProviderClient for OPENAI_PLATFORM`() {
        val client = registry.getClient(ConnectionType.OPENAI_PLATFORM)
        assertTrue(client is OpenAiPlatformProviderClient)
    }

    @Test
    fun `getClient returns CodexProviderClient for CODEX_CLI`() {
        val client = registry.getClient(ConnectionType.CODEX_CLI)
        assertTrue(client is CodexProviderClient)
    }

    @Test
    fun `getClient returns ClaudeCodeProviderClient for CLAUDE_CODE`() {
        val client = registry.getClient(ConnectionType.CLAUDE_CODE)
        assertTrue(client is ClaudeCodeProviderClient)
    }

    @Test
    fun `all connection types are handled by registry`() {
        ConnectionType.entries.forEach { connectionType ->
            val client = registry.getClient(connectionType)
            assertNotNull(client, "Expected client for $connectionType")
        }
    }

    @Test
    fun `getClient returns same instance for repeated calls`() {
        val client1 = registry.getClient(ConnectionType.OPENROUTER_PROVISIONING)
        val client2 = registry.getClient(ConnectionType.OPENROUTER_PROVISIONING)
        assertTrue(client1 === client2, "Expected same instance (lazy initialization)")
    }

    @Test
    fun `registry can be created with custom http client`() {
        val customClient = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build()
        val customRegistry = DefaultProviderRegistry(customClient, gson)

        assertNotNull(customRegistry.getClient(ConnectionType.OPENROUTER_PROVISIONING))
    }
}
