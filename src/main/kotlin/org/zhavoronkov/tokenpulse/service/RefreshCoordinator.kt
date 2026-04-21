package org.zhavoronkov.tokenpulse.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_CACHE_TTL_SECONDS = 60L

/**
 * Cooldown configuration for deterministic failures (e.g., missing credentials).
 * These failures won't self-resolve without user action, so we back off aggressively.
 */
private const val CREDENTIAL_FAILURE_INITIAL_COOLDOWN_SECONDS = 300L // 5 minutes
private const val CREDENTIAL_FAILURE_MAX_COOLDOWN_SECONDS = 3600L // 1 hour

/**
 * Handles the actual refresh logic: TTL caching, single-flight coalescing,
 * and calling the provider clients. Pure Kotlin (no IntelliJ deps) for testability.
 */
class RefreshCoordinator(
    private val scope: CoroutineScope,
    private val fetcher: suspend (Account) -> ProviderResult,
    private val cacheTtl: Duration = Duration.ofSeconds(DEFAULT_CACHE_TTL_SECONDS),
    private val clock: Clock = Clock.systemUTC()
) {
    private val _results = MutableStateFlow<Map<String, ProviderResult>>(emptyMap())
    val results: StateFlow<Map<String, ProviderResult>> = _results.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pendingForcedRefresh = ConcurrentHashMap<String, Boolean>()

    /**
     * Tracks the last failure time and cooldown duration for accounts with credential-related failures.
     * These failures are deterministic and won't self-resolve without user action.
     */
    private val credentialFailureCooldowns = ConcurrentHashMap<String, CredentialFailureState>()

    @Suppress("ReturnCount")
    fun refreshAccount(account: Account, force: Boolean, onResult: (ProviderResult) -> Unit = {}) {
        if (!account.isEnabled) return

        val currentJob = activeJobs[account.id]
        if (isJobRunning(currentJob)) {
            if (force) {
                org.zhavoronkov.tokenpulse.utils.TokenPulseLogger.Service.debug(
                    "Refresh in progress for ${account.id}, queuing pending force-refresh"
                )
                pendingForcedRefresh[account.id] = true
            } else {
                org.zhavoronkov.tokenpulse.utils.TokenPulseLogger.Service.debug(
                    "Refresh in progress for ${account.id}, skipping auto-refresh"
                )
            }
            return
        }

        if (!force && shouldSkipRefresh(account.id)) {
            org.zhavoronkov.tokenpulse.utils.TokenPulseLogger.Service.debug(
                "Refresh skipped for account ${account.id} due to TTL"
            )
            return
        }

        executeRefresh(account, onResult)
    }

    private fun executeRefresh(
        account: Account,
        onResult: (ProviderResult) -> Unit
    ) {
        activeJobs[account.id] = scope.launch {
            try {
                org.zhavoronkov.tokenpulse.utils.TokenPulseLogger.Service.debug(
                    "Starting refresh execution for account ${account.id}"
                )
                val result = fetcher(account)
                _results.value = _results.value + (account.id to result)
                onResult(result)
            } finally {
                activeJobs.remove(account.id)
                if (pendingForcedRefresh.remove(account.id) == true) {
                    org.zhavoronkov.tokenpulse.utils.TokenPulseLogger.Service.debug(
                        "Running queued pending force-refresh for ${account.id}"
                    )
                    refreshAccount(account, force = true, onResult = onResult)
                }
            }
        }
    }

    private fun isJobRunning(job: Job?): Boolean = job?.isActive == true

    private fun shouldSkipRefresh(accountId: String): Boolean {
        val lastResult = _results.value[accountId]
        if (lastResult is ProviderResult.Success) {
            val age = Duration.between(lastResult.snapshot.timestamp, Instant.now(clock))
            return age < cacheTtl
        }

        // Check if we're in cooldown for credential failures
        if (isInCredentialCooldown(accountId)) {
            org.zhavoronkov.tokenpulse.utils.TokenPulseLogger.Service.debug(
                "Refresh skipped for account $accountId: in credential failure cooldown"
            )
            return true
        }

        return false
    }

    /**
     * Checks if an account is currently in credential failure cooldown.
     */
    private fun isInCredentialCooldown(accountId: String): Boolean {
        val state = credentialFailureCooldowns[accountId] ?: return false
        val elapsedSeconds = java.time.Duration.between(state.lastFailureTime, Instant.now(clock)).toSeconds()
        return elapsedSeconds < state.currentCooldown
    }

    /**
     * Records a credential failure and sets the next cooldown duration.
     * Uses exponential backoff: 5m -> 10m -> 20m -> 40m -> 60m (max)
     */
    fun recordCredentialFailure(accountId: String) {
        val now = Instant.now(clock)
        val existing = credentialFailureCooldowns[accountId]

        val nextCooldown = if (existing == null) {
            CREDENTIAL_FAILURE_INITIAL_COOLDOWN_SECONDS
        } else {
            // Exponential backoff: double the previous cooldown, capped at max
            (existing.currentCooldown * 2).coerceAtMost(CREDENTIAL_FAILURE_MAX_COOLDOWN_SECONDS)
        }

        credentialFailureCooldowns[accountId] = CredentialFailureState(
            lastFailureTime = now,
            currentCooldown = nextCooldown
        )

        org.zhavoronkov.tokenpulse.utils.TokenPulseLogger.Service.debug(
            "Recorded credential failure for $accountId: cooldown set to ${nextCooldown}s"
        )
    }

    /**
     * Clears any credential failure cooldown for an account (called on success).
     */
    fun clearCredentialCooldown(accountId: String) {
        credentialFailureCooldowns.remove(accountId)?.let {
            org.zhavoronkov.tokenpulse.utils.TokenPulseLogger.Service.debug(
                "Cleared credential cooldown for $accountId"
            )
        }
    }

    /**
     * State tracking for credential failure cooldowns.
     */
    private data class CredentialFailureState(
        val lastFailureTime: Instant,
        val currentCooldown: Long // in seconds
    )
}
