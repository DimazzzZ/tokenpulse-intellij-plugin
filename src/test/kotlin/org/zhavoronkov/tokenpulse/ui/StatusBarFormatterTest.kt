package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.Provider
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.StatusBarFormat
import java.math.BigDecimal

class StatusBarFormatterTest {

    @Test
    fun `isUsagePercentageType returns true for Claude Code`() {
        assertEquals(true, BalanceFormatter.isUsagePercentageType(ConnectionType.CLAUDE_CODE))
    }

    @Test
    fun `isUsagePercentageType returns true for Codex CLI`() {
        assertEquals(true, BalanceFormatter.isUsagePercentageType(ConnectionType.CODEX_CLI))
    }

    @Test
    fun `isUsagePercentageType returns false for dollar-based providers`() {
        assertEquals(false, BalanceFormatter.isUsagePercentageType(ConnectionType.OPENROUTER_PROVISIONING))
        assertEquals(false, BalanceFormatter.isUsagePercentageType(ConnectionType.NEBIUS_BILLING))
        assertEquals(false, BalanceFormatter.isUsagePercentageType(ConnectionType.OPENAI_PLATFORM))
        assertEquals(false, BalanceFormatter.isUsagePercentageType(ConnectionType.CLINE_API))
    }

    @Test
    fun `formatDollarsForStatusBar compact format without provider`() {
        val amount = BigDecimal("500.00")
        val result = BalanceFormatter.formatDollarsForStatusBar(amount, StatusBarFormat.COMPACT)
        assertEquals("\$500.00", result)
    }

    @Test
    fun `formatDollarsForStatusBar compact format with provider`() {
        val amount = BigDecimal("14.50")
        val result = BalanceFormatter.formatDollarsForStatusBar(amount, StatusBarFormat.COMPACT, Provider.OPENROUTER)
        assertEquals("\$14.50 (OR)", result)
    }

    @Test
    fun `formatDollarsForStatusBar descriptive format without provider`() {
        val amount = BigDecimal("500.00")
        val result = BalanceFormatter.formatDollarsForStatusBar(amount, StatusBarFormat.DESCRIPTIVE)
        assertEquals("\$500.00 remaining", result)
    }

    @Test
    fun `formatDollarsForStatusBar descriptive format with provider`() {
        val amount = BigDecimal("14.50")
        val result = BalanceFormatter.formatDollarsForStatusBar(
            amount,
            StatusBarFormat.DESCRIPTIVE,
            Provider.OPENROUTER
        )
        assertEquals("\$14.50 remaining (OR)", result)
    }

    @Test
    fun `formatTotalDollarsForStatusBar compact format single provider`() {
        val amount = BigDecimal("500.00")
        val result = BalanceFormatter.formatTotalDollarsForStatusBar(amount, StatusBarFormat.COMPACT, 1)
        assertEquals("\$500.00", result)
    }

    @Test
    fun `formatTotalDollarsForStatusBar compact format multiple providers`() {
        val amount = BigDecimal("500.00")
        val result = BalanceFormatter.formatTotalDollarsForStatusBar(amount, StatusBarFormat.COMPACT, 3)
        assertEquals("\$500.00 (3)", result)
    }

    @Test
    fun `formatTotalDollarsForStatusBar descriptive format`() {
        val amount = BigDecimal("500.00")
        val result = BalanceFormatter.formatTotalDollarsForStatusBar(amount, StatusBarFormat.DESCRIPTIVE, 3)
        assertEquals("\$500.00 total remaining", result)
    }

    @Test
    fun `formatUsedDollarsForStatusBar compact format`() {
        val amount = BigDecimal("14.50")
        val result = BalanceFormatter.formatUsedDollarsForStatusBar(amount, StatusBarFormat.COMPACT)
        assertEquals("\$14.50 used", result)
    }

    @Test
    fun `formatUsedDollarsForStatusBar descriptive format with provider`() {
        val amount = BigDecimal("14.50")
        val result = BalanceFormatter.formatUsedDollarsForStatusBar(
            amount,
            StatusBarFormat.DESCRIPTIVE,
            Provider.OPENAI
        )
        assertEquals("\$14.50 used this period (OA)", result)
    }

    @Test
    fun `formatUsagePercentageForStatusBar Claude Code compact`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.CLAUDE_CODE,
            balance = Balance(),
            metadata = mapOf(
                "fiveHourUtilization" to "14",
                "sevenDayUtilization" to "28"
            )
        )
        val result = ProviderResult.Success(snapshot)
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.COMPACT)
        assertEquals("86% 5h • 72% wk", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar Claude Code descriptive`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.CLAUDE_CODE,
            balance = Balance(),
            metadata = mapOf(
                "fiveHourUtilization" to "14",
                "sevenDayUtilization" to "28"
            )
        )
        val result = ProviderResult.Success(snapshot)
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.DESCRIPTIVE)
        assertEquals("86% of 5h remaining • 72% of weekly remaining", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar Claude Code with provider abbreviation`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.CLAUDE_CODE,
            balance = Balance(),
            metadata = mapOf(
                "fiveHourUtilization" to "14",
                "sevenDayUtilization" to "28"
            )
        )
        val result = ProviderResult.Success(snapshot)
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(
            result,
            StatusBarFormat.COMPACT,
            Provider.ANTHROPIC
        )
        assertEquals("86% 5h • 72% wk (CL)", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar Codex CLI compact`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.CODEX_CLI,
            balance = Balance(),
            metadata = mapOf(
                "fiveHourUsed" to "25.5",
                "weeklyUsed" to "40.0"
            )
        )
        val result = ProviderResult.Success(snapshot)
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.COMPACT)
        assertEquals("75% 5h • 60% wk", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar returns -- when no data`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.CLAUDE_CODE,
            balance = Balance(),
            metadata = emptyMap()
        )
        val result = ProviderResult.Success(snapshot)
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.COMPACT)
        assertEquals("--", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar shows 100 percent when session used is 0`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.CLAUDE_CODE,
            balance = Balance(),
            metadata = mapOf(
                "sessionUsed" to "0",
                "weekUsed" to "14"
            )
        )
        val result = ProviderResult.Success(snapshot)
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.COMPACT)
        assertEquals("100% 5h • 86% wk", formatted)
    }

    @Test
    fun `formatUsagePercentageForStatusBar fallback to CLI data`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.CLAUDE_CODE,
            balance = Balance(),
            metadata = mapOf(
                "sessionUsed" to "30",
                "weekUsed" to "50"
            )
        )
        val result = ProviderResult.Success(snapshot)
        val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(result, StatusBarFormat.COMPACT)
        assertEquals("70% 5h • 50% wk", formatted)
    }

    @Test
    fun `getStatusBarDataFromSnapshot returns UsagePercentage for Claude`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.CLAUDE_CODE,
            balance = Balance()
        )
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snapshot)
        assertEquals(BalanceFormatter.StatusBarData.UsagePercentage(Provider.ANTHROPIC), data)
    }

    @Test
    fun `getStatusBarDataFromSnapshot returns RemainingDollars for OpenRouter`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance(credits = Credits(remaining = BigDecimal("100.00")))
        )
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snapshot)
        assertEquals(
            BalanceFormatter.StatusBarData.RemainingDollars(BigDecimal("100.00"), Provider.OPENROUTER),
            data
        )
    }

    @Test
    fun `getStatusBarDataFromSnapshot returns UsedDollars for OpenAI Platform`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.OPENAI_PLATFORM,
            balance = Balance(credits = Credits(used = BigDecimal("50.00")))
        )
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snapshot)
        assertEquals(
            BalanceFormatter.StatusBarData.UsedDollars(BigDecimal("50.00"), Provider.OPENAI),
            data
        )
    }

    @Test
    fun `getStatusBarDataFromSnapshot returns NoData when no credits`() {
        val snapshot = BalanceSnapshot(
            accountId = "test",
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            balance = Balance()
        )
        val data = BalanceFormatter.getStatusBarDataFromSnapshot(snapshot)
        assertEquals(BalanceFormatter.StatusBarData.NoData, data)
    }

    @Test
    fun `provider abbreviations are correct`() {
        assertEquals("CL", Provider.ANTHROPIC.abbreviation)
        assertEquals("OA", Provider.OPENAI.abbreviation)
        assertEquals("CN", Provider.CLINE.abbreviation)
        assertEquals("OR", Provider.OPENROUTER.abbreviation)
        assertEquals("NB", Provider.NEBIUS.abbreviation)
    }
}
