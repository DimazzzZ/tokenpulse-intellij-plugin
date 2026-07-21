package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.NebiusBalanceBreakdown
import org.zhavoronkov.tokenpulse.model.Provider
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.ResetTimeFormatter
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure (Swing-free) model behind the status-bar tooltip.
 *
 * Produces the ordered list of [TooltipRow]s for each account and groups
 * accounts under their provider, so [TokenPulseTooltipPanel] only has to
 * render. Keeping this logic free of Swing and IntelliJ services makes it
 * unit-testable with plain JUnit.
 */
internal object TooltipModel {

    /** A single logical row in the tooltip, independent of rendering. */
    internal sealed interface TooltipRow {
        data class LabelValue(val label: String, val value: String, val bold: Boolean = false) : TooltipRow
        data class UsageBar(val label: String, val percent: Int, val resetInline: String?) : TooltipRow
        data class BalanceBar(val label: String, val remainingPercent: Int, val resetInline: String? = null) : TooltipRow
        data class Info(val message: String) : TooltipRow
        data class Error(val message: String, val warning: Boolean = false) : TooltipRow
        data class SectionHeader(val title: String) : TooltipRow
    }

    /**
     * Groups [accounts] by provider for display. Groups are sorted by
     * [Provider.displayName]; accounts within a group by
     * [ConnectionType.fullDisplayName]. Each account is paired with its
     * pre-built rows. Accounts that yield no rows are dropped, and a provider
     * whose accounts all yield no rows is omitted entirely (no orphan header).
     */
    internal fun groupAccountsWithRows(
        accounts: List<Account>,
        results: Map<String, ProviderResult>,
    ): List<Pair<Provider, List<Pair<Account, List<TooltipRow>>>>> {
        return accounts
            .groupBy { it.connectionType.provider }
            .toList()
            .sortedBy { (provider, _) -> provider.displayName }
            .mapNotNull { (provider, accountsForProvider) ->
                val prepared = accountsForProvider
                    .sortedBy { it.connectionType.fullDisplayName }
                    .map { it to buildAccountRows(it, results[it.id]) }
                    .filter { (_, rows) -> rows.isNotEmpty() }
                if (prepared.isEmpty()) null else provider to prepared
            }
    }

    internal fun buildAccountRows(account: Account, result: ProviderResult?): List<TooltipRow> {
        val rows = mutableListOf<TooltipRow>()
        when (result) {
            is ProviderResult.Success -> {
                val snapshot = result.snapshot
                val breakdown = snapshot.nebiusBreakdown
                when {
                    snapshot.connectionType == ConnectionType.NEBIUS_BILLING && breakdown != null ->
                        appendNebiusRows(rows, breakdown)
                    snapshot.connectionType == ConnectionType.CODEX_CLI ->
                        appendCodexRows(rows, snapshot.metadata)
                    snapshot.connectionType == ConnectionType.CLAUDE_CODE ->
                        appendClaudeCodeRows(rows, snapshot.metadata)
                    snapshot.connectionType == ConnectionType.XIAOMI_TOKEN_PLAN ->
                        appendXiaomiTokenPlanRows(rows, snapshot.metadata, snapshot.balance.tokens)
                    snapshot.connectionType == ConnectionType.CLINE_API ->
                        appendClineRows(rows, snapshot.balance.credits, snapshot.metadata)
                    else ->
                        appendCreditsRows(rows, snapshot.balance.credits)
                }
            }
            is ProviderResult.Failure.AuthError ->
                rows.add(TooltipRow.Error(getAuthErrorMessage(result, account.connectionType)))
            is ProviderResult.Failure.RateLimited ->
                rows.add(TooltipRow.Error("Rate limited (retry later)", warning = true))
            is ProviderResult.Failure.NetworkError ->
                rows.add(TooltipRow.Error("Network error"))
            is ProviderResult.Failure ->
                rows.add(TooltipRow.Error("Connection error"))
            null ->
                rows.add(TooltipRow.Info("Refreshing..."))
        }
        return rows
    }

    private fun appendNebiusRows(rows: MutableList<TooltipRow>, breakdown: NebiusBalanceBreakdown) {
        breakdown.tenantName?.let { rows.add(TooltipRow.LabelValue("Account:", it)) }
        val paid = breakdown.paidRemaining
        val trial = breakdown.trialRemaining
        when {
            paid != null && trial != null -> {
                rows.add(TooltipRow.LabelValue("Total:", formatAmount(paid + trial), bold = true))
                rows.add(TooltipRow.LabelValue("Paid:", formatAmount(paid)))
                rows.add(TooltipRow.LabelValue("Trial:", formatAmount(trial)))
            }
            paid != null -> rows.add(TooltipRow.LabelValue("Paid:", formatAmount(paid), bold = true))
            trial != null -> rows.add(TooltipRow.LabelValue("Trial:", formatAmount(trial), bold = true))
            else -> rows.add(TooltipRow.Info("No balance data"))
        }
    }

    private fun appendCodexRows(rows: MutableList<TooltipRow>, metadata: Map<String, String>?) {
        if (metadata == null) {
            rows.add(TooltipRow.Info("No usage data"))
            return
        }
        val planType = metadata["planType"]
        if (planType != null && planType != "unknown") rows.add(TooltipRow.LabelValue("Plan:", planType))
        val email = metadata["email"]
        if (email != null && email != "unknown") rows.add(TooltipRow.LabelValue("Account:", email))

        val fiveHourUsed = metadata["fiveHourUsed"]
        val weeklyUsed = metadata["weeklyUsed"]
        val codeReviewUsed = metadata["codeReviewUsed"]
        val hasRateLimits = (fiveHourUsed != null && fiveHourUsed != "N/A") ||
            (weeklyUsed != null && weeklyUsed != "N/A") ||
            (codeReviewUsed != null && codeReviewUsed != "N/A")

        if (hasRateLimits) {
            fiveHourUsed?.takeIf { it != "N/A" }?.toFloatOrNull()?.toInt()?.let {
                rows.add(TooltipRow.BalanceBar("5-hour", (100 - it).coerceIn(0, 100)))
            }
            weeklyUsed?.takeIf { it != "N/A" }?.toFloatOrNull()?.toInt()?.let {
                rows.add(TooltipRow.BalanceBar("Weekly", (100 - it).coerceIn(0, 100)))
            }
            codeReviewUsed?.takeIf { it != "N/A" }?.toFloatOrNull()?.toInt()?.let {
                rows.add(TooltipRow.BalanceBar("Code Review", (100 - it).coerceIn(0, 100)))
            }
            return
        }

        val codexEnabled = metadata["codexEnabled"]
        val codexError = metadata["codexError"]
        val codexErrorDetail = metadata["codexErrorDetail"]
        if (codexEnabled == "true") {
            val errorMsg = when (codexError) {
                "not_installed" -> "Codex CLI not installed"
                "not_authenticated" -> "Codex not logged in"
                "app_server_start_failed" -> "Codex app-server failed"
                "rate_limits_unavailable" -> "Rate limits unavailable"
                "token_expired" -> "Codex session expired"
                else -> "Usage data unavailable"
            }
            val detail = codexErrorDetail?.takeIf { it.isNotBlank() }
            val fullMsg = if (detail != null) "$errorMsg: ${truncate(detail, 60)}" else errorMsg
            rows.add(TooltipRow.Info(fullMsg))
        } else if (planType?.lowercase() != "free") {
            rows.add(TooltipRow.Info("Enable Codex for usage tracking"))
        }
    }

    private fun appendClaudeCodeRows(rows: MutableList<TooltipRow>, metadata: Map<String, String>?) {
        if (metadata == null || metadata.isEmpty()) {
            rows.add(TooltipRow.Info("No usage data"))
            return
        }
        val fiveHourUtilization = metadata["fiveHourUtilization"]
        val sevenDayUtilization = metadata["sevenDayUtilization"]

        if (fiveHourUtilization != null || sevenDayUtilization != null) {
            fiveHourUtilization?.let {
                val pct = it.toIntOrNull() ?: 0
                rows.add(TooltipRow.UsageBar("5-hour", 100 - pct, resetInline(metadata["fiveHourResetsAt"])))
            }
            sevenDayUtilization?.let {
                val pct = it.toIntOrNull() ?: 0
                rows.add(TooltipRow.UsageBar("7-day", 100 - pct, resetInline(metadata["sevenDayResetsAt"])))
            }
        } else {
            val sessionUsed = metadata["sessionUsed"]
            val weekUsed = metadata["weekUsed"]
            sessionUsed?.let {
                val pct = it.toIntOrNull() ?: 0
                rows.add(TooltipRow.UsageBar("5-hour", 100 - pct, resetInline(metadata["sessionResetsAt"])))
            }
            weekUsed?.let {
                val pct = it.toIntOrNull() ?: 0
                rows.add(TooltipRow.UsageBar("Weekly", 100 - pct, resetInline(metadata["weekResetsAt"])))
            }
            if (sessionUsed == null && weekUsed == null) {
                rows.add(TooltipRow.Info("Usage data unavailable"))
            }
        }

        metadata["status"]?.let { rows.add(TooltipRow.Info("Status: $it")) }
    }

    /**
     * Format an ISO-8601 reset string for inline display next to a usage bar,
     * e.g. "Resets Today 14:30". Returns null when the value is missing or
     * unparseable so the renderer omits the reset text (never shows a raw ISO
     * value).
     */
    private fun resetInline(iso: String?): String? =
        ResetTimeFormatter.formatReset(iso)?.let { "Resets $it" }

    private fun appendXiaomiTokenPlanRows(
        rows: MutableList<TooltipRow>,
        metadata: Map<String, String>?,
        tokens: Tokens?
    ) {
        if (metadata == null && tokens == null) {
            rows.add(TooltipRow.Info("No usage data"))
            return
        }
        metadata?.get("sessionUsed")?.toIntOrNull()?.let {
            rows.add(TooltipRow.BalanceBar("Credits", 100 - it))
        }
        val planUsed = tokens?.used
        val planTotal = tokens?.total
        if (planUsed != null && planTotal != null && planTotal > 0) {
            val usedFormatted = BalanceFormatter.formatShortCredits(planUsed)
            val totalFormatted = BalanceFormatter.formatShortCredits(planTotal)
            rows.add(TooltipRow.LabelValue("Used:", "$usedFormatted / $totalFormatted"))
        }
    }

    private fun appendCreditsRows(rows: MutableList<TooltipRow>, credits: Credits?) {
        if (credits == null) {
            rows.add(TooltipRow.Info("No balance data"))
            return
        }
        val remaining = credits.remaining
        val used = credits.used
        when {
            remaining != null -> {
                val formatted = formatAmount(remaining)
                if (used != null && used > BigDecimal.ZERO) {
                    rows.add(TooltipRow.LabelValue("Remaining:", formatted, bold = true))
                    rows.add(TooltipRow.LabelValue("Used:", formatAmount(used)))
                } else {
                    rows.add(TooltipRow.LabelValue("Balance:", formatted, bold = true))
                }
            }
            used != null -> rows.add(TooltipRow.LabelValue("Used:", formatAmount(used)))
            else -> rows.add(TooltipRow.Info("No balance data"))
        }
    }

    private fun appendClineRows(
        rows: MutableList<TooltipRow>,
        credits: Credits?,
        metadata: Map<String, String>
    ) {
        appendCreditsRows(rows, credits)
        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)
        if (metrics.isEmpty()) return
        rows.add(TooltipRow.SectionHeader(ClinePassUsageRenderer.SECTION_TITLE))
        for (metric in metrics) {
            // Render Cline metrics the same way Claude Code does: reset time
            // inline in col 3, never on a separate row (constant placement).
            rows.add(TooltipRow.UsageBar(metric.label, metric.percent.coerceIn(0, 100), resetInline(metric.resetAt)))
        }
    }

    private fun formatAmount(amount: BigDecimal): String =
        "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun truncate(text: String, maxLength: Int): String =
        if (text.length > maxLength) text.take(maxLength - 1) + "\u2026" else text

    private fun getAuthErrorMessage(
        authError: ProviderResult.Failure.AuthError,
        connectionType: ConnectionType
    ): String {
        if (connectionType == ConnectionType.CODEX_CLI) return authError.message
        val message = authError.message
        return when {
            message.contains("expired", ignoreCase = true) -> "Session expired"
            message.contains("not authenticated", ignoreCase = true) -> "Not authenticated"
            message.contains("missing", ignoreCase = true) -> "Credentials missing"
            message.contains("invalid", ignoreCase = true) -> "Invalid credentials"
            else -> message
        }
    }
}
