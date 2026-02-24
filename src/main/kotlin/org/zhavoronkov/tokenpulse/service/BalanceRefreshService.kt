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
import org.zhavoronkov.tokenpulse.ui.TokenPulseNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class BalanceRefreshService : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // accountId -> last ProviderResult
    private val _results = MutableStateFlow<Map<String, ProviderResult>>(emptyMap())
    val results: StateFlow<Map<String, ProviderResult>> = _results.asStateFlow()

    private val refreshJobs = ConcurrentHashMap<String, Job>()
    private var autoRefreshJob: Job? = null

    private val CACHE_TTL = Duration.ofSeconds(60)

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

    fun refreshAll(force: Boolean = false) {
        val accounts = TokenPulseSettingsService.getInstance().state.accounts
        accounts.filter { it.isEnabled }.forEach { account ->
            refreshAccount(account.id, force)
        }
    }

    fun refreshAccount(accountId: String, force: Boolean = false) {
        val account = TokenPulseSettingsService.getInstance().state.accounts.find { it.id == accountId } ?: return
        if (!account.isEnabled) return

        // Single-flight check: if already running, just wait for it (unless forced, but even then better to let current finish if possible or cancel)
        val currentJob = refreshJobs[accountId]
        if (currentJob != null && currentJob.isActive && !force) {
            return
        }

        // TTL Cache check
        if (!force) {
            val lastResult = _results.value[accountId]
            if (lastResult is ProviderResult.Success) {
                val age = Duration.between(lastResult.snapshot.timestamp, Instant.now())
                if (age < CACHE_TTL) {
                    return
                }
            }
        }

        currentJob?.cancel()
        refreshJobs[accountId] = scope.launch {
            val apiKey = CredentialsStore.getInstance().getApiKey(accountId) ?: return@launch
            val client = ProviderFactory.getClient(account.providerId)
            
            val result = client.fetchBalance(account, apiKey)
            val oldResult = _results.value[accountId]
            
            _results.value = _results.value + (accountId to result)
            
            // Publish event via MessageBus
            ApplicationManager.getApplication().messageBus
                .syncPublisher(BalanceUpdatedTopic.TOPIC)
                .balanceUpdated(accountId, result)
            
            // Notify on status change (Success -> Failure, or Failure type change)
            handleNotifications(account.name, oldResult, result)
        }
    }

    private fun handleNotifications(accountName: String, old: ProviderResult?, new: ProviderResult) {
        if (new is ProviderResult.Failure) {
            val shouldNotify = old == null || old is ProviderResult.Success || 
                             (old is ProviderResult.Failure && old.javaClass != new.javaClass)
            
            if (shouldNotify) {
                val message = "Failed to refresh $accountName: ${new.message}"
                TokenPulseNotifier.notifyError(null, message)
            }
        } else if (new is ProviderResult.Success && old is ProviderResult.Failure) {
            TokenPulseNotifier.notifyInfo(null, "Account $accountName is back online.")
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(): BalanceRefreshService = service()
    }
}
