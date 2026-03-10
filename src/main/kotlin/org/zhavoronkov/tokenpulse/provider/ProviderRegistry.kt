package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCodeProviderClient
import org.zhavoronkov.tokenpulse.provider.cline.ClineProviderClient
import org.zhavoronkov.tokenpulse.provider.nebius.NebiusProviderClient
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.ChatGptSubscriptionProviderClient
import org.zhavoronkov.tokenpulse.provider.openai.platform.OpenAiPlatformProviderClient
import org.zhavoronkov.tokenpulse.provider.openrouter.OpenRouterPluginBridgeClient
import org.zhavoronkov.tokenpulse.provider.openrouter.OpenRouterProviderClient

/**
 * Registry for provider client instances.
 *
 * This interface abstracts the mapping from connection types to their respective
 * client implementations.
 */
interface ProviderRegistry {

    /**
     * Get the client for a [ConnectionType].
     */
    fun getClient(connectionType: ConnectionType): ProviderClient
}

/**
 * Default implementation of [ProviderRegistry] with lazy client initialization.
 */
class DefaultProviderRegistry(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) : ProviderRegistry {

    private val openRouterClient by lazy { OpenRouterProviderClient(httpClient, gson) }
    private val openRouterPluginClient by lazy { OpenRouterPluginBridgeClient() }
    private val clineClient by lazy { ClineProviderClient(httpClient, gson) }
    private val nebiusClient by lazy { NebiusProviderClient(httpClient, gson) }
    private val openAiPlatformClient by lazy { OpenAiPlatformProviderClient(httpClient, gson) }
    private val chatGptClient by lazy { ChatGptSubscriptionProviderClient(httpClient, gson) }
    private val claudeCodeClient by lazy { ClaudeCodeProviderClient() }

    override fun getClient(connectionType: ConnectionType): ProviderClient {
        return when (connectionType) {
            ConnectionType.OPENROUTER_PROVISIONING -> openRouterClient
            ConnectionType.OPENROUTER_PLUGIN -> openRouterPluginClient
            ConnectionType.CLINE_API -> clineClient
            ConnectionType.NEBIUS_BILLING -> nebiusClient
            ConnectionType.OPENAI_PLATFORM -> openAiPlatformClient
            ConnectionType.CHATGPT_SUBSCRIPTION -> chatGptClient
            ConnectionType.CLAUDE_CODE -> claudeCodeClient
        }
    }
}
