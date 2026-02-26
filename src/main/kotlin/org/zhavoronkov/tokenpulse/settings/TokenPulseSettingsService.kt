package org.zhavoronkov.tokenpulse.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "TokenPulseSettings",
    storages = [Storage("tokenpulse.xml")]
)
class TokenPulseSettingsService : PersistentStateComponent<TokenPulseSettings> {
    private var mySettings = TokenPulseSettings()

    override fun getState(): TokenPulseSettings = mySettings

    override fun loadState(state: TokenPulseSettings) {
        // Migrate legacy OPENROUTER_API_KEY accounts to OPENROUTER_PROVISIONING_KEY
        mySettings = state.copy(accounts = state.accounts.migrateAuthTypes())
    }

    companion object {
        fun getInstance(): TokenPulseSettingsService = service()
    }
}
