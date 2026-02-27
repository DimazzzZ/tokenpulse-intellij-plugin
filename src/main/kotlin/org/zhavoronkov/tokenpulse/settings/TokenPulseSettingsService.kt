package org.zhavoronkov.tokenpulse.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
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
        // Defensive wrapper to prevent init-time crashes from malformed legacy state
        mySettings = runCatching {
            state.copy(
                accounts = state.accounts
                    .migrateAuthTypes()
                    .normalizeProviderAuthTypes()
                    .sanitizeAccounts()
            )
        }.onFailure { e ->
            TokenPulseLogger.Settings.error("Failed to load settings, using defaults", e)
        }.getOrDefault(TokenPulseSettings())
    }

    companion object {
        fun getInstance(): TokenPulseSettingsService = service()
    }
}
