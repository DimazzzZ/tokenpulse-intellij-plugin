package org.zhavoronkov.tokenpulse.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class BalanceHistoryEntryTest {

    private val fixedTimestamp = Instant.parse("2025-01-15T12:00:00Z")

    private fun snapshot(
        connectionType: ConnectionType,
        balance: Balance = Balance(),
        metadata: Map<String, String> = emptyMap()
    ) = BalanceSnapshot(
        accountId = "acc-1",
        connectionType = connectionType,
        balance = balance,
        timestamp = fixedTimestamp,
        metadata = metadata
    )

    @Test
    fun `fromSnapshot with percentage provider uses fiveHourUsed metadata key`() {
        val snap = snapshot(
            ConnectionType.CODEX_CLI,
            metadata = mapOf("fiveHourUsed" to "45")
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(55.0, entry.percentageRemaining, 0.01)
        assertEquals("55%", entry.rawValue)
    }

    @Test
    fun `fromSnapshot with percentage provider uses weeklyUsed metadata key`() {
        val snap = snapshot(
            ConnectionType.CODEX_CLI,
            metadata = mapOf("weeklyUsed" to "20")
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(80.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with percentage provider uses sessionUsed metadata key`() {
        val snap = snapshot(
            ConnectionType.CLAUDE_CODE,
            metadata = mapOf("sessionUsed" to "75")
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(25.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with percentage provider uses weekUsed metadata key`() {
        val snap = snapshot(
            ConnectionType.CLAUDE_CODE,
            metadata = mapOf("weekUsed" to "30")
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(70.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with percentage provider returns 100 minus used as remaining`() {
        val snap = snapshot(
            ConnectionType.CODEX_CLI,
            metadata = mapOf("fiveHourUsed" to "10")
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(90.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with percentage provider clamps to 0-100 range`() {
        val snap = snapshot(
            ConnectionType.CODEX_CLI,
            metadata = mapOf("fiveHourUsed" to "150")
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(0.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with percentage provider clamps negative to 0`() {
        val snap = snapshot(
            ConnectionType.CODEX_CLI,
            metadata = mapOf("fiveHourUsed" to "-10")
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(100.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with percentage provider handles percentage sign in value`() {
        val snap = snapshot(
            ConnectionType.CODEX_CLI,
            metadata = mapOf("fiveHourUsed" to "85%")
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(15.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with percentage provider falls back to token calculation`() {
        val snap = snapshot(
            ConnectionType.CODEX_CLI,
            balance = Balance(tokens = Tokens(used = 50000, total = 100000, remaining = 50000))
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(50.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with percentage provider returns 100 when no data at all`() {
        val snap = snapshot(ConnectionType.CODEX_CLI)
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(100.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with dollar provider and remaining plus total`() {
        val snap = snapshot(
            ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance(credits = Credits(remaining = BigDecimal(75), total = BigDecimal(100)))
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(75.0, entry.percentageRemaining, 0.01)
        assertEquals("$75.00", entry.rawValue)
        assertEquals("of $100.00", entry.rawUnit)
    }

    @Test
    fun `fromSnapshot with dollar provider and remaining plus used no total`() {
        val snap = snapshot(
            ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance(credits = Credits(remaining = BigDecimal(60), used = BigDecimal(40)))
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(60.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with dollar provider and remaining plus used where both are zero`() {
        val snap = snapshot(
            ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance(credits = Credits(remaining = BigDecimal.ZERO, used = BigDecimal.ZERO))
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(100.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with dollar provider and remaining plus maxSeenBalance`() {
        val snap = snapshot(
            ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance(credits = Credits(remaining = BigDecimal(50)))
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, BigDecimal(200))
        assertEquals(25.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with dollar provider and only remaining`() {
        val snap = snapshot(
            ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance(credits = Credits(remaining = BigDecimal(100)))
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(100.0, entry.percentageRemaining, 0.01)
    }

    @Test
    fun `fromSnapshot with no credits data returns zero`() {
        val snap = snapshot(ConnectionType.OPENROUTER_PROVISIONING)
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(0.0, entry.percentageRemaining, 0.01)
        assertEquals("N/A", entry.rawValue)
    }

    @Test
    fun `fromSnapshot prioritizes fiveHourUsed over weeklyUsed`() {
        val snap = snapshot(
            ConnectionType.CODEX_CLI,
            metadata = mapOf("fiveHourUsed" to "45", "weeklyUsed" to "20")
        )
        val entry = BalanceHistoryEntry.fromSnapshot(snap, null)
        assertEquals(55.0, entry.percentageRemaining, 0.01)
    }
}
