package org.zhavoronkov.tokenpulse.service

import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles the actual refresh logic: TTL caching, single-flight coalescing,
 * and calling the provider clients. Pure Kotlin (no IntelliJ deps) for testability.
 */
class RefreshCoordinator(
    private val scope: CoroutineScope,
    private val fetcher: suspend (Account) -> ProviderResult,
    private val cacheTtl: Duration = Duration.ofSeconds(60),
    private val clock: Clock = Clock.systemUTC()
) {
    private val _results = MutableStateFlow<Map<String, ProviderResult>>(emptyMap())
    val results: StateFlow<Map<String, ProviderResult>> = _results.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun refreshAccount(account: Account, force: Boolean, onResult: (ProviderResult) -> Unit = {}) {
        if (!account.isEnabled) return

        // Single-flight check
        val currentJob = activeJobs[account.id]
        if (currentJob != null && currentJob.isActive && !force) {
            return
        }

        // TTL Cache check
        if (!force) {
            val lastResult = _results.value[account.id]
            if (lastResult is ProviderResult.Success) {
                val age = Duration.between(lastResult.snapshot.timestamp, Instant.now(clock))
                if (age < cacheTtl) {
                    return
                }
            }
        }

        currentJob?.cancel()
        activeJobs[account.id] = scope.launch {
            val result = fetcher(account)
            _results.value = _results.value + (account.id to result)
            onResult(result)
        }
    }
}
