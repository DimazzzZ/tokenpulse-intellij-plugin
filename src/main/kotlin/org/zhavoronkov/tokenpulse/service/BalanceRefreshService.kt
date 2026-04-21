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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.CredentialsStore
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.ui.TokenPulseNotifier
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Tracks the last notification time per account+error fingerprint to avoid spamming
     * identical error notifications repeatedly.
     * Key: "accountId:errorType", Value: last notification timestamp.
     */
    private val notificationTracker = ConcurrentHashMap<String, Long>()

    /** Minimum time between identical notifications for the same account (30 minutes). */
    private val notificationThrottleMs = 30 * 60 * 1000L

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
                delay((interval * 60).seconds)
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

        // Handle credential failure cooldowns
        when (newResult) {
            is ProviderResult.Success -> {
                coordinator.clearCredentialCooldown(accountId)
                recordToHistory(newResult)
            }
            is ProviderResult.Failure -> {
                if (isCredentialRelatedFailure(newResult)) {
                    coordinator.recordCredentialFailure(accountId)
                }
            }
        }
    }

    /**
     * Identifies failures that are credential-related and won't self-resolve.
     * These trigger cooldown behavior to reduce notification spam.
     *
     * Only [ProviderResult.Failure.AuthError] is considered credential-related.
     * Other failure types (NetworkError, RateLimited, etc.) are transient and may self-resolve.
     */
    private fun isCredentialRelatedFailure(result: ProviderResult.Failure): Boolean {
        return result is ProviderResult.Failure.AuthError
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
        val fingerprint = "$accountLabel:${new.javaClass.simpleName}:${normalizeErrorMessage(new.message)}"
        val now = System.currentTimeMillis()

        // Check throttle for repeated identical notifications
        val lastNotified = notificationTracker[fingerprint] ?: return

        val elapsedMs = now - lastNotified
        val shouldThrottle = elapsedMs < notificationThrottleMs
        if (shouldThrottle) {
            TokenPulseLogger.Service.debug(
                "Suppressing duplicate notification for $accountLabel: ${new.message}"
            )
            return
        }

        val shouldNotify = old == null || old is ProviderResult.Success ||
            (old is ProviderResult.Failure && old.javaClass != new.javaClass)

        if (shouldNotify) {
            val message = buildErrorMessage(accountLabel, new)
            TokenPulseNotifier.notifyError(null, message)
            notificationTracker[fingerprint] = now
        }
    }

    /**
     * Normalizes error message for fingerprinting by removing variable parts like timestamps.
     */
    private fun normalizeErrorMessage(message: String): String {
        return message
            .lowercase()
            .replace(Regex("\\d+"), "N") // Replace numbers with placeholder
            .trim()
    }

    /**
     * Builds a more actionable error message for credential-related failures.
     */
    private fun buildErrorMessage(accountLabel: String, failure: ProviderResult.Failure): String {
        val baseMessage = "Failed to refresh $accountLabel: ${failure.message}"

        // Add actionable hint for credential issues
        if (isCredentialRelatedFailure(failure)) {
            return "$baseMessage Please re-enter your API key in TokenPulse Settings."
        }

        return baseMessage
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun startAutoRefresh() {
        restartAutoRefresh()
    }

    companion object {
        fun getInstance(): BalanceRefreshService = service()
    }
}
