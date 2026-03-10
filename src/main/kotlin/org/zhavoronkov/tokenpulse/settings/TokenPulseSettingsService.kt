package org.zhavoronkov.tokenpulse.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.ChatGptOAuthManager
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

@Service(Service.Level.APP)
@State(
    name = "TokenPulseSettings",
    storages = [Storage("tokenpulse.xml")]
)
class TokenPulseSettingsService : PersistentStateComponent<TokenPulseSettings> {
    private var mySettings = TokenPulseSettings()

    override fun getState(): TokenPulseSettings = mySettings

    override fun loadState(state: TokenPulseSettings) {
        // Defensive wrapper to prevent init-time crashes from malformed state
        mySettings = runCatching {
            state.copy(
                accounts = state.accounts
                    .normalizeConnectionAuthTypes()
                    .sanitizeAccounts()
            )
        }.onFailure { e ->
            TokenPulseLogger.Settings.error("Failed to load settings, using defaults", e)
        }.getOrDefault(TokenPulseSettings())

        // Clean up orphaned ChatGPT OAuth credentials
        cleanupOrphanedChatGptCredentials()
    }

    /**
     * If there are no ChatGPT accounts in settings but OAuth credentials exist,
     * clear the orphaned credentials to prevent stale "connected as" state.
     */
    private fun cleanupOrphanedChatGptCredentials() {
        val hasChatGptAccount = mySettings.accounts.any {
            it.connectionType == ConnectionType.CHATGPT_SUBSCRIPTION
        }
        if (!hasChatGptAccount) {
            val oauthManager = ChatGptOAuthManager.getInstance()
            if (oauthManager.isAuthenticated()) {
                TokenPulseLogger.Settings.info(
                    "No ChatGPT accounts found, clearing orphaned OAuth credentials"
                )
                oauthManager.clearCredentials()
            }
        }
    }

    companion object {
        fun getInstance(): TokenPulseSettingsService = service()
    }
}
