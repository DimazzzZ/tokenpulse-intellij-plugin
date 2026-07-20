package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit tests for the pure column-value helpers backing [AccountTableColumns].
 * These carry all column decision logic; the ColumnInfo adapters merely look
 * up the live result and delegate here.
 */
class AccountTableColumnsTest {

    private fun claudeAccount(configDir: String?) = Account(
        connectionType = ConnectionType.CLAUDE_CODE,
        authType = AuthType.CLAUDE_CODE_LOCAL,
        claudeConfigDir = configDir
    )

    private fun successResult(
        connectionType: ConnectionType,
        balance: Balance = Balance(),
        metadata: Map<String, String> = emptyMap()
    ) = ProviderResult.Success(
        BalanceSnapshot(
            accountId = "acc-1",
            connectionType = connectionType,
            balance = balance,
            timestamp = Instant.now(),
            metadata = metadata
        )
    )

    // ── keyPreviewValue ──────────────────────────────────────────────────

    @Test
    fun `keyPreviewValue shows default dir for Claude with null config dir`() {
        assertEquals("~/.claude", keyPreviewValue(claudeAccount(null)))
    }

    @Test
    fun `keyPreviewValue shows basename for Claude with custom config dir`() {
        assertEquals("~/.claude-work", keyPreviewValue(claudeAccount("/Users/me/.claude-work")))
    }

    @Test
    fun `keyPreviewValue shows masked preview for non-Claude account`() {
        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY,
            keyPreview = "sk-or-…91bc"
        )
        assertEquals("sk-or-…91bc", keyPreviewValue(account))
    }

    @Test
    fun `keyPreviewValue shows em dash for non-Claude account with empty preview`() {
        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY,
            keyPreview = ""
        )
        assertEquals("—", keyPreviewValue(account))
    }

    // ── statusValue ──────────────────────────────────────────────────────

    @Test
    fun `statusValue null result is Never`() {
        assertEquals("Never", statusValue(null))
    }

    @Test
    fun `statusValue success is OK`() {
        assertEquals("OK", statusValue(successResult(ConnectionType.CLAUDE_CODE)))
    }

    @Test
    fun `statusValue auth error is Auth Error`() {
        assertEquals("Auth Error", statusValue(ProviderResult.Failure.AuthError("nope")))
    }

    @Test
    fun `statusValue rate limited is Rate Limited`() {
        assertEquals("Rate Limited", statusValue(ProviderResult.Failure.RateLimited("slow down")))
    }

    @Test
    fun `statusValue generic failure is Error`() {
        assertEquals("Error", statusValue(ProviderResult.Failure.NetworkError("boom")))
        assertEquals("Error", statusValue(ProviderResult.Failure.UnknownError("mystery")))
    }

    // ── lastUpdatedValue ─────────────────────────────────────────────────

    @Test
    fun `lastUpdatedValue null result is double dash`() {
        assertEquals("--", lastUpdatedValue(null))
    }

    @Test
    fun `lastUpdatedValue non-null result formats a HH mm ss time`() {
        val formatted = lastUpdatedValue(successResult(ConnectionType.CLAUDE_CODE))
        // Format is HH:mm:ss — assert shape, not the wall-clock value.
        assertEquals(true, Regex("""\d{2}:\d{2}:\d{2}""").matches(formatted))
    }

    // ── creditsValue ─────────────────────────────────────────────────────

    @Test
    fun `creditsValue non-success is double dash`() {
        assertEquals("--", creditsValue(null))
        assertEquals("--", creditsValue(ProviderResult.Failure.AuthError("x")))
    }

    @Test
    fun `creditsValue usage-percentage type renders utilization windows`() {
        val result = successResult(
            ConnectionType.CLAUDE_CODE,
            metadata = mapOf("fiveHourUtilization" to "20", "sevenDayUtilization" to "40")
        )
        assertEquals("80% 5h • 60% wk", creditsValue(result))
    }

    @Test
    fun `creditsValue non-usage type delegates to BalanceFormatter`() {
        val balance = Balance(credits = Credits(remaining = BigDecimal("12.50")))
        val result = successResult(ConnectionType.OPENROUTER_PROVISIONING, balance = balance)
        assertEquals(BalanceFormatter.format(balance), creditsValue(result))
    }

    // ── formatUsagePercentage ────────────────────────────────────────────

    @Test
    fun `formatUsagePercentage reads OAuth utilization keys`() {
        val out = formatUsagePercentage(
            mapOf("fiveHourUtilization" to "10", "sevenDayUtilization" to "25")
        )
        assertEquals("90% 5h • 75% wk", out)
    }

    @Test
    fun `formatUsagePercentage reads CLI used keys`() {
        val out = formatUsagePercentage(mapOf("sessionUsed" to "30", "weekUsed" to "60"))
        assertEquals("70% 5h • 40% wk", out)
    }

    @Test
    fun `formatUsagePercentage renders only the present window`() {
        assertEquals("95% 5h", formatUsagePercentage(mapOf("fiveHourUtilization" to "5")))
        assertEquals("50% wk", formatUsagePercentage(mapOf("weekUsed" to "50")))
    }

    @Test
    fun `formatUsagePercentage with no known keys is double dash`() {
        assertEquals("--", formatUsagePercentage(emptyMap()))
        assertEquals("--", formatUsagePercentage(mapOf("unrelated" to "1")))
    }
}
