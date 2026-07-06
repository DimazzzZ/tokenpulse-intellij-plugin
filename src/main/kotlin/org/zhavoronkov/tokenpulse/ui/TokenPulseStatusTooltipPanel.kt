package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.NebiusBalanceBreakdown
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.utils.Constants
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Builds the rich HTML tooltip for the TokenPulse status bar widget.
 *
 * Uses only HTML constructs that Swing's tooltip renderer (HTMLEditorKit)
 * supports reliably: `<table>`, `<tr>`, `<td>` with `bgcolor`/`width`/`height`
 * attributes and conservative `style` declarations. Modern CSS features like
 * `display:flex`, `border-radius`, nested `<div>` with explicit pixel sizes,
 * and CSS-based progress bars are intentionally avoided.
 *
 * The tooltip has a fixed overall width (see [TOOLTIP_MAX_WIDTH]) so that long
 * account names or dollar amounts do not push it off the edge of the editor.
 * Values and footer text wrap naturally within that width.
 */
object TokenPulseStatusTooltipPanel {

    /**
     * Maximum width of the tooltip body in pixels. Tooltips wider than this
     * can spill off the right edge of the IDE window.
     */
    private const val TOOLTIP_MAX_WIDTH = 300

    /**
     * Width of the leftmost (label) column in per-account rows.
     */
    private const val LABEL_COL_WIDTH = 78

    /**
     * Builds the full HTML tooltip string.
     * Returns an empty string if there is no data to display.
     */
    fun buildTooltipHtml(): String {
        val accounts = TokenPulseSettingsService.getInstance().state.accounts
        val activeAccounts = accounts.filter { it.isEnabled }
        if (activeAccounts.isEmpty()) return ""

        val results = BalanceRefreshService.getInstance().results.value
        val enabledAccountIds = activeAccounts.map { it.id }.toSet()
        val activeResults = results.filterKeys { it in enabledAccountIds }

        val successCount = activeResults.values.filterIsInstance<ProviderResult.Success>().count()
        val errorCount = activeResults.values.filterIsInstance<ProviderResult.Failure>().count()

        return buildString {
            append("<html><body style=\"font-family:sans-serif;font-size:12px;\">")
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
            append("width=\"" + TOOLTIP_MAX_WIDTH + "\" ")
            append("style=\"border-collapse:collapse;width:" + TOOLTIP_MAX_WIDTH + "px;table-layout:fixed;\">")
            append("<colgroup>")
            append("<col width=\"" + LABEL_COL_WIDTH + "\">")
            append("<col>")
            append("</colgroup>")
            append("<tr><td colspan=\"2\" style=\"padding:0 0 2px 0;\">")
            append(buildHeaderHtml(successCount, errorCount))
            append("</td></tr>")
            // Separator row
            append("<tr><td colspan=\"2\" style=\"padding:0;\">")
            append(buildSeparatorHtml())
            append("</td></tr>")

            activeAccounts.sortedBy { it.connectionType.fullDisplayName }.forEach { account ->
                val result = results[account.id]
                append(buildAccountSectionHtml(account, result))
            }

            append("<tr><td colspan=\"2\" style=\"padding:4px 0 0 0;\">")
            append(buildSeparatorHtml())
            append("</td></tr>")
            append("<tr><td colspan=\"2\" style=\"padding:4px 0 0 0;color:#888888;font-size:11px;word-wrap:break-word;\">")
            append("Click for Dashboard &middot; Refresh &middot; Settings")
            append("</td></tr>")
            append("</table>")
            append("</body></html>")
        }
    }

    /**
     * Header: app name on the first line, connection status on the second.
     * Stacking (instead of a one-row flex layout) keeps the tooltip narrow
     * and prevents long status text from pushing the title off-screen.
     */
    private fun buildHeaderHtml(successCount: Int, errorCount: Int): String {
        val statusColor = if (errorCount > 0) "#CC4444" else "#44AA44"
        val errorSuffix = if (errorCount > 0) {
            val s = if (errorCount > 1) "s" else ""
            " &middot; <span style=\"color:#CC4444;\">$errorCount error$s</span>"
        } else {
            ""
        }
        val statusText = "$successCount connected$errorSuffix"
        return buildString {
            append("<div style=\"font-weight:bold;padding:0;\">")
            append(Constants.DISPLAY_NAME)
            append("</div>")
            append("<div style=\"color:")
            append(statusColor)
            append(";font-size:11px;padding:1px 0 0 0;word-wrap:break-word;\">")
            append(statusText)
            append("</div>")
        }
    }

    /**
     * A 1-pixel horizontal separator using a fixed-height empty cell with
     * a background color. `<hr>` rendering varies too much across themes.
     */
    private fun buildSeparatorHtml(): String {
        return "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" " +
            "width=\"100%\" style=\"border-collapse:collapse;\">" +
            "<tr><td height=\"1\" bgcolor=\"#888888\" " +
            "style=\"background:#888888;line-height:1px;font-size:1px;height:1px;\">&nbsp;</td></tr>" +
            "</table>"
    }

    private fun buildAccountSectionHtml(account: Account, result: ProviderResult?): String {
        val label = (account.name.ifBlank { account.connectionType.fullDisplayName }).escapeHtml()
        val rows = mutableListOf<String>()

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
                        appendCreditsRows(rows, snapshot.balance.credits, snapshot.connectionType)
                }
            }
            is ProviderResult.Failure.AuthError -> {
                val errorMessage = getAuthErrorMessage(result, account.connectionType).escapeHtml()
                rows.add(errorRowHtml(errorMessage, isError = true))
            }
            is ProviderResult.Failure.RateLimited ->
                rows.add(errorRowHtml("Rate limited (retry later)", isWarning = true))
            is ProviderResult.Failure.NetworkError ->
                rows.add(errorRowHtml("Network error", isError = true))
            is ProviderResult.Failure ->
                rows.add(errorRowHtml("Connection error", isError = true))
            null ->
                rows.add(infoRowHtml("Refreshing..."))
        }

        if (rows.isEmpty()) return ""

        return buildString {
            append("<tr><td colspan=\"2\" style=\"padding:8px 0 0 0;\">")
            append("<div style=\"font-weight:bold;padding:0 0 2px 0;word-wrap:break-word;\">")
            append(label)
            append("</div>")
            append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
            append("width=\"100%\" style=\"border-collapse:collapse;table-layout:fixed;\">")
            append("<colgroup><col width=\"" + LABEL_COL_WIDTH + "\"><col></colgroup>")
            rows.forEach { append(it) }
            append("</table>")
            append("</td></tr>")
        }
    }

    private fun appendNebiusRows(rows: MutableList<String>, breakdown: NebiusBalanceBreakdown) {
        val tenant = breakdown.tenantName
        if (tenant != null) {
            rows.add(labelValueRowHtml("Account:", tenant.escapeHtml()))
        }

        val paid = breakdown.paidRemaining
        val trial = breakdown.trialRemaining

        when {
            paid != null && trial != null -> {
                val total = paid + trial
                rows.add(labelValueRowHtml("Total:", formatAmount(total), bold = true))
                rows.add(labelValueRowHtml("Paid:", formatAmount(paid)))
                rows.add(labelValueRowHtml("Trial:", formatAmount(trial)))
            }
            paid != null -> rows.add(labelValueRowHtml("Paid:", formatAmount(paid), bold = true))
            trial != null -> rows.add(labelValueRowHtml("Trial:", formatAmount(trial), bold = true))
            else -> rows.add(infoRowHtml("No balance data"))
        }
    }

    private fun appendCodexRows(rows: MutableList<String>, metadata: Map<String, String>?) {
        if (metadata == null) {
            rows.add(infoRowHtml("No usage data"))
            return
        }

        val planType = metadata["planType"]
        if (planType != null && planType != "unknown") {
            rows.add(labelValueRowHtml("Plan:", planType.escapeHtml()))
        }

        val email = metadata["email"]
        if (email != null && email != "unknown") {
            rows.add(labelValueRowHtml("Account:", email.escapeHtml()))
        }

        val fiveHourUsed = metadata["fiveHourUsed"]
        val weeklyUsed = metadata["weeklyUsed"]
        val codeReviewUsed = metadata["codeReviewUsed"]

        val hasRateLimits = (fiveHourUsed != null && fiveHourUsed != "N/A") ||
            (weeklyUsed != null && weeklyUsed != "N/A") ||
            (codeReviewUsed != null && codeReviewUsed != "N/A")

        if (hasRateLimits) {
            fiveHourUsed?.takeIf { it != "N/A" }?.toFloatOrNull()?.toInt()?.let { usedPct ->
                val remainingPct = (100 - usedPct).coerceIn(0, 100)
                rows.add(ProgressBarRenderer.buildBalanceSection("5-hour", remainingPct))
            }

            weeklyUsed?.takeIf { it != "N/A" }?.toFloatOrNull()?.toInt()?.let { usedPct ->
                val remainingPct = (100 - usedPct).coerceIn(0, 100)
                rows.add(ProgressBarRenderer.buildBalanceSection("Weekly", remainingPct))
            }

            codeReviewUsed?.takeIf { it != "N/A" }?.toFloatOrNull()?.toInt()?.let { usedPct ->
                val remainingPct = (100 - usedPct).coerceIn(0, 100)
                rows.add(ProgressBarRenderer.buildBalanceSection("Code Review", remainingPct))
            }
        } else {
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
                rows.add(infoRowHtml(fullMsg.escapeHtml()))
            } else if (planType?.lowercase() != "free") {
                rows.add(infoRowHtml("Enable Codex for usage tracking"))
            }
        }
    }

    private fun appendClaudeCodeRows(rows: MutableList<String>, metadata: Map<String, String>?) {
        if (metadata == null || metadata.isEmpty()) {
            rows.add(infoRowHtml("No usage data"))
            return
        }

        val fiveHourUtilization = metadata["fiveHourUtilization"]
        val sevenDayUtilization = metadata["sevenDayUtilization"]
        val fiveHourResetsAt = metadata["fiveHourResetsAt"]
        val sevenDayResetsAt = metadata["sevenDayResetsAt"]

        if (fiveHourUtilization != null || sevenDayUtilization != null) {
            if (fiveHourUtilization != null) {
                val pct = fiveHourUtilization.toIntOrNull() ?: 0
                val remaining = 100 - pct
                rows.add(ProgressBarRenderer.buildUsageSection("5-hour", remaining, fiveHourResetsAt))
            }

            if (sevenDayUtilization != null) {
                val pct = sevenDayUtilization.toIntOrNull() ?: 0
                val remaining = 100 - pct
                rows.add(ProgressBarRenderer.buildUsageSection("7-day", remaining, sevenDayResetsAt))
            }
        } else {
            val sessionUsed = metadata["sessionUsed"]
            val weekUsed = metadata["weekUsed"]
            val sessionResetsAt = metadata["sessionResetsAt"]
            val weekResetsAt = metadata["weekResetsAt"]

            if (sessionUsed != null) {
                val sessionPct = sessionUsed.toIntOrNull() ?: 0
                val remaining = 100 - sessionPct
                rows.add(ProgressBarRenderer.buildUsageSection("5-hour", remaining, sessionResetsAt))
            }

            if (weekUsed != null) {
                val weekPct = weekUsed.toIntOrNull() ?: 0
                val remaining = 100 - weekPct
                rows.add(ProgressBarRenderer.buildUsageSection("Weekly", remaining, weekResetsAt))
            }

            if (sessionUsed == null && weekUsed == null) {
                rows.add(infoRowHtml("Usage data unavailable"))
            }
        }

        val status = metadata["status"]
        if (status != null) {
            rows.add(infoRowHtml("Status: ${status.escapeHtml()}"))
        }
    }

    private fun appendXiaomiTokenPlanRows(
        rows: MutableList<String>,
        metadata: Map<String, String>?,
        tokens: Tokens?
    ) {
        if (metadata == null && tokens == null) {
            rows.add(infoRowHtml("No usage data"))
            return
        }

        val usedPercent = metadata?.get("sessionUsed")?.toIntOrNull()
        if (usedPercent != null) {
            val remaining = 100 - usedPercent
            rows.add(ProgressBarRenderer.buildBalanceSection("Credits", remaining))
        }

        val planUsed = tokens?.used
        val planTotal = tokens?.total
        if (planUsed != null && planTotal != null && planTotal > 0) {
            val usedFormatted = BalanceFormatter.formatShortCredits(planUsed)
            val totalFormatted = BalanceFormatter.formatShortCredits(planTotal)
            rows.add(labelValueRowHtml("Used:", "$usedFormatted / $totalFormatted"))
        }
    }

    private fun appendCreditsRows(
        rows: MutableList<String>,
        credits: Credits?,
        connectionType: ConnectionType
    ) {
        if (credits == null) {
            rows.add(infoRowHtml("No balance data"))
            return
        }

        val remaining = credits.remaining
        val used = credits.used

        when {
            remaining != null -> {
                val formatted = formatAmount(remaining)
                if (used != null && used > BigDecimal.ZERO) {
                    rows.add(labelValueRowHtml("Remaining:", formatted, bold = true))
                    rows.add(labelValueRowHtml("Used:", formatAmount(used)))
                } else {
                    rows.add(labelValueRowHtml("Balance:", formatted, bold = true))
                }
            }
            used != null -> rows.add(labelValueRowHtml("Used:", formatAmount(used)))
            else -> rows.add(infoRowHtml("No balance data"))
        }
    }

    /**
     * Render the Cline tooltip block: existing Cline credit balance rows, plus an
     * optional ClinePass usage subsection when the provider client populated
     * plan-usage metadata. If ClinePass metadata is absent (e.g. the user has
     * no ClinePass plan), only the existing balance rows are shown.
     *
     * The ClinePass subsection is rendered as a visually distinct block:
     *   - spacer row
     *   - muted section header ("ClinePass")
     *   - one progress row per metric (label | bar + percent)
     *   - one subtle reset row per metric (colspan=2, indented timestamp)
     */
    private fun appendClineRows(
        rows: MutableList<String>,
        credits: Credits?,
        metadata: Map<String, String>
    ) {
        appendCreditsRows(rows, credits, ConnectionType.CLINE_API)

        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)
        if (metrics.isEmpty()) return

        // Spacer before ClinePass subsection
        rows.add(spacerRowHtml(4))
        // Section header
        rows.add(sectionHeaderRowHtml(ClinePassUsageRenderer.SECTION_TITLE))

        // One progress row + one reset row per metric
        for (metric in metrics) {
            val clampedPercent = metric.percent.coerceIn(0, 100)
            rows.add(
                ProgressBarRenderer.buildUsageSectionNoReset(metric.label, clampedPercent)
            )
            if (metric.resetAt != null && metric.resetAt.isNotBlank()) {
                rows.add(resetInfoRowHtml(metric.resetAt))
            }
        }
    }

    /**
     * Renders a single label/value row.
     *
     * The value cell is allowed to wrap so that long dollar amounts or
     * email addresses do not blow out the tooltip width. The label cell
     * stays nowrap so that the column of left-hand labels lines up.
     */
    private fun labelValueRowHtml(label: String, value: String, bold: Boolean = false): String {
        val valueStyle = if (bold) "font-weight:bold;" else ""
        return "<tr>" +
            "<td style=\"padding:2px 8px 2px 0;color:#888888;white-space:nowrap;vertical-align:top;\">${label.escapeHtml()}</td>" +
            "<td style=\"padding:2px 0;${valueStyle}word-wrap:break-word;overflow-wrap:break-word;\">${value.escapeHtml()}</td>" +
            "</tr>"
    }

    private fun errorRowHtml(message: String, isError: Boolean = false, isWarning: Boolean = false): String {
        val color = when {
            isError -> "#CC4444"
            isWarning -> "#CC8800"
            else -> "#888888"
        }
        return "<tr><td colspan=\"2\" style=\"padding:2px 0;color:$color;font-size:11px;word-wrap:break-word;\">${message.escapeHtml()}</td></tr>"
    }

    private fun infoRowHtml(message: String): String {
        return "<tr><td colspan=\"2\" style=\"padding:2px 0;color:#888888;font-size:11px;font-style:italic;word-wrap:break-word;\">${message.escapeHtml()}</td></tr>"
    }

    /**
     * A thin spacer row (colspan=2) with the given padding in pixels.
     */
    private fun spacerRowHtml(paddingPx: Int): String {
        return "<tr><td colspan=\"2\" style=\"padding:${paddingPx}px 0;\"></td></tr>"
    }

    /**
     * A muted section-header row (colspan=2) used to separate logical subsections
     * like ClinePass within the Cline account block.
     */
    private fun sectionHeaderRowHtml(title: String): String {
        return "<tr><td colspan=\"2\" style=\"padding-top:4px;\"><font color=\"#BBBBBB\"><b>$title</b></font></td></tr>"
    }

    /**
     * A subtle reset-info row (colspan=2) that renders a timestamp beneath a
     * progress row, avoiding the awkward inline wrapping that previously occurred.
     */
    private fun resetInfoRowHtml(resetAt: String): String {
        return "<tr><td colspan=\"2\" style=\"padding:1px 0 1px 16px;color:#999999;font-size:10px;font-style:italic;word-wrap:break-word;\">${resetAt.escapeHtml()}</td></tr>"
    }

    private fun getAuthErrorMessage(
        authError: ProviderResult.Failure.AuthError,
        connectionType: ConnectionType
    ): String {
        if (connectionType == ConnectionType.CODEX_CLI) {
            return authError.message
        }
        val message = authError.message
        return when {
            message.contains("expired", ignoreCase = true) -> "Session expired"
            message.contains("not authenticated", ignoreCase = true) -> "Not authenticated"
            message.contains("missing", ignoreCase = true) -> "Credentials missing"
            message.contains("invalid", ignoreCase = true) -> "Invalid credentials"
            else -> message
        }
    }

    private fun formatAmount(amount: BigDecimal): String {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength - 1) + "\u2026"
        } else {
            text
        }
    }

    private fun String.escapeHtml(): String {
        return this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
