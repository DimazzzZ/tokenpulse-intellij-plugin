package org.zhavoronkov.tokenpulse.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A deterministic, manually-advanceable clock for tests.
 * Eliminates any dependency on real wall-clock time.
 */
class TestClock(initialTime: Instant = Instant.EPOCH) : Clock() {
    private val current = AtomicReference(initialTime)

    override fun instant(): Instant = current.get()
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this

    fun advance(duration: Duration) {
        current.updateAndGet { it.plus(duration) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {

    private fun account() = Account(
        id = "a1",
        name = "Test",
        providerId = ProviderId.CLINE,
        authType = AuthType.CLINE_API_KEY
    )

    @Test
    fun `test single-flight coalescing`() = runTest {
        val callCount = AtomicInteger(0)
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                callCount.incrementAndGet()
                delay(1000)
                ProviderResult.Success(
                    BalanceSnapshot("a1", ProviderId.CLINE, Balance(), timestamp = testClock.instant())
                )
            },
            clock = testClock
        )

        // Launch multiple refreshes simultaneously – should coalesce to 1 fetcher call
        repeat(3) { launch { coordinator.refreshAccount(account(), force = false) } }
        advanceUntilIdle()

        assertEquals(1, callCount.get(), "Should only call fetcher once for simultaneous requests")
    }

    @Test
    fun `test ttl cache - second call within TTL is skipped`() = runTest {
        val callCount = AtomicInteger(0)
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                callCount.incrementAndGet()
                ProviderResult.Success(
                    BalanceSnapshot("a1", ProviderId.CLINE, Balance(), timestamp = testClock.instant())
                )
            },
            cacheTtl = Duration.ofSeconds(60),
            clock = testClock
        )

        // First refresh
        coordinator.refreshAccount(account(), force = false)
        advanceUntilIdle()

        // Advance by 30 s (still within the 60 s TTL)
        testClock.advance(Duration.ofSeconds(30))

        // Second refresh – cache should still be valid
        coordinator.refreshAccount(account(), force = false)
        advanceUntilIdle()

        assertEquals(1, callCount.get(), "Should use cache and not call fetcher twice within TTL")
    }

    @Test
    fun `test ttl expiry triggers new fetch`() = runTest {
        val callCount = AtomicInteger(0)
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                callCount.incrementAndGet()
                ProviderResult.Success(
                    BalanceSnapshot("a1", ProviderId.CLINE, Balance(), timestamp = testClock.instant())
                )
            },
            cacheTtl = Duration.ofSeconds(60),
            clock = testClock
        )

        // First refresh
        coordinator.refreshAccount(account(), force = false)
        advanceUntilIdle()
        assertEquals(1, callCount.get())

        // Advance clock past the TTL boundary
        testClock.advance(Duration.ofSeconds(61))

        // Second refresh – TTL has expired, fetcher must be called again
        coordinator.refreshAccount(account(), force = false)
        advanceUntilIdle()

        assertEquals(2, callCount.get(), "Should re-fetch after TTL expires")
    }

    @Test
    fun `test forced refresh bypasses ttl`() = runTest {
        val callCount = AtomicInteger(0)
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                callCount.incrementAndGet()
                ProviderResult.Success(
                    BalanceSnapshot("a1", ProviderId.CLINE, Balance(), timestamp = testClock.instant())
                )
            },
            cacheTtl = Duration.ofSeconds(60),
            clock = testClock
        )

        // First refresh
        coordinator.refreshAccount(account(), force = false)
        advanceUntilIdle()

        // Force=true should bypass TTL even though clock hasn't advanced
        coordinator.refreshAccount(account(), force = true)
        advanceUntilIdle()

        assertEquals(2, callCount.get(), "Should bypass cache when force=true")
    }
}
