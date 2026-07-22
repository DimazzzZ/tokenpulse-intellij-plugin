package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.Provider
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.settings.StatusBarDollarFormat
import org.zhavoronkov.tokenpulse.settings.StatusBarFormat
import java.math.BigDecimal
import java.time.Instant

class BalanceFormatterTest {

    private val fixedTimestamp = Instant.parse("2025-01-15T12:00:00Z")

    private fun snapshot(
        connectionType: ConnectionType,
        balance: Balance = Balance(),
        metadata: Map<String, String> = emptyMap()
    ) = BalanceSnapshot(
        accountId = "a1",
        connectionType = connectionType,
        balance = balance,
        timestamp = fixedTimestamp,
        metadata = metadata
    )

    private fun successResult(
        connectionType: ConnectionType,
        balance: Balance = Balance(),
        metadata: Map<String, String> = emptyMap()
    ) = ProviderResult.Success(snapshot(connectionType, balance, metadata))

    @Test
    fun `format returns No data when balance is empty`() {
        val balance = Balance()
        assertEquals("No data", BalanceFormatter.format(balance))
    }

    @Test
    fun `format returns credits only when no tokens`() {
        val balance = Balance(credits = Credits(remaining = BigDecimal("10.50")))
        assertEquals("\$10.50", BalanceFormatter.format(balance))
    }

    @Test
    fun `format returns tokens only when no credits`() {
        val balance = Balance(tokens = Tokens(used = 1500L))
        assertEquals("1,500 used", BalanceFormatter.format(balance))
    }

    @Test
    fun `format returns both credits and tokens`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("25.00")),
            tokens = Tokens(used = 5000L)
        )
        assertEquals("\$25.00 (5,000 used)", BalanceFormatter.format(balance))
    }

    @Test
    fun `format credits uses used when remaining is null`() {
        val balance = Balance(credits = Credits(used = BigDecimal("5.25")))
        assertEquals("\$5.25", BalanceFormatter.format(balance))
    }

    @Test
    fun `format credits uses total as fallback`() {
        val balance = Balance(credits = Credits(total = BigDecimal("100.00")))
        assertEquals("\$100.00", BalanceFormatter.format(balance))
    }

    @Test
    fun `format tokens uses remaining when available`() {
        val balance = Balance(tokens = Tokens(remaining = 50000L))
        assertEquals("50,000", BalanceFormatter.format(balance))
    }

    @Test
    fun `format tokens uses total as fallback`() {
        val balance = Balance(tokens = Tokens(total = 100000L))
        assertEquals("100,000", BalanceFormatter.format(balance))
    }

    @Test
    fun `format handles large numbers with formatting`() {
        val balance = Balance(tokens = Tokens(used = 1_234_567L))
        assertEquals("1,234,567 used", BalanceFormatter.format(balance))
    }

    @Test
    fun `format rounds credits to 2 decimal places`() {
        val balance = Balance(credits = Credits(remaining = BigDecimal("10.555")))
        assertEquals("\$10.56", BalanceFormatter.format(balance))
    }

    @Test
    fun `format handles zero values`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal.ZERO),
            tokens = Tokens(used = 0L)
        )
        assertEquals("\$0.00 (0 used)", BalanceFormatter.format(balance))
    }

    @Test
    fun `formatDetailed returns dashes when nothing to show`() {
        val balance = Balance()
        assertEquals("--", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = true))
    }

    @Test
    fun `formatDetailed shows only credits when showTokens is false`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("50.00")),
            tokens = Tokens(used = 1000L)
        )
        assertEquals("\$50.00", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = false))
    }

    @Test
    fun `formatDetailed shows only tokens when showCredits is false`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("50.00")),
            tokens = Tokens(used = 1000L)
        )
        assertEquals("1,000 used", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true))
    }

    @Test
    fun `formatDetailed shows both with plus separator`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("50.00")),
            tokens = Tokens(used = 1000L)
        )
        assertEquals(
            "\$50.00 + 1,000 used",
            BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = true)
        )
    }

    @Test
    fun `formatDetailed shows tokens with total when available`() {
        val balance = Balance(
            tokens = Tokens(used = 5000L, total = 10000L)
        )
        assertEquals(
            "5,000 used/ 10,000",
            BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true)
        )
    }

    @Test
    fun `format handles credits with all null fields`() {
        val balance = Balance(credits = Credits())
        assertEquals("--", BalanceFormatter.format(balance))
    }

    @Test
    fun `format handles tokens with all null fields`() {
        val balance = Balance(tokens = Tokens())
        assertEquals("--", BalanceFormatter.format(balance))
    }

    @Test
    fun `formatDetailed shows tokens remaining when available`() {
        val balance = Balance(
            tokens = Tokens(remaining = 7500L)
        )
        assertEquals("7,500", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true))
    }

    @Test
    fun `formatDetailed shows tokens total when only total available`() {
        val balance = Balance(
            tokens = Tokens(total = 20000L)
        )
        assertEquals("20,000", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true))
    }

    @Test
    fun `formatDetailed shows credits used when remaining is null`() {
        val balance = Balance(
            credits = Credits(used = BigDecimal("15.75"))
        )
        assertEquals("\$15.75", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = false))
    }

    @Test
    fun `formatDetailed shows credits total when only total available`() {
        val balance = Balance(
            credits = Credits(total = BigDecimal("200.00"))
        )
        assertEquals("\$200.00", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = false))
    }

    @Test
    fun `formatDetailed returns dashes when showCredits is false and no tokens`() {
        val balance = Balance(credits = Credits(remaining = BigDecimal("50.00")))
        assertEquals("--", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true))
    }

    @Test
    fun `formatDetailed returns dashes when showTokens is false and no credits`() {
        val balance = Balance(tokens = Tokens(remaining = 1000L))
        assertEquals("--", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = false))
    }

    @Test
    fun `formatDetailed handles both flags false`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("50.00")),
            tokens = Tokens(used = 1000L)
        )
        assertEquals("--", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = false))
    }

    // === formatUsagePercentageForStatusBar ===

    @Test
    fun `formats Claude Code with session and week metadata in compact format`() {
        val result = ProviderResult.Success(
            snapshot(
                ConnectionType.CLAUDE_CODE,
                metadata = mapOf("sessionUsed" to "14", "weekUsed" to "28")
            )
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.COMPACT)
        assertEquals("86% 5h • 72% wk", formatted)
    }

    @Test
    fun `formats Claude Code with session and week in descriptive format`() {
        val result = ProviderResult.Success(
            snapshot(
                ConnectionType.CLAUDE_CODE,
                metadata = mapOf("sessionUsed" to "14", "weekUsed" to "28")
            )
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.DESCRIPTIVE)
        assertEquals("86% of 5h remaining • 72% of weekly remaining", formatted)
    }

    @Test
    fun `formats Codex CLI with fiveHourUsed and weeklyUsed metadata`() {
        val result = ProviderResult.Success(
            snapshot(
                ConnectionType.CODEX_CLI,
                metadata = mapOf("fiveHourUsed" to "45", "weeklyUsed" to "20")
            )
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.COMPACT)
        assertEquals("55% 5h • 80% wk", formatted)
    }

    @Test
    fun `shows only weekly when session data is missing`() {
        val result = ProviderResult.Success(
            snapshot(
                ConnectionType.CLAUDE_CODE,
                metadata = mapOf("weekUsed" to "30")
            )
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.COMPACT)
        assertEquals("70% wk", formatted)
    }

    @Test
    fun `returns dashes when no usage data available`() {
        val result = ProviderResult.Success(
            snapshot(ConnectionType.CLAUDE_CODE)
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.COMPACT)
        assertEquals("--", formatted)
    }

    @Test
    fun `appends provider abbreviation when provided`() {
        val result = ProviderResult.Success(
            snapshot(
                ConnectionType.CLAUDE_CODE,
                metadata = mapOf("sessionUsed" to "14", "weekUsed" to "28")
            )
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(
            result,
            StatusBarFormat.COMPACT,
            Provider.ANTHROPIC
        )
        assertEquals("86% 5h • 72% wk (CL)", formatted)
    }

    // === formatDollarsForStatusBar ===

    @Test
    fun `formats dollar amount in compact format`() {
        val formatted = BalanceFormatter.formatDollarsForStatusBar(
            BigDecimal("500.00"),
            StatusBarFormat.COMPACT
        )
        assertEquals("\$500.00", formatted)
    }

    @Test
    fun `formats dollar amount in descriptive format`() {
        val formatted = BalanceFormatter.formatDollarsForStatusBar(
            BigDecimal("500.00"),
            StatusBarFormat.DESCRIPTIVE
        )
        assertEquals("\$500.00 remaining", formatted)
    }

    @Test
    fun `formats dollar amount with provider abbreviation`() {
        val formatted = BalanceFormatter.formatDollarsForStatusBar(
            BigDecimal("500.00"),
            StatusBarFormat.COMPACT,
            Provider.OPENROUTER
        )
        assertEquals("\$500.00 (OR)", formatted)
    }

    // === formatTotalDollarsForStatusBar ===

    @Test
    fun `formats total in compact with provider count`() {
        val formatted = BalanceFormatter.formatTotalDollarsForStatusBar(
            BigDecimal("500.00"),
            StatusBarFormat.COMPACT,
            providerCount = 3
        )
        assertEquals("\$500.00 (3)", formatted)
    }

    @Test
    fun `formats total in compact without count when single provider`() {
        val formatted = BalanceFormatter.formatTotalDollarsForStatusBar(
            BigDecimal("500.00"),
            StatusBarFormat.COMPACT,
            providerCount = 1
        )
        assertEquals("\$500.00", formatted)
    }

    @Test
    fun `formats total in descriptive format`() {
        val formatted = BalanceFormatter.formatTotalDollarsForStatusBar(
            BigDecimal("500.00"),
            StatusBarFormat.DESCRIPTIVE
        )
        assertEquals("\$500.00 total remaining", formatted)
    }

    // === formatUsedDollarsForStatusBar ===

    @Test
    fun `formats used dollars in compact format`() {
        val formatted = BalanceFormatter.formatUsedDollarsForStatusBar(
            BigDecimal("14.50"),
            StatusBarFormat.COMPACT
        )
        assertEquals("\$14.50 used", formatted)
    }

    @Test
    fun `formats used dollars in descriptive format`() {
        val formatted = BalanceFormatter.formatUsedDollarsForStatusBar(
            BigDecimal("14.50"),
            StatusBarFormat.DESCRIPTIVE
        )
        assertEquals("\$14.50 used this period", formatted)
    }

    @Test
    fun `formats used dollars with provider abbreviation`() {
        val formatted = BalanceFormatter.formatUsedDollarsForStatusBar(
            BigDecimal("14.50"),
            StatusBarFormat.COMPACT,
            Provider.OPENAI
        )
        assertEquals("\$14.50 used (OA)", formatted)
    }

    // === FormatCapability ===

    @Test
    fun `FormatCapability fromCredits with remaining only`() {
        val capability = BalanceFormatter.FormatCapability.fromCredits(Credits(remaining = BigDecimal(100)))
        assertEquals(true, capability.supportsRemaining)
        assertEquals(false, capability.supportsUsedOfTotal)
        assertEquals(false, capability.supportsPercentage)
    }

    @Test
    fun `FormatCapability fromCredits with used and remaining`() {
        val capability = BalanceFormatter.FormatCapability.fromCredits(
            Credits(used = BigDecimal(50), remaining = BigDecimal(100))
        )
        assertEquals(true, capability.supportsRemaining)
        assertEquals(true, capability.supportsUsedOfTotal)
        assertEquals(true, capability.supportsPercentage)
    }

    @Test
    fun `FormatCapability fromCredits with remaining and total`() {
        val capability = BalanceFormatter.FormatCapability.fromCredits(
            Credits(remaining = BigDecimal(75), total = BigDecimal(100))
        )
        assertEquals(true, capability.supportsRemaining)
        assertEquals(true, capability.supportsUsedOfTotal)
        assertEquals(true, capability.supportsPercentage)
    }

    @Test
    fun `FormatCapability fromCredits with null credits`() {
        val capability = BalanceFormatter.FormatCapability.fromCredits(null)
        assertEquals(false, capability.supportsRemaining)
        assertEquals(false, capability.supportsUsedOfTotal)
        assertEquals(false, capability.supportsPercentage)
    }

    @Test
    fun `FormatCapability bestAvailable returns requested when supported`() {
        val capability = BalanceFormatter.FormatCapability.fromCredits(
            Credits(remaining = BigDecimal(75), total = BigDecimal(100))
        )
        assertEquals(
            StatusBarDollarFormat.PERCENTAGE_REMAINING,
            capability.bestAvailable(StatusBarDollarFormat.PERCENTAGE_REMAINING)
        )
    }

    @Test
    fun `FormatCapability bestAvailable falls back to USED_OF_REMAINING`() {
        val capability = BalanceFormatter.FormatCapability.fromCredits(
            Credits(remaining = BigDecimal(100))
        )
        assertEquals(
            StatusBarDollarFormat.REMAINING_ONLY,
            capability.bestAvailable(StatusBarDollarFormat.PERCENTAGE_REMAINING)
        )
    }

    @Test
    fun `FormatCapability bestAvailable falls back to REMAINING_ONLY`() {
        val capability = BalanceFormatter.FormatCapability.fromCredits(
            Credits(remaining = BigDecimal(100))
        )
        assertEquals(
            StatusBarDollarFormat.REMAINING_ONLY,
            capability.bestAvailable(StatusBarDollarFormat.USED_OF_REMAINING)
        )
    }

    // === formatCreditsForStatusBar ===

    @Test
    fun `formats REMAINING_ONLY with remaining amount`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(remaining = BigDecimal(500)),
            StatusBarDollarFormat.REMAINING_ONLY
        )
        assertEquals("\$500", formatted)
    }

    @Test
    fun `formats REMAINING_ONLY fallback to used when remaining null`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(used = BigDecimal(50)),
            StatusBarDollarFormat.REMAINING_ONLY
        )
        assertEquals("\$50 used", formatted)
    }

    @Test
    fun `formats USED_OF_REMAINING with both used and remaining`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(used = BigDecimal(193), remaining = BigDecimal(200)),
            StatusBarDollarFormat.USED_OF_REMAINING
        )
        // total = used + remaining = 393, so format is "remaining / total"
        assertEquals("\$200 / \$393", formatted)
    }

    @Test
    fun `formats PERCENTAGE_REMAINING with remaining and total`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(remaining = BigDecimal(75), total = BigDecimal(100)),
            StatusBarDollarFormat.PERCENTAGE_REMAINING
        )
        assertEquals("75% remaining", formatted)
    }

    @Test
    fun `formats PERCENTAGE_REMAINING with remaining and computed total`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(remaining = BigDecimal(60), used = BigDecimal(40)),
            StatusBarDollarFormat.PERCENTAGE_REMAINING
        )
        assertEquals("60% remaining", formatted)
    }

    @Test
    fun `formats PERCENTAGE_REMAINING with zero remaining and used`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(remaining = BigDecimal.ZERO, used = BigDecimal.ZERO),
            StatusBarDollarFormat.PERCENTAGE_REMAINING
        )
        assertEquals("0% remaining", formatted)
    }

    @Test
    fun `formats PERCENTAGE_REMAINING with only remaining falls back to remaining only`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(remaining = BigDecimal(55)),
            StatusBarDollarFormat.PERCENTAGE_REMAINING
        )
        // Falls back to REMAINING_ONLY since percentage cannot be calculated
        assertEquals("$55", formatted)
    }

    @Test
    fun `formats REMAINING_ONLY DESCRIPTIVE with only used`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(used = BigDecimal(50)),
            StatusBarDollarFormat.REMAINING_ONLY,
            null,
            StatusBarFormat.DESCRIPTIVE
        )
        assertEquals("$50 used this period", formatted)
    }

    @Test
    fun `returns dashes when no data available`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(),
            StatusBarDollarFormat.REMAINING_ONLY
        )
        assertEquals("--", formatted)
    }

    @Test
    fun `appends provider abbreviation when text is not dashes`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(remaining = BigDecimal(500)),
            StatusBarDollarFormat.REMAINING_ONLY,
            Provider.OPENROUTER
        )
        assertEquals("\$500 (OR)", formatted)
    }

    @Test
    fun `does not append abbreviation when text is dashes`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(),
            StatusBarDollarFormat.REMAINING_ONLY,
            Provider.OPENROUTER
        )
        assertEquals("--", formatted)
    }

    // === getStatusBarDataFromSnapshot ===

    @Test
    fun `returns UsagePercentage for CLAUDE_CODE connection`() {
        val snap = snapshot(ConnectionType.CLAUDE_CODE)
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snap)
        assert(data is BalanceFormatter.StatusBarData.UsagePercentage)
    }

    @Test
    fun `returns UsagePercentage for CODEX_CLI connection`() {
        val snap = snapshot(ConnectionType.CODEX_CLI)
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snap)
        assert(data is BalanceFormatter.StatusBarData.UsagePercentage)
    }

    @Test
    fun `returns RemainingDollars when credits remaining present`() {
        val snap = snapshot(
            ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance(credits = Credits(remaining = BigDecimal(100)))
        )
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snap)
        assert(data is BalanceFormatter.StatusBarData.RemainingDollars)
    }

    @Test
    fun `returns UsedDollars when only credits used present`() {
        val snap = snapshot(
            ConnectionType.OPENAI_PLATFORM,
            balance = Balance(credits = Credits(used = BigDecimal(50)))
        )
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snap)
        assert(data is BalanceFormatter.StatusBarData.UsedDollars)
    }

    @Test
    fun `returns NoData when no credits`() {
        val snap = snapshot(ConnectionType.OPENROUTER_PROVISIONING)
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snap)
        assert(data is BalanceFormatter.StatusBarData.NoData)
    }

    // === ThreadLocal safety ===

    @Test
    fun `formatNumber produces consistent US locale output across calls`() {
        val balance1 = Balance(tokens = Tokens(used = 1234567L))
        val balance2 = Balance(tokens = Tokens(used = 9876543L))
        assertEquals("1,234,567 used", BalanceFormatter.format(balance1))
        assertEquals("9,876,543 used", BalanceFormatter.format(balance2))
    }

    // === formatShortCredits ===

    @Test
    fun `formatShortCredits formats small numbers without suffix`() {
        assertEquals("0", BalanceFormatter.formatShortCredits(0))
        assertEquals("123", BalanceFormatter.formatShortCredits(123))
        assertEquals("999", BalanceFormatter.formatShortCredits(999))
    }

    @Test
    fun `formatShortCredits formats thousands with K suffix`() {
        assertEquals("1K", BalanceFormatter.formatShortCredits(1000))
        assertEquals("1.5K", BalanceFormatter.formatShortCredits(1500))
        assertEquals("999K", BalanceFormatter.formatShortCredits(999000))
    }

    @Test
    fun `formatShortCredits formats millions with M suffix`() {
        assertEquals("1M", BalanceFormatter.formatShortCredits(1_000_000))
        assertEquals("2.3M", BalanceFormatter.formatShortCredits(2_300_000))
        assertEquals("999M", BalanceFormatter.formatShortCredits(999_000_000))
    }

    @Test
    fun `formatShortCredits formats billions with B suffix`() {
        assertEquals("1B", BalanceFormatter.formatShortCredits(1_000_000_000))
        assertEquals("3.3B", BalanceFormatter.formatShortCredits(3_300_000_000))
        assertEquals("11B", BalanceFormatter.formatShortCredits(11_000_000_000))
    }

    // === formatShortDollars ===

    @Test
    fun `formatShortDollars formats with dollar prefix`() {
        assertEquals("$0", BalanceFormatter.formatShortDollars(BigDecimal.ZERO))
        assertEquals("$500", BalanceFormatter.formatShortDollars(BigDecimal(500)))
        assertEquals("$1.5K", BalanceFormatter.formatShortDollars(BigDecimal(1500)))
        assertEquals("$2.3M", BalanceFormatter.formatShortDollars(BigDecimal(2300000)))
        assertEquals("$3.3B", BalanceFormatter.formatShortDollars(BigDecimal(3300000000)))
    }

    // === isUsagePercentageType ===

    @Test
    fun `isUsagePercentageType returns false for XIAOMI unified`() {
        // The unified XIAOMI type prefers its dollar balance and only falls back
        // to a Token Plan percentage at render time, so it is NOT a pure
        // percentage type like Claude/Codex.
        assertEquals(false, BalanceFormatter.isUsagePercentageType(ConnectionType.XIAOMI))
    }

    // === formatUsagePercentageForStatusBar for unified XIAOMI (Token Plan slice) ===

    @Test
    fun `formatUsagePercentageForStatusBar shows percentage for XIAOMI`() {
        val result = successResult(
            connectionType = ConnectionType.XIAOMI,
            metadata = mapOf("sessionUsed" to "25")
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(
            result,
            StatusBarFormat.COMPACT
        )
        assertEquals("75% Credits", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar shows descriptive percentage for XIAOMI`() {
        val result = successResult(
            connectionType = ConnectionType.XIAOMI,
            metadata = mapOf("sessionUsed" to "25")
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(
            result,
            StatusBarFormat.DESCRIPTIVE
        )
        assertEquals("75% of Credits remaining", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar shows used of remaining for XIAOMI`() {
        val result = successResult(
            connectionType = ConnectionType.XIAOMI,
            balance = Balance(tokens = Tokens(used = 2727524596, total = 11000000000, remaining = 8272475404)),
            metadata = mapOf("sessionUsed" to "25")
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(
            result,
            StatusBarFormat.COMPACT,
            null,
            StatusBarDollarFormat.USED_OF_REMAINING
        )
        assertEquals("2.7B / 11B", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar shows descriptive used of remaining for XIAOMI`() {
        val result = successResult(
            connectionType = ConnectionType.XIAOMI,
            balance = Balance(tokens = Tokens(used = 2727524596, total = 11000000000, remaining = 8272475404)),
            metadata = mapOf("sessionUsed" to "25")
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(
            result,
            StatusBarFormat.DESCRIPTIVE,
            null,
            StatusBarDollarFormat.USED_OF_REMAINING
        )
        assertEquals("2.7B used of 11B Credits", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar shows remaining only for XIAOMI`() {
        val result = successResult(
            connectionType = ConnectionType.XIAOMI,
            balance = Balance(tokens = Tokens(used = 2727524596, total = 11000000000, remaining = 8272475404)),
            metadata = mapOf("sessionUsed" to "25")
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(
            result,
            StatusBarFormat.COMPACT,
            null,
            StatusBarDollarFormat.REMAINING_ONLY
        )
        assertEquals("8.3B", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar shows descriptive remaining only for XIAOMI`() {
        val result = successResult(
            connectionType = ConnectionType.XIAOMI,
            balance = Balance(tokens = Tokens(used = 2727524596, total = 11000000000, remaining = 8272475404)),
            metadata = mapOf("sessionUsed" to "25")
        )
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(
            result,
            StatusBarFormat.DESCRIPTIVE,
            null,
            StatusBarDollarFormat.REMAINING_ONLY
        )
        assertEquals("8.3B Credits remaining", formatted)
    }

    // === formatCreditsForStatusBar with DESCRIPTIVE format ===

    @Test
    fun `formatCreditsForStatusBar shows descriptive remaining only`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(remaining = BigDecimal(500)),
            StatusBarDollarFormat.REMAINING_ONLY,
            null,
            StatusBarFormat.DESCRIPTIVE
        )
        assertEquals("$500 remaining", formatted)
    }

    @Test
    fun `formatCreditsForStatusBar shows descriptive used of remaining`() {
        val formatted = BalanceFormatter.formatCreditsForStatusBar(
            Credits(used = BigDecimal(193), remaining = BigDecimal(200)),
            StatusBarDollarFormat.USED_OF_REMAINING,
            null,
            StatusBarFormat.DESCRIPTIVE
        )
        // total = used + remaining = 393, so format is "remaining of total remaining"
        assertEquals("$200 of $393 remaining", formatted)
    }

    // === getStatusBarDataFromSnapshot for unified XIAOMI ===

    @Test
    fun `returns UsagePercentage for XIAOMI when only Token Plan tokens present`() {
        val snap = snapshot(
            ConnectionType.XIAOMI,
            balance = Balance(tokens = Tokens(used = 100, total = 1000, remaining = 900))
        )
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snap)
        assert(data is BalanceFormatter.StatusBarData.UsagePercentage)
    }

    @Test
    fun `returns RemainingDollars for XIAOMI when dollar balance present`() {
        val snap = snapshot(
            ConnectionType.XIAOMI,
            balance = Balance(credits = Credits(remaining = BigDecimal("12.34")))
        )
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snap)
        assert(data is BalanceFormatter.StatusBarData.RemainingDollars)
    }
}
