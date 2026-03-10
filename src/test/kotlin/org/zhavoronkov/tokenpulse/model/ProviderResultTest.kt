package org.zhavoronkov.tokenpulse.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Tests for [ProviderResult] sealed class hierarchy.
 */
class ProviderResultTest {

    @Test
    fun `Success holds snapshot`() {
        val snapshot = createTestSnapshot()
        val result = ProviderResult.Success(snapshot)

        assertEquals(snapshot, result.snapshot)
    }

    @Test
    fun `Success has timestamp`() {
        val beforeTest = Instant.now()
        val result = ProviderResult.Success(createTestSnapshot())
        val afterTest = Instant.now()

        assertTrue(result.timestamp >= beforeTest)
        assertTrue(result.timestamp <= afterTest)
    }

    @Test
    fun `AuthError has message`() {
        val result = ProviderResult.Failure.AuthError("Invalid API key")
        assertEquals("Invalid API key", result.message)
        assertEquals("Invalid API key", result.msg)
    }

    @Test
    fun `RateLimited has message`() {
        val result = ProviderResult.Failure.RateLimited("Too many requests")
        assertEquals("Too many requests", result.message)
    }

    @Test
    fun `NetworkError has message and cause`() {
        val cause = RuntimeException("Connection refused")
        val result = ProviderResult.Failure.NetworkError("Network error", cause)

        assertEquals("Network error", result.message)
        assertEquals(cause, result.throwable)
        assertEquals(cause, result.cause)
    }

    @Test
    fun `NetworkError allows null cause`() {
        val result = ProviderResult.Failure.NetworkError("Network error", null)
        assertNull(result.throwable)
    }

    @Test
    fun `ParseError has message and cause`() {
        val cause = IllegalArgumentException("Bad JSON")
        val result = ProviderResult.Failure.ParseError("Parse failed", cause)

        assertEquals("Parse failed", result.message)
        assertEquals(cause, result.cause)
    }

    @Test
    fun `UnknownError has message and cause`() {
        val cause = Exception("Something went wrong")
        val result = ProviderResult.Failure.UnknownError("Unknown", cause)

        assertEquals("Unknown", result.message)
        assertEquals(cause, result.cause)
    }

    @Test
    fun `Failure subtypes can be used in when expressions`() {
        val results = listOf(
            ProviderResult.Success(createTestSnapshot()),
            ProviderResult.Failure.AuthError("Auth"),
            ProviderResult.Failure.RateLimited("Rate"),
            ProviderResult.Failure.NetworkError("Net", null),
            ProviderResult.Failure.ParseError("Parse", null),
            ProviderResult.Failure.UnknownError("Unknown", null)
        )

        results.forEach { result ->
            val status = when (result) {
                is ProviderResult.Success -> "OK"
                is ProviderResult.Failure.AuthError -> "Auth Error"
                is ProviderResult.Failure.RateLimited -> "Rate Limited"
                is ProviderResult.Failure.NetworkError -> "Network Error"
                is ProviderResult.Failure.ParseError -> "Parse Error"
                is ProviderResult.Failure.UnknownError -> "Unknown Error"
            }
            assertTrue(status.isNotBlank())
        }
    }

    private fun createTestSnapshot(): BalanceSnapshot = BalanceSnapshot(
        accountId = "test-id",
        connectionType = ConnectionType.OPENROUTER_PROVISIONING,
        balance = Balance(
            credits = Credits(remaining = BigDecimal("10.50"))
        )
    )
}

/**
 * Tests for [BalanceSnapshot] data class.
 */
class BalanceSnapshotTest {

    @Test
    fun `BalanceSnapshot has default timestamp`() {
        val beforeTest = Instant.now()
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance()
        )
        val afterTest = Instant.now()

        assertTrue(snapshot.timestamp >= beforeTest)
        assertTrue(snapshot.timestamp <= afterTest)
    }

    @Test
    fun `BalanceSnapshot can have custom timestamp`() {
        val customTime = Instant.parse("2024-01-15T10:00:00Z")
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance(),
            timestamp = customTime
        )

        assertEquals(customTime, snapshot.timestamp)
    }

    @Test
    fun `BalanceSnapshot has empty metadata by default`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance()
        )

        assertTrue(snapshot.metadata.isEmpty())
    }

    @Test
    fun `BalanceSnapshot can have metadata`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.CHATGPT_SUBSCRIPTION,
            balance = Balance(),
            metadata = mapOf(
                "email" to "user@example.com",
                "planType" to "plus"
            )
        )

        assertEquals("user@example.com", snapshot.metadata["email"])
        assertEquals("plus", snapshot.metadata["planType"])
    }

    @Test
    fun `BalanceSnapshot nebiusBreakdown is null by default`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.NEBIUS_BILLING,
            balance = Balance()
        )

        assertNull(snapshot.nebiusBreakdown)
    }

    @Test
    fun `BalanceSnapshot can have nebiusBreakdown`() {
        val breakdown = NebiusBalanceBreakdown(
            paidRemaining = BigDecimal("50.00"),
            trialRemaining = BigDecimal("10.00"),
            tenantName = "My Workspace"
        )

        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.NEBIUS_BILLING,
            balance = Balance(),
            nebiusBreakdown = breakdown
        )

        assertNotNull(snapshot.nebiusBreakdown)
        assertEquals(BigDecimal("50.00"), snapshot.nebiusBreakdown?.paidRemaining)
        assertEquals(BigDecimal("10.00"), snapshot.nebiusBreakdown?.trialRemaining)
        assertEquals("My Workspace", snapshot.nebiusBreakdown?.tenantName)
    }
}

/**
 * Tests for [NebiusBalanceBreakdown] data class.
 */
class NebiusBalanceBreakdownTest {

    @Test
    fun `NebiusBalanceBreakdown has default nulls`() {
        val breakdown = NebiusBalanceBreakdown()

        assertNull(breakdown.paidRemaining)
        assertNull(breakdown.trialRemaining)
        assertNull(breakdown.tenantName)
    }

    @Test
    fun `NebiusBalanceBreakdown holds values`() {
        val breakdown = NebiusBalanceBreakdown(
            paidRemaining = BigDecimal("100.50"),
            trialRemaining = BigDecimal("25.00"),
            tenantName = "Test Tenant"
        )

        assertEquals(BigDecimal("100.50"), breakdown.paidRemaining)
        assertEquals(BigDecimal("25.00"), breakdown.trialRemaining)
        assertEquals("Test Tenant", breakdown.tenantName)
    }
}
