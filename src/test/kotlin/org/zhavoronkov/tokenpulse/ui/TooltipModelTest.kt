package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.NebiusBalanceBreakdown
import org.zhavoronkov.tokenpulse.model.Provider
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.provider.cline.ClineProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.ui.TooltipModel.TooltipRow
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit tests for the pure tooltip model: provider grouping and per-account
 * row building. Rendering (Swing) is not exercised here.
 */
class TooltipModelTest {

    private fun account(ct: ConnectionType, id: String = ct.name, name: String = "") =
        Account(id = id, name = name, connectionType = ct, isEnabled = true)

    private fun success(
        ct: ConnectionType,
        credits: Credits? = null,
        tokens: Tokens? = null,
        breakdown: NebiusBalanceBreakdown? = null,
        metadata: Map<String, String> = emptyMap(),
    ): ProviderResult.Success = ProviderResult.Success(
        BalanceSnapshot(
            accountId = ct.name,
            connectionType = ct,
            balance = Balance(credits = credits, tokens = tokens),
            timestamp = Instant.EPOCH,
            nebiusBreakdown = breakdown,
            metadata = metadata,
        )
    )

    private fun rows(ct: ConnectionType, result: ProviderResult?): List<TooltipRow> =
        TooltipModel.buildAccountRows(account(ct), result)

    // ---- grouping ---------------------------------------------------------

    @Test
    fun `groups sorted by provider display name`() {
        val cline = account(ConnectionType.CLINE_API, id = "cl")
        val claude = account(ConnectionType.CLAUDE_CODE, id = "an")
        val results = mapOf(
            "cl" to success(ConnectionType.CLINE_API, credits = Credits(remaining = BigDecimal.TEN)),
            "an" to success(ConnectionType.CLAUDE_CODE, metadata = mapOf("fiveHourUtilization" to "20")),
        )
        val grouped = TooltipModel.groupAccountsWithRows(listOf(cline, claude), results)
        assertEquals(listOf(Provider.ANTHROPIC, Provider.CLINE), grouped.map { it.first })
    }

    @Test
    fun `two connections of same provider collapse into one group`() {
        val codex = account(ConnectionType.CODEX_CLI, id = "a")
        val platform = account(ConnectionType.OPENAI_PLATFORM, id = "b")
        val results = mapOf(
            "a" to success(ConnectionType.CODEX_CLI, metadata = mapOf("fiveHourUsed" to "10")),
            "b" to success(ConnectionType.OPENAI_PLATFORM, credits = Credits(remaining = BigDecimal.ONE)),
        )
        val grouped = TooltipModel.groupAccountsWithRows(listOf(codex, platform), results)
        assertEquals(1, grouped.size)
        assertEquals(Provider.OPENAI, grouped[0].first)
        assertEquals(2, grouped[0].second.size)
    }

    @Test
    fun `accounts within a provider sorted by fullDisplayName`() {
        // OpenAI: "Codex CLI" sorts before "Platform API Key".
        val platform = account(ConnectionType.OPENAI_PLATFORM, id = "b")
        val codex = account(ConnectionType.CODEX_CLI, id = "a")
        val results = mapOf(
            "a" to success(ConnectionType.CODEX_CLI, metadata = mapOf("fiveHourUsed" to "10")),
            "b" to success(ConnectionType.OPENAI_PLATFORM, credits = Credits(remaining = BigDecimal.ONE)),
        )
        val grouped = TooltipModel.groupAccountsWithRows(listOf(platform, codex), results)
        val order = grouped[0].second.map { it.first.connectionType }
        assertEquals(listOf(ConnectionType.CODEX_CLI, ConnectionType.OPENAI_PLATFORM), order)
    }

    @Test
    fun `empty input returns empty`() {
        assertTrue(TooltipModel.groupAccountsWithRows(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `null result keeps account with Refreshing row`() {
        val a = account(ConnectionType.CLINE_API, id = "x")
        val grouped = TooltipModel.groupAccountsWithRows(listOf(a), emptyMap())
        assertEquals(1, grouped.size)
        assertEquals(listOf(TooltipRow.Info("Refreshing...")), grouped[0].second[0].second)
    }

    // ---- Nebius -----------------------------------------------------------

    @Test
    fun `nebius paid and trial yields total paid trial`() {
        val r = success(
            ConnectionType.NEBIUS_BILLING,
            breakdown = NebiusBalanceBreakdown(
                paidRemaining = BigDecimal("10.00"),
                trialRemaining = BigDecimal("5.00"),
                tenantName = "acme",
            ),
        )
        val out = rows(ConnectionType.NEBIUS_BILLING, r)
        assertEquals(TooltipRow.LabelValue("Account:", "acme"), out[0])
        assertEquals(TooltipRow.LabelValue("Total:", "$15.00", bold = true), out[1])
        assertEquals(TooltipRow.LabelValue("Paid:", "$10.00"), out[2])
        assertEquals(TooltipRow.LabelValue("Trial:", "$5.00"), out[3])
    }

    @Test
    fun `nebius paid only is bold`() {
        val r = success(
            ConnectionType.NEBIUS_BILLING,
            breakdown = NebiusBalanceBreakdown(paidRemaining = BigDecimal("7.00")),
        )
        assertEquals(
            TooltipRow.LabelValue("Paid:", "$7.00", bold = true),
            rows(ConnectionType.NEBIUS_BILLING, r).last()
        )
    }

    @Test
    fun `nebius no components yields no balance data`() {
        val r = success(ConnectionType.NEBIUS_BILLING, breakdown = NebiusBalanceBreakdown())
        assertEquals(listOf(TooltipRow.Info("No balance data")), rows(ConnectionType.NEBIUS_BILLING, r))
    }

    // ---- Codex ------------------------------------------------------------

    @Test
    fun `codex empty metadata prompts to enable`() {
        // snapshot.metadata is a non-null map; empty map goes through the
        // disabled/non-free branch and emits the enable hint (planType null,
        // codexEnabled null).
        val r = success(ConnectionType.CODEX_CLI, metadata = emptyMap())
        assertEquals(listOf(TooltipRow.Info("Enable Codex for usage tracking")), rows(ConnectionType.CODEX_CLI, r))
    }

    @Test
    fun `codex rate limits yield three usage bars in order`() {
        val r = success(
            ConnectionType.CODEX_CLI,
            metadata = mapOf(
                "fiveHourUsed" to "10",
                "weeklyUsed" to "20",
                "codeReviewUsed" to "30",
                "fiveHourResetsAt" to "2999-01-01T00:00:00Z",
            ),
        )
        val bars = rows(ConnectionType.CODEX_CLI, r).filterIsInstance<TooltipRow.UsageBar>()
        assertEquals(listOf("5-hour", "Weekly", "Code Review"), bars.map { it.label })
        // Bar fills with CONSUMED (used %); the printed label reads REMAINING.
        assertEquals(listOf(10, 20, 30), bars.map { it.fillPercent })
        assertEquals(listOf("90%", "80%", "70%"), bars.map { it.labelText })
        // valid ISO reset -> inline present with prefix; absent -> null
        assertTrue(bars.first { it.label == "5-hour" }.resetInline!!.startsWith("Resets "))
        assertNull(bars.first { it.label == "Weekly" }.resetInline)
    }

    @Test
    fun `codex NA rate limit values are skipped`() {
        val r = success(
            ConnectionType.CODEX_CLI,
            metadata = mapOf("fiveHourUsed" to "N/A", "weeklyUsed" to "25"),
        )
        val bars = rows(ConnectionType.CODEX_CLI, r).filterIsInstance<TooltipRow.UsageBar>()
        assertEquals(listOf("Weekly"), bars.map { it.label })
    }

    @Test
    fun `codex error variants map to info messages`() {
        val cases = mapOf(
            "not_installed" to "Codex CLI not installed",
            "not_authenticated" to "Codex not logged in",
            "app_server_start_failed" to "Codex app-server failed",
            "rate_limits_unavailable" to "Rate limits unavailable",
            "token_expired" to "Codex session expired",
            "something_else" to "Usage data unavailable",
        )
        for ((code, msg) in cases) {
            val r = success(
                ConnectionType.CODEX_CLI,
                metadata = mapOf("codexEnabled" to "true", "codexError" to code),
            )
            assertEquals(listOf(TooltipRow.Info(msg)), rows(ConnectionType.CODEX_CLI, r))
        }
    }

    @Test
    fun `codex error detail is appended`() {
        val r = success(
            ConnectionType.CODEX_CLI,
            metadata = mapOf(
                "codexEnabled" to "true",
                "codexError" to "not_installed",
                "codexErrorDetail" to "brew missing",
            ),
        )
        assertEquals(
            listOf(TooltipRow.Info("Codex CLI not installed: brew missing")),
            rows(ConnectionType.CODEX_CLI, r)
        )
    }

    @Test
    fun `codex disabled non-free plan prompts to enable`() {
        val r = success(ConnectionType.CODEX_CLI, metadata = mapOf("planType" to "pro"))
        assertTrue(rows(ConnectionType.CODEX_CLI, r).contains(TooltipRow.Info("Enable Codex for usage tracking")))
    }

    @Test
    fun `codex disabled free plan adds no hint`() {
        val r = success(ConnectionType.CODEX_CLI, metadata = mapOf("planType" to "free"))
        assertEquals(listOf(TooltipRow.LabelValue("Plan:", "free")), rows(ConnectionType.CODEX_CLI, r))
    }

    // ---- Claude Code ------------------------------------------------------

    @Test
    fun `claude empty metadata yields no usage data`() {
        val r = success(ConnectionType.CLAUDE_CODE, metadata = emptyMap())
        assertEquals(listOf(TooltipRow.Info("No usage data")), rows(ConnectionType.CLAUDE_CODE, r))
    }

    @Test
    fun `claude utilization path builds usage bars with inline reset`() {
        val r = success(
            ConnectionType.CLAUDE_CODE,
            metadata = mapOf(
                "fiveHourUtilization" to "40",
                "sevenDayUtilization" to "10",
                "fiveHourResetsAt" to "2999-01-01T00:00:00Z",
            ),
        )
        val bars = rows(ConnectionType.CLAUDE_CODE, r).filterIsInstance<TooltipRow.UsageBar>()
        assertEquals(listOf("5-hour", "7-day"), bars.map { it.label })
        // Bar fills with CONSUMED (used %); the printed label reads REMAINING.
        assertEquals(listOf(40, 10), bars.map { it.fillPercent })
        assertEquals(listOf("60%", "90%"), bars.map { it.labelText })
        // valid ISO -> inline reset present with prefix
        val fiveHour = bars.first { it.label == "5-hour" }
        assertTrue(fiveHour.resetInline!!.startsWith("Resets "))
        // absent reset -> null
        assertNull(bars.first { it.label == "7-day" }.resetInline)
    }

    @Test
    fun `claude session week fallback path`() {
        val r = success(
            ConnectionType.CLAUDE_CODE,
            metadata = mapOf("sessionUsed" to "30", "weekUsed" to "15"),
        )
        val bars = rows(ConnectionType.CLAUDE_CODE, r).filterIsInstance<TooltipRow.UsageBar>()
        assertEquals(listOf("5-hour", "Weekly"), bars.map { it.label })
        assertEquals(listOf(30, 15), bars.map { it.fillPercent })
        assertEquals(listOf("70%", "85%"), bars.map { it.labelText })
    }

    @Test
    fun `claude no usable metadata yields unavailable and status row`() {
        val r = success(ConnectionType.CLAUDE_CODE, metadata = mapOf("status" to "ok"))
        val out = rows(ConnectionType.CLAUDE_CODE, r)
        assertTrue(out.contains(TooltipRow.Info("Usage data unavailable")))
        assertTrue(out.contains(TooltipRow.Info("Status: ok")))
    }

    // ---- Xiaomi Token Plan ------------------------------------------------

    @Test
    fun `xiaomi empty metadata and no tokens yields no rows`() {
        // snapshot.metadata is a non-null empty map, so the (metadata == null
        // && tokens == null) guard is false; nothing is emitted.
        val r = success(ConnectionType.XIAOMI_TOKEN_PLAN)
        assertTrue(rows(ConnectionType.XIAOMI_TOKEN_PLAN, r).isEmpty())
    }

    @Test
    fun `xiaomi session used yields credits bar and plan used`() {
        val r = success(
            ConnectionType.XIAOMI_TOKEN_PLAN,
            tokens = Tokens(total = 1000, used = 250),
            metadata = mapOf("sessionUsed" to "40"),
        )
        val out = rows(ConnectionType.XIAOMI_TOKEN_PLAN, r)
        assertEquals(TooltipRow.BalanceBar("Credits", 60), out[0])
        assertTrue(out.any { it is TooltipRow.LabelValue && it.label == "Used:" })
    }

    // ---- Cline ------------------------------------------------------------

    @Test
    fun `cline credits only when no metrics`() {
        val r = success(ConnectionType.CLINE_API, credits = Credits(remaining = BigDecimal("3.00")))
        val out = rows(ConnectionType.CLINE_API, r)
        assertEquals(listOf(TooltipRow.LabelValue("Balance:", "$3.00", bold = true)), out)
    }

    @Test
    fun `cline metrics render section header and inline reset bars`() {
        val r = success(
            ConnectionType.CLINE_API,
            credits = Credits(remaining = BigDecimal("3.00")),
            metadata = mapOf(
                ClineProviderClient.METADATA_FIVE_HOUR_USED to "50",
                ClineProviderClient.METADATA_FIVE_HOUR_RESETS_AT to "2999-01-01T00:00:00Z",
            ),
        )
        val out = rows(ConnectionType.CLINE_API, r)
        assertTrue(out.any { it is TooltipRow.SectionHeader })
        val bars = out.filterIsInstance<TooltipRow.UsageBar>()
        assertEquals(1, bars.size)
        assertEquals("5-hour", bars[0].label)
        // Cline convention: bar fill AND label both read USED (unchanged).
        assertEquals(50, bars[0].fillPercent)
        assertEquals("50%", bars[0].labelText)
        assertTrue(bars[0].resetInline!!.startsWith("Resets "))
    }

    // ---- generic credits fallback ----------------------------------------

    @Test
    fun `credits remaining and used yields remaining bold plus used`() {
        val r = success(
            ConnectionType.OPENROUTER_PROVISIONING,
            credits = Credits(remaining = BigDecimal("8.00"), used = BigDecimal("2.00")),
        )
        val out = rows(ConnectionType.OPENROUTER_PROVISIONING, r)
        assertEquals(TooltipRow.LabelValue("Remaining:", "$8.00", bold = true), out[0])
        assertEquals(TooltipRow.LabelValue("Used:", "$2.00"), out[1])
    }

    @Test
    fun `credits null yields no balance data`() {
        val r = success(ConnectionType.OPENROUTER_PROVISIONING, credits = null)
        assertEquals(listOf(TooltipRow.Info("No balance data")), rows(ConnectionType.OPENROUTER_PROVISIONING, r))
    }

    // ---- failures / null --------------------------------------------------

    @Test
    fun `auth error codex passes raw message`() {
        val r = ProviderResult.Failure.AuthError("weird codex text")
        assertEquals(listOf(TooltipRow.Error("weird codex text")), rows(ConnectionType.CODEX_CLI, r))
    }

    @Test
    fun `auth error non-codex maps known messages`() {
        val cases = mapOf(
            "token expired yesterday" to "Session expired",
            "user not authenticated" to "Not authenticated",
            "credentials missing" to "Credentials missing",
            "invalid api key" to "Invalid credentials",
            "totally unknown" to "totally unknown",
        )
        for ((raw, mapped) in cases) {
            val r = ProviderResult.Failure.AuthError(raw)
            assertEquals(listOf(TooltipRow.Error(mapped)), rows(ConnectionType.CLINE_API, r))
        }
    }

    @Test
    fun `rate limited is a warning error`() {
        val r = ProviderResult.Failure.RateLimited("slow down")
        assertEquals(
            listOf(TooltipRow.Error("Rate limited (retry later)", warning = true)),
            rows(ConnectionType.CLINE_API, r)
        )
    }

    @Test
    fun `network error message`() {
        val r = ProviderResult.Failure.NetworkError("no net")
        assertEquals(listOf(TooltipRow.Error("Network error")), rows(ConnectionType.CLINE_API, r))
    }

    @Test
    fun `parse and unknown errors map to connection error`() {
        assertEquals(
            listOf(TooltipRow.Error("Connection error")),
            rows(ConnectionType.CLINE_API, ProviderResult.Failure.ParseError("x"))
        )
        assertEquals(
            listOf(TooltipRow.Error("Connection error")),
            rows(ConnectionType.CLINE_API, ProviderResult.Failure.UnknownError("y"))
        )
    }

    @Test
    fun `null result yields refreshing`() {
        assertEquals(listOf(TooltipRow.Info("Refreshing...")), rows(ConnectionType.CLINE_API, null))
    }
}
