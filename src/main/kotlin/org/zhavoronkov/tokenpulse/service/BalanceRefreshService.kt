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
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.SessionParser
import org.zhavoronkov.tokenpulse.provider.xiaomi.XiaomiProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import org.zhavoronkov.tokenpulse.settings.CredentialsStore
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.ui.TokenPulseNotifier
import org.zhavoronkov.tokenpulse.utils.Constants
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

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
    private val notificationThrottleMs = Constants.NOTIFICATION_THROTTLE_MS

    init {
        // One-time migration for the Xiaomi provider unification: merge duplicate
        // Xiaomi accounts (same userId). Runs off-EDT on the IO scope because it
        // reads session secrets from PasswordSafe, and completes BEFORE the first
        // refresh so a removed duplicate never gets refreshed.
        scope.launch {
            dedupeXiaomiAccounts()
            startAutoRefresh()
        }
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
            handleNotifications(account.displayLabel(), account.authType, oldResult, newResult)
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

    private fun handleNotifications(
        accountLabel: String,
        authType: AuthType,
        old: ProviderResult?,
        new: ProviderResult
    ) {
        when {
            new is ProviderResult.Failure -> handleFailureNotification(accountLabel, authType, old, new)
            new is ProviderResult.Success && old is ProviderResult.Failure ->
                TokenPulseNotifier.notifyInfo(null, "Account $accountLabel is back online.")
        }
    }

    private fun handleFailureNotification(
        accountLabel: String,
        authType: AuthType,
        old: ProviderResult?,
        new: ProviderResult.Failure
    ) {
        val fingerprint = "$accountLabel:${new.javaClass.simpleName}:${normalizeErrorMessage(new.message)}"
        val now = System.currentTimeMillis()

        val shouldNotify = old == null || old is ProviderResult.Success ||
            (old is ProviderResult.Failure && old.javaClass != new.javaClass)

        // Check throttle for repeated identical notifications
        val lastNotified = notificationTracker[fingerprint]
        if (lastNotified != null) {
            val elapsedMs = now - lastNotified
            if (elapsedMs < notificationThrottleMs) {
                TokenPulseLogger.Service.debug(
                    "Suppressing duplicate notification for $accountLabel: ${new.message}"
                )
                return
            }
        }

        if (shouldNotify) {
            val message = composeNotificationMessage(accountLabel, new, authType)
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

    override fun dispose() {
        scope.cancel()
    }

    /**
     * One-time migration: collapse duplicate unified-Xiaomi accounts that share
     * the same Xiaomi `userId` into a single account.
     *
     * Before this session-unification work, a user could add both a
     * pay-as-you-go and a Token Plan account for the SAME Xiaomi login. Post
     * migration both are [ConnectionType.XIAOMI] and each shows both balances,
     * so the second is redundant. We keep one survivor per userId and remove the
     * rest (account + PasswordSafe secret).
     *
     * Safety:
     * - Runs at most once, guarded by [TokenPulseSettings.xiaomiDedupeDone].
     * - Reads secrets on the IO scope (never the EDT).
     * - DATA-LOSS GUARD: any account whose secret is missing/unparseable or
     *   whose userId is blank is left untouched and never merged.
     * - Survivor preference: an account whose session carries a `passToken`
     *   (silent-refresh capable) wins; ties break by lowest id for determinism.
     */
    private fun dedupeXiaomiAccounts() {
        val settingsService = TokenPulseSettingsService.getInstance()
        if (settingsService.state.xiaomiDedupeDone) return

        val credentials = CredentialsStore.getInstance()
        val accounts = settingsService.state.accounts
        val xiaomiAccounts = accounts.filter { it.connectionType == ConnectionType.XIAOMI }

        // Group by userId; only accounts with a readable, parseable session that
        // yields a non-blank userId are eligible for merging.
        val byUserId = xiaomiAccounts
            .mapNotNull { account ->
                val session = credentials.getApiKey(account.id)?.let { parseXiaomiSession(it) }
                val userId = session?.userId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Triple(account, userId, session)
            }
            .groupBy { it.second }

        val idsToRemove = mutableListOf<String>()
        byUserId.values.filter { it.size > 1 }.forEach { group ->
            val sorted = group.sortedWith(
                compareByDescending<Triple<Account, String, XiaomiProviderClient.XiaomiSession>> {
                    !it.third.passToken.isNullOrBlank()
                }.thenBy { it.first.id }
            )
            // Keep the first (survivor); mark the rest for removal.
            sorted.drop(1).forEach { idsToRemove.add(it.first.id) }
        }

        if (idsToRemove.isEmpty()) {
            settingsService.state.xiaomiDedupeDone = true
            return
        }

        TokenPulseLogger.Service.info("Xiaomi dedup: merging ${idsToRemove.size} duplicate account(s)")
        idsToRemove.forEach { credentials.removeApiKey(it) }
        settingsService.state.accounts = accounts.filter { it.id !in idsToRemove }
        settingsService.state.xiaomiDedupeDone = true

        refreshAll(force = true)
    }

    private fun parseXiaomiSession(secret: String): XiaomiProviderClient.XiaomiSession? =
        SessionParser.parse(
            secret = secret,
            sessionClass = XiaomiProviderClient.XiaomiSession::class.java,
            validator = { !it.serviceToken.isNullOrBlank() },
            providerName = "Xiaomi",
            gson = com.google.gson.Gson()
        )

    private fun startAutoRefresh() {
        restartAutoRefresh()
    }

    companion object {
        fun getInstance(): BalanceRefreshService = service()
    }
}

/**
 * Composes the final user-facing notification message for a failed refresh.
 *
 * Appends "Please re-enter your API key in TokenPulse Settings." ONLY for auth
 * types where the user actually manages an API key in Settings. OAuth / CLI /
 * session-backed auth types have provider messages that are already actionable
 * (e.g. "run `claude` to re-authenticate"), so no generic hint is appended —
 * otherwise the two CTAs would contradict each other.
 *
 * Top-level pure function so it is unit-testable without instantiating the
 * `@Service` (whose init block needs a live IDE application).
 */
internal fun composeNotificationMessage(
    accountLabel: String,
    failure: ProviderResult.Failure,
    authType: AuthType
): String {
    val baseMessage = "Failed to refresh $accountLabel: ${failure.message}"
    return if (failure is ProviderResult.Failure.AuthError && isApiKeyAuth(authType)) {
        "$baseMessage Please re-enter your API key in TokenPulse Settings."
    } else {
        baseMessage
    }
}

/**
 * Allowlist of auth types where the user directly enters/pastes an API key in
 * TokenPulse Settings. Anything else (OAuth flows, CLI-local sessions, browser
 * session capture, plugin bridges) is handled outside Settings and must NOT
 * trigger the "re-enter your API key" hint.
 */
internal fun isApiKeyAuth(authType: AuthType): Boolean = when (authType) {
    AuthType.CLINE_API_KEY,
    AuthType.OPENAI_API_KEY,
    AuthType.XIAOMI_API_KEY,
    AuthType.XIAOMI_TOKEN_PLAN_KEY,
    AuthType.OPENROUTER_PROVISIONING_KEY -> true
    AuthType.CLAUDE_CODE_LOCAL,
    AuthType.CODEX_CLI_LOCAL,
    AuthType.OPENAI_OAUTH,
    AuthType.NEBIUS_BILLING_SESSION,
    AuthType.XIAOMI_SESSION,
    AuthType.OPENROUTER_PLUGIN_BRIDGE -> false
}
