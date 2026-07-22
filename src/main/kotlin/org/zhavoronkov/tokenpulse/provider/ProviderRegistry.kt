package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCodeProviderClient
import org.zhavoronkov.tokenpulse.provider.cline.ClineProviderClient
import org.zhavoronkov.tokenpulse.provider.nebius.NebiusProviderClient
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.CodexProviderClient
import org.zhavoronkov.tokenpulse.provider.openai.platform.OpenAiPlatformProviderClient
import org.zhavoronkov.tokenpulse.provider.openrouter.OpenRouterPluginBridgeClient
import org.zhavoronkov.tokenpulse.provider.openrouter.OpenRouterProviderClient
import org.zhavoronkov.tokenpulse.provider.xiaomi.XiaomiProviderClient
import org.zhavoronkov.tokenpulse.provider.xiaomi.XiaomiSessionRefresher
import org.zhavoronkov.tokenpulse.settings.CredentialsStore

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
    private val gson: Gson,
    /**
     * Persists a rotated session secret back to storage. Defaults to the
     * [CredentialsStore] app service; overridable in tests. Used by the Xiaomi
     * client to save a silently-refreshed session (see [XiaomiSessionRefresher]).
     */
    private val sessionWriter: (accountId: String, newSecretJson: String) -> Unit = { id, json ->
        CredentialsStore.getInstance().saveApiKey(id, json)
    }
) : ProviderRegistry {

    private val openRouterClient by lazy { OpenRouterProviderClient(httpClient, gson) }
    private val openRouterPluginClient by lazy { OpenRouterPluginBridgeClient() }
    private val clineClient by lazy { ClineProviderClient(httpClient, gson) }
    private val nebiusClient by lazy { NebiusProviderClient(httpClient, gson) }
    private val openAiPlatformClient by lazy { OpenAiPlatformProviderClient(httpClient, gson) }
    private val codexClient by lazy { CodexProviderClient() }
    private val claudeCodeClient by lazy { ClaudeCodeProviderClient() }
    private val xiaomiClient by lazy {
        XiaomiProviderClient(
            httpClient = httpClient,
            gson = gson,
            refresher = XiaomiSessionRefresher(httpClient, gson),
            sessionWriter = sessionWriter
        )
    }

    override fun getClient(connectionType: ConnectionType): ProviderClient {
        return when (connectionType) {
            ConnectionType.OPENROUTER_PROVISIONING -> openRouterClient
            ConnectionType.OPENROUTER_PLUGIN -> openRouterPluginClient
            ConnectionType.CLINE_API -> clineClient
            ConnectionType.NEBIUS_BILLING -> nebiusClient
            ConnectionType.OPENAI_PLATFORM -> openAiPlatformClient
            ConnectionType.CODEX_CLI -> codexClient
            ConnectionType.CLAUDE_CODE -> claudeCodeClient
            ConnectionType.XIAOMI_API -> xiaomiClient
            ConnectionType.XIAOMI_TOKEN_PLAN -> xiaomiClient
        }
    }
}
