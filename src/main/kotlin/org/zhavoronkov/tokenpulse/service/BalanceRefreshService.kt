package org.zhavoronkov.tokenpulse.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.ProviderFactory
import org.zhavoronkov.tokenpulse.settings.CredentialsStore
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class BalanceRefreshService : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshots = MutableStateFlow<Map<String, BalanceSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, BalanceSnapshot>> = _snapshots.asStateFlow()

    private val refreshJobs = ConcurrentHashMap<String, Job>()

    fun refreshAll() {
        val accounts = TokenPulseSettingsService.getInstance().state.accounts
        accounts.filter { it.isEnabled }.forEach { account ->
            refreshAccount(account.id)
        }
    }

    fun refreshAccount(accountId: String) {
        val account = TokenPulseSettingsService.getInstance().state.accounts.find { it.id == accountId } ?: return
        if (!account.isEnabled) return

        refreshJobs[accountId]?.cancel()
        refreshJobs[accountId] = scope.launch {
            val apiKey = CredentialsStore.getInstance().getApiKey(accountId) ?: return@launch
            val client = ProviderFactory.getClient(account.providerId)
            
            when (val result = client.fetchBalance(accountId, apiKey)) {
                is ProviderResult.Success -> {
                    _snapshots.value = _snapshots.value + (accountId to result.snapshot)
                }
                is ProviderResult.Error -> {
                    // TODO: Log error or notify user
                    println("Error refreshing account ${account.name}: ${result.message}")
                }
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(): BalanceRefreshService = service()
    }
}
