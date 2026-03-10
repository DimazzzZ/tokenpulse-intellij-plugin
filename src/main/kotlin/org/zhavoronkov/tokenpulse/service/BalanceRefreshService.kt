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
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

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
                    .getClient(account.connectionType)
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

        // Always force refresh when settings change (accounts may have been updated)
        TokenPulseLogger.Service.info("restartAutoRefresh called, forcing immediate refresh")
        refreshAll(force = true)

        if (!settings.autoRefreshEnabled) return

        autoRefreshJob = scope.launch {
            while (isActive) {
                val interval = TokenPulseSettingsService.getInstance().state.refreshIntervalMinutes
                delay(interval.toLong() * SECONDS_PER_MINUTE * MILLIS_PER_SECOND)
                refreshAll()
            }
        }
    }

    fun refreshAll(force: Boolean = false) {
        TokenPulseLogger.Service.debug("Starting refreshAll (force=$force)")
        val accounts = TokenPulseSettingsService.getInstance().state.accounts
        val enabledCount = accounts.count { it.isEnabled }
        TokenPulseLogger.Service.debug("Found $enabledCount enabled accounts to refresh")
        accounts.filter { it.isEnabled }.forEach { account ->
            refreshAccount(account.id, force)
        }
    }

    fun refreshAccount(accountId: String, force: Boolean = false) {
        TokenPulseLogger.Service.debug("refreshAccount called: accountId=$accountId, force=$force")
        val account = TokenPulseSettingsService.getInstance().state.accounts.find { it.id == accountId }
            ?: return

        val oldResult = results.value[accountId]

        coordinator.refreshAccount(account, force) { newResult ->
            publishBalanceUpdate(accountId, newResult)
            logRefreshResult(accountId, newResult)
            handleNotifications(account.displayLabel(), oldResult, newResult)
        }
    }

    private fun publishBalanceUpdate(accountId: String, newResult: ProviderResult) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(BalanceUpdatedTopic.TOPIC)
            .balanceUpdated(accountId, newResult)

        // Record successful results to history for charting
        if (newResult is ProviderResult.Success) {
            recordToHistory(newResult)
        }
    }

    private fun recordToHistory(result: ProviderResult.Success) {
        try {
            BalanceHistoryService.getInstance().recordSnapshot(result.snapshot)
            TokenPulseLogger.Service.debug(
                "Recorded balance history for account: ${result.snapshot.accountId}"
            )
        } catch (e: Exception) {
            TokenPulseLogger.Service.warn(
                "Failed to record balance history: ${e.message}"
            )
        }
    }

    private fun logRefreshResult(accountId: String, newResult: ProviderResult) {
        TokenPulseLogger.Service.debug(
            "refreshAccount completed: accountId=$accountId, result=${newResult::class.simpleName}"
        )
    }

    private fun handleNotifications(accountLabel: String, old: ProviderResult?, new: ProviderResult) {
        when {
            new is ProviderResult.Failure -> handleFailureNotification(accountLabel, old, new)
            new is ProviderResult.Success && old is ProviderResult.Failure ->
                TokenPulseNotifier.notifyInfo(null, "Account $accountLabel is back online.")
        }
    }

    private fun handleFailureNotification(
        accountLabel: String,
        old: ProviderResult?,
        new: ProviderResult.Failure
    ) {
        val shouldNotify = old == null || old is ProviderResult.Success ||
            (old is ProviderResult.Failure && old.javaClass != new.javaClass)

        if (shouldNotify) {
            val message = "Failed to refresh $accountLabel: ${new.message}"
            TokenPulseNotifier.notifyError(null, message)
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
