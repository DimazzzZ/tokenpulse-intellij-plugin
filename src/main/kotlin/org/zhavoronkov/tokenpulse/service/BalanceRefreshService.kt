package org.zhavoronkov.tokenpulse.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
    
    // accountId -> last ProviderResult
    private val _results = MutableStateFlow<Map<String, ProviderResult>>(emptyMap())
    val results: StateFlow<Map<String, ProviderResult>> = _results.asStateFlow()

    private val refreshJobs = ConcurrentHashMap<String, Job>()
    private var autoRefreshJob: Job? = null

    init {
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = scope.launch {
            while (isActive) {
                val interval = TokenPulseSettingsService.getInstance().state.refreshIntervalMinutes
                refreshAll()
                delay(interval * 60 * 1000L)
            }
        }
    }

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
            
            val result = client.fetchBalance(account, apiKey)
            _results.value = _results.value + (accountId to result)
            
            // Publish event via MessageBus
            ApplicationManager.getApplication().messageBus
                .syncPublisher(BalanceUpdatedTopic.TOPIC)
                .balanceUpdated(accountId, result)
            
            if (result is ProviderResult.Failure) {
                // TODO: Better logging/notification
                println("Error refreshing account ${account.name}: ${result.message}")
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
