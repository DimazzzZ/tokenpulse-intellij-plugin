package org.zhavoronkov.tokenpulse.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.CredentialsStore
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.ui.TokenPulseNotifier

@Service(Service.Level.APP)
class BalanceRefreshService : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val coordinator = RefreshCoordinator(
        scope = scope,
        fetcher = { account ->
            val apiKey = CredentialsStore.getInstance().getApiKey(account.id)
            if (apiKey == null) {
                ProviderResult.Failure.AuthError("Missing API key")
            } else {
                HttpClientService.getInstance().providerRegistry
                    .getClient(account.providerId)
                    .fetchBalance(account, apiKey)
            }
        }
    )

    val results: StateFlow<Map<String, ProviderResult>> = coordinator.results

    private var autoRefreshJob: Job? = null

    init {
        startAutoRefresh()
    }

    fun restartAutoRefresh() {
        autoRefreshJob?.cancel()
        val settings = TokenPulseSettingsService.getInstance().state
        if (!settings.autoRefreshEnabled) return

        autoRefreshJob = scope.launch {
            while (isActive) {
                refreshAll()
                val interval = TokenPulseSettingsService.getInstance().state.refreshIntervalMinutes
                delay(interval.toLong() * SECONDS_PER_MINUTE * MILLIS_PER_SECOND)
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

        val oldResult = results.value[accountId]

        coordinator.refreshAccount(account, force) { newResult ->
            // Publish event via MessageBus
            ApplicationManager.getApplication().messageBus
                .syncPublisher(BalanceUpdatedTopic.TOPIC)
                .balanceUpdated(accountId, newResult)

            // Notify on status change
            handleNotifications(account.name, oldResult, newResult)
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

    private fun startAutoRefresh() {
        restartAutoRefresh()
    }

    companion object {
        private const val SECONDS_PER_MINUTE = 60L
        private const val MILLIS_PER_SECOND = 1000L

        fun getInstance(): BalanceRefreshService = service()
    }
}
