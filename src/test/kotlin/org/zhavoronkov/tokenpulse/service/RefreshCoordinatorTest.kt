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
import org.zhavoronkov.tokenpulse.model.ConnectionType
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
        connectionType = ConnectionType.CLINE_API,
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
                    BalanceSnapshot("a1", ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
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
                    BalanceSnapshot("a1", ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
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
                    BalanceSnapshot("a1", ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
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
                    BalanceSnapshot("a1", ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
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

    @Test
    fun `test disabled account is skipped`() = runTest {
        val callCount = AtomicInteger(0)
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                callCount.incrementAndGet()
                ProviderResult.Success(
                    BalanceSnapshot("a1", ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
                )
            },
            clock = testClock
        )

        // Create a disabled account
        val disabledAccount = Account(
            id = "a1",
            connectionType = ConnectionType.CLINE_API,
            authType = AuthType.CLINE_API_KEY,
            isEnabled = false
        )

        coordinator.refreshAccount(disabledAccount, force = false)
        advanceUntilIdle()

        assertEquals(0, callCount.get(), "Should not call fetcher for disabled account")
    }

    @Test
    fun `test onResult callback is invoked on success`() = runTest {
        val testClock = TestClock()
        val resultCapture = AtomicReference<ProviderResult?>(null)
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                ProviderResult.Success(
                    BalanceSnapshot("a1", ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
                )
            },
            clock = testClock
        )

        coordinator.refreshAccount(account(), force = false) { result ->
            resultCapture.set(result)
        }
        advanceUntilIdle()

        val captured = resultCapture.get()
        assertEquals(true, captured is ProviderResult.Success, "Callback should receive Success result")
    }

    @Test
    fun `test onResult callback is invoked on failure`() = runTest {
        val testClock = TestClock()
        val resultCapture = AtomicReference<ProviderResult?>(null)
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                ProviderResult.Failure.NetworkError("Connection failed", null)
            },
            clock = testClock
        )

        coordinator.refreshAccount(account(), force = false) { result ->
            resultCapture.set(result)
        }
        advanceUntilIdle()

        val captured = resultCapture.get()
        assertEquals(
            true,
            captured is ProviderResult.Failure.NetworkError,
            "Callback should receive NetworkError result"
        )
    }

    @Test
    fun `test failure result does not cache - next call fetches again`() = runTest {
        val callCount = AtomicInteger(0)
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                callCount.incrementAndGet()
                ProviderResult.Failure.AuthError("Invalid credentials")
            },
            cacheTtl = Duration.ofSeconds(60),
            clock = testClock
        )

        // First refresh - fails
        coordinator.refreshAccount(account(), force = false)
        advanceUntilIdle()
        assertEquals(1, callCount.get())

        // Second refresh - should still call fetcher since failure doesn't cache
        coordinator.refreshAccount(account(), force = false)
        advanceUntilIdle()

        assertEquals(2, callCount.get(), "Failure results should not be cached")
    }

    @Test
    fun `test results state flow contains latest result`() = runTest {
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                ProviderResult.Success(
                    BalanceSnapshot("a1", ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
                )
            },
            clock = testClock
        )

        // Initially empty
        assertEquals(emptyMap<String, ProviderResult>(), coordinator.results.value)

        coordinator.refreshAccount(account(), force = false)
        advanceUntilIdle()

        // Should contain result
        assertEquals(true, coordinator.results.value.containsKey("a1"))
        assertEquals(true, coordinator.results.value["a1"] is ProviderResult.Success)
    }

    @Test
    fun `test pending force refresh queued during active job`() = runTest {
        val callCount = AtomicInteger(0)
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                callCount.incrementAndGet()
                delay(500) // Simulate slow request
                ProviderResult.Success(
                    BalanceSnapshot("a1", ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
                )
            },
            clock = testClock
        )

        // Start first refresh
        launch { coordinator.refreshAccount(account(), force = false) }

        // Queue a force refresh while first is running
        launch { coordinator.refreshAccount(account(), force = true) }

        advanceUntilIdle()

        // First call + queued force refresh = 2 calls
        assertEquals(2, callCount.get(), "Force refresh queued during active job should run after")
    }

    @Test
    fun `test concurrent auto-refresh is skipped while job active`() = runTest {
        val callCount = AtomicInteger(0)
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = {
                callCount.incrementAndGet()
                delay(500)
                ProviderResult.Success(
                    BalanceSnapshot("a1", ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
                )
            },
            clock = testClock
        )

        // Start first refresh
        launch { coordinator.refreshAccount(account(), force = false) }

        // Try auto-refresh (force=false) while first is running
        launch { coordinator.refreshAccount(account(), force = false) }

        advanceUntilIdle()

        // Only first call should execute
        assertEquals(1, callCount.get(), "Auto-refresh during active job should be skipped")
    }

    @Test
    fun `test multiple accounts are handled independently`() = runTest {
        val callCount = AtomicInteger(0)
        val testClock = TestClock()
        val coordinator = RefreshCoordinator(
            scope = this,
            fetcher = { account ->
                callCount.incrementAndGet()
                ProviderResult.Success(
                    BalanceSnapshot(account.id, ConnectionType.CLINE_API, Balance(), timestamp = testClock.instant())
                )
            },
            cacheTtl = Duration.ofSeconds(60),
            clock = testClock
        )

        val account1 = Account(id = "a1", connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val account2 = Account(
            id = "a2",
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )

        coordinator.refreshAccount(account1, force = false)
        coordinator.refreshAccount(account2, force = false)
        advanceUntilIdle()

        assertEquals(2, callCount.get(), "Each account should trigger separate fetch")
        assertEquals(true, coordinator.results.value.containsKey("a1"))
        assertEquals(true, coordinator.results.value.containsKey("a2"))
    }
}
