package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.NebiusBalanceBreakdown
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.service.BalanceUpdatedListener
import org.zhavoronkov.tokenpulse.service.BalanceUpdatedTopic
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.StatusBarDisplayMode
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.math.BigDecimal
import java.math.RoundingMode

class TokenPulseStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "TokenPulse"
    override fun getDisplayName(): String = "TokenPulse β"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = TokenPulseStatusBarWidget()
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class TokenPulseStatusBarWidget : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var myStatusBar: StatusBar? = null

    companion object {
        private const val MAX_DISPLAY_LENGTH = 20
    }

    override fun ID(): String = "TokenPulse"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val settings = TokenPulseSettingsService.getInstance().state
        val accounts = settings.accounts
        val enabledAccounts = accounts.filter { it.isEnabled }

        // No accounts configured
        if (enabledAccounts.isEmpty()) return "TP: --"

        val results = BalanceRefreshService.getInstance().results.value
        val enabledAccountIds = enabledAccounts.map { it.id }.toSet()

        // Check if we're still loading (no results yet for any enabled account)
        val hasAnyResults = enabledAccountIds.any { it in results }
        if (!hasAnyResults) {
            return "TP: ..."
        }

        // Filter results to only include enabled accounts
        val activeResults = results.filterKeys { it in enabledAccountIds }

        // Get successful results only
        val successfulResults = activeResults.filterValues { it is ProviderResult.Success }
            .mapValues { it.value as ProviderResult.Success }

        if (successfulResults.isEmpty()) {
            return "TP: --"
        }

        val format = settings.statusBarFormat

        return when (settings.statusBarDisplayMode) {
            StatusBarDisplayMode.AUTO -> formatAutoMode(enabledAccounts, successfulResults, format)
            StatusBarDisplayMode.TOTAL_DOLLARS -> formatTotalDollars(successfulResults, format)
            StatusBarDisplayMode.SINGLE_PROVIDER -> formatSingleProvider(
                settings,
                enabledAccounts,
                successfulResults,
                format
            )
        }
    }

    /**
     * AUTO mode: Adapts display based on first enabled provider type.
     * - If first provider is usage-percentage type (Claude/ChatGPT), show usage percentages
     * - Otherwise, show data from first dollar-based provider
     */
    private fun formatAutoMode(
        enabledAccounts: List<Account>,
        successfulResults: Map<String, ProviderResult.Success>,
        format: org.zhavoronkov.tokenpulse.settings.StatusBarFormat
    ): String {
        val settings = TokenPulseSettingsService.getInstance().state
        val firstAccount = enabledAccounts.firstOrNull() ?: return "TP: --"
        val firstResult = successfulResults[firstAccount.id]

        // Check if first provider is usage-percentage type
        if (BalanceFormatter.isUsagePercentageType(firstAccount.connectionType)) {
            // Show usage percentage for the first provider
            return if (firstResult != null) {
                "TP: ${BalanceFormatter.formatUsagePercentageForStatusBar(firstResult, format)}"
            } else {
                "TP: --"
            }
        }

        // First provider is dollar-based - show with new dollar format
        if (firstResult != null) {
            val credits = firstResult.snapshot.balance.credits
            if (credits != null) {
                val provider = firstAccount.connectionType.provider
                return "TP: ${BalanceFormatter.formatCreditsForStatusBar(
                    credits,
                    settings.statusBarDollarFormat,
                    provider
                )}"
            }
        }

        return "TP: --"
    }

    /**
     * TOTAL_DOLLARS mode: Sum all dollar balances across providers.
     */
    private fun formatTotalDollars(
        successfulResults: Map<String, ProviderResult.Success>,
        format: org.zhavoronkov.tokenpulse.settings.StatusBarFormat
    ): String {
        // Sum remaining credits (exclude usage-percentage providers)
        val dollarResults = successfulResults.values.filter {
            !BalanceFormatter.isUsagePercentageType(it.snapshot.connectionType)
        }

        val totalRemaining = dollarResults
            .mapNotNull { it.snapshot.balance.credits?.remaining }
            .fold(BigDecimal.ZERO) { acc, amount -> acc.add(amount) }

        val totalUsed = dollarResults
            .mapNotNull { it.snapshot.balance.credits?.used }
            .fold(BigDecimal.ZERO) { acc, amount -> acc.add(amount) }

        val providerCount = dollarResults.size

        return when {
            totalRemaining > BigDecimal.ZERO -> {
                "TP: ${BalanceFormatter.formatTotalDollarsForStatusBar(totalRemaining, format, providerCount)}"
            }
            totalUsed > BigDecimal.ZERO -> {
                "TP: ${BalanceFormatter.formatUsedDollarsForStatusBar(totalUsed, format)}"
            }
            else -> "TP: --"
        }
    }

    /**
     * SINGLE_PROVIDER mode: Show data from a specific provider.
     */
    private fun formatSingleProvider(
        settings: org.zhavoronkov.tokenpulse.settings.TokenPulseSettings,
        enabledAccounts: List<Account>,
        successfulResults: Map<String, ProviderResult.Success>,
        format: org.zhavoronkov.tokenpulse.settings.StatusBarFormat
    ): String {
        // Find the primary account (specified or first enabled)
        val primaryAccount = if (settings.statusBarPrimaryAccountId != null) {
            enabledAccounts.find { it.id == settings.statusBarPrimaryAccountId }
        } else {
            null
        } ?: enabledAccounts.firstOrNull() ?: return "TP: --"

        val result = successfulResults[primaryAccount.id] ?: return "TP: --"
        val provider = primaryAccount.connectionType.provider

        // Format based on provider type
        return if (BalanceFormatter.isUsagePercentageType(primaryAccount.connectionType)) {
            "TP: ${BalanceFormatter.formatUsagePercentageForStatusBar(result, format, provider)}"
        } else {
            val credits = result.snapshot.balance.credits
            if (credits != null) {
                "TP: ${BalanceFormatter.formatCreditsForStatusBar(credits, settings.statusBarDollarFormat, provider)}"
            } else {
                "TP: --"
            }
        }
    }

    /**
     * Truncate a string to max length with ellipsis if needed.
     */
    private fun truncate(text: String, maxLength: Int = MAX_DISPLAY_LENGTH): String {
        return if (text.length > maxLength) {
            text.take(maxLength - 1) + "…"
        } else {
            text
        }
    }

    override fun getTooltipText(): String {
        val results = BalanceRefreshService.getInstance().results.value
        if (results.isEmpty()) return buildSimpleTooltip("No accounts configured", null)

        val accounts = TokenPulseSettingsService.getInstance().state.accounts
        val activeAccounts = accounts.filter { it.isEnabled }
        if (activeAccounts.isEmpty()) return buildSimpleTooltip("All accounts disabled", "Open settings to enable")

        val enabledAccountIds = activeAccounts.map { it.id }.toSet()
        val activeResults = results.filterKeys { it in enabledAccountIds }.values

        val successCount = activeResults.filterIsInstance<ProviderResult.Success>().count()
        val errorCount = activeResults.filterIsInstance<ProviderResult.Failure>().count()

        val rows = buildString {
            // Header summary
            append("<tr><td colspan='2' style='padding-bottom: 4px;'>")
            append("<b>TokenPulse β Summary</b></td></tr>")
            append("<tr><td>Active:</td><td align='right'>$successCount connected")
            val errSuffix = if (errorCount > 1) "s" else ""
            if (errorCount > 0) append(", <font color='#CC4444'>$errorCount error$errSuffix</font>")
            append("</td></tr>")
            append("<tr height='4'><td></td></tr>")

            // Per-account rows (sorted alphabetically by full display name)
            activeAccounts.sortedBy { it.connectionType.fullDisplayName }.forEach { account ->
                val result = results[account.id]
                val label = account.name.ifBlank { account.connectionType.fullDisplayName }

                // Account name as group header
                append("<tr><td colspan='2' style='border-top: 1px solid #555; ")
                append("padding-top: 4px;'><b>$label</b></td></tr>")

                when (result) {
                    is ProviderResult.Success -> {
                        val snapshot = result.snapshot
                        val breakdown = snapshot.nebiusBreakdown

                        if (snapshot.connectionType == ConnectionType.NEBIUS_BILLING && breakdown != null) {
                            appendNebiusBreakdownRows(breakdown)
                        } else if (snapshot.connectionType ==
                            ConnectionType.CODEX_CLI
                        ) {
                            appendCodexRows(snapshot.metadata)
                        } else if (snapshot.connectionType ==
                            ConnectionType.CLAUDE_CODE
                        ) {
                            appendClaudeCodeRows(snapshot.metadata)
                        } else {
                            appendCreditsRows(snapshot.balance.credits, snapshot.connectionType)
                        }
                    }
                    is ProviderResult.Failure.AuthError -> {
                        // Show the actual error message instead of generic "Session expired"
                        val errorMessage = getAuthErrorMessage(result, account.connectionType)
                        append("<tr><td colspan='2'><font color='#CC4444'>$errorMessage</font></td></tr>")
                    }
                    is ProviderResult.Failure.RateLimited ->
                        append("<tr><td colspan='2'><font color='#CC8800'>Rate limited (retry later)</font></td></tr>")
                    is ProviderResult.Failure.NetworkError ->
                        append("<tr><td colspan='2'><font color='#CC4444'>Network error</font></td></tr>")
                    is ProviderResult.Failure ->
                        append("<tr><td colspan='2'><font color='#CC4444'>Connection error</font></td></tr>")
                    null ->
                        append("<tr><td colspan='2'><font color='gray'>Refreshing...</font></td></tr>")
                }
                append("<tr height='6'><td></td></tr>")
            }

            // Minimal hint
            append("<tr><td colspan='2' style='border-top: 1px solid #555; ")
            append("padding-top: 4px;'><font size='-2' color='gray'>")
            append("Click for Dashboard • Refresh • Settings</font></td></tr>")
        }

        return "<html><table border='0' cellpadding='0' cellspacing='0' style='min-width: 180px;'>$rows</table></html>"
    }

    private fun StringBuilder.appendNebiusBreakdownRows(breakdown: NebiusBalanceBreakdown) {
        val paid = breakdown.paidRemaining
        val trial = breakdown.trialRemaining
        val tenant = breakdown.tenantName

        if (tenant != null) {
            append("<tr><td>Account:</td><td align='right'>${truncate(tenant)}</td></tr>")
        }

        when {
            paid != null && trial != null -> {
                val total = paid + trial
                append("<tr><td>Total:</td><td align='right'><b>${formatAmount(total)}</b></td></tr>")
                append("<tr><td>Paid:</td><td align='right'>${formatAmount(paid)}</td></tr>")
                append("<tr><td>Trial:</td><td align='right'>${formatAmount(trial)}</td></tr>")
            }
            paid != null -> append("<tr><td>Paid:</td><td align='right'><b>${formatAmount(paid)}</b></td></tr>")
            trial != null -> append("<tr><td>Trial:</td><td align='right'><b>${formatAmount(trial)}</b></td></tr>")
            else -> append("<tr><td colspan='2'><i>No balance data</i></td></tr>")
        }
    }

    /**
     * Render Codex CLI rate limit usage from metadata.
     * Shows 5h/weekly usage percentages with progress bars when available.
     */
    private fun StringBuilder.appendCodexRows(metadata: Map<String, String>?) {
        if (metadata == null) {
            append("<tr><td colspan='2'><i>No usage data</i></td></tr>")
            return
        }

        // Show plan type
        val planType = metadata["planType"]
        if (planType != null && planType != "unknown") {
            append("<tr><td>Plan:</td><td align='right'>$planType</td></tr>")
        }

        // Show account email (truncated)
        val email = metadata["email"]
        if (email != null && email != "unknown") {
            append("<tr><td>Account:</td><td align='right'>${truncate(email)}</td></tr>")
        }

        // Show rate limit usage with progress bars if available (from Codex)
        // Codex reports usedPercent, so we invert to show "X% left" to match /status output
        val fiveHourUsed = metadata["fiveHourUsed"]
        val weeklyUsed = metadata["weeklyUsed"]
        val codeReviewUsed = metadata["codeReviewUsed"]

        val hasRateLimits = (fiveHourUsed != null && fiveHourUsed != "N/A") ||
            (weeklyUsed != null && weeklyUsed != "N/A") ||
            (codeReviewUsed != null && codeReviewUsed != "N/A")

        if (hasRateLimits) {
            if (fiveHourUsed != null && fiveHourUsed != "N/A") {
                val usedPct = fiveHourUsed.toFloatOrNull()?.toInt() ?: 0
                val remainingPct = (100 - usedPct).coerceIn(0, 100)
                append(ProgressBarRenderer.buildBalanceSection("5-hour", remainingPct, null))
            }

            if (weeklyUsed != null && weeklyUsed != "N/A") {
                val usedPct = weeklyUsed.toFloatOrNull()?.toInt() ?: 0
                val remainingPct = (100 - usedPct).coerceIn(0, 100)
                append(ProgressBarRenderer.buildBalanceSection("Weekly", remainingPct, null))
            }

            if (codeReviewUsed != null && codeReviewUsed != "N/A") {
                val usedPct = codeReviewUsed.toFloatOrNull()?.toInt() ?: 0
                val remainingPct = (100 - usedPct).coerceIn(0, 100)
                append(ProgressBarRenderer.buildBalanceSection("Code Review", remainingPct, null))
            }
        } else {
            // No rate limits available - check why and show appropriate message
            val codexEnabled = metadata["codexEnabled"]
            val codexAvailable = metadata["codexAvailable"]
            val codexError = metadata["codexError"]
            val codexErrorDetail = metadata["codexErrorDetail"]

            if (codexEnabled == "true") {
                // Codex was enabled but something went wrong - always show a message
                val errorMsg = when (codexError) {
                    "not_installed" -> "Codex CLI not installed"
                    "not_authenticated" -> "Codex not logged in"
                    "app_server_start_failed" -> "Codex app-server failed"
                    "rate_limits_unavailable" -> "Rate limits unavailable"
                    "token_expired" -> "Codex session expired"
                    else -> "Usage data unavailable"
                }
                append("<tr><td colspan='2'>")
                append("<font size='-2' color='gray'>$errorMsg")
                // Show error detail if available (truncated for readability)
                codexErrorDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                    append(": ${truncate(detail, 60)}")
                }
                append("</font></td></tr>")
            } else {
                // Codex not enabled - suggest enabling it for usage tracking
                if (planType?.lowercase() != "free") {
                    append("<tr><td colspan='2'><font size='-2' color='gray'>")
                    append("Enable Codex for usage tracking</font></td></tr>")
                }
            }
        }
    }

    private fun StringBuilder.appendClaudeCodeRows(metadata: Map<String, String>?) {
        if (metadata == null || metadata.isEmpty()) {
            append("<tr><td colspan='2'><i>No usage data</i></td></tr>")
            return
        }

        // First, try OAuth data (preferred) - contains utilization + resetsAt
        val fiveHourUtilization = metadata["fiveHourUtilization"]
        val sevenDayUtilization = metadata["sevenDayUtilization"]
        val fiveHourResetsAt = metadata["fiveHourResetsAt"]
        val sevenDayResetsAt = metadata["sevenDayResetsAt"]

        if (fiveHourUtilization != null || sevenDayUtilization != null) {
            // OAuth data is available - show with progress bars
            if (fiveHourUtilization != null) {
                val pct = fiveHourUtilization.toIntOrNull() ?: 0
                append(ProgressBarRenderer.buildUsageSection("5-hour", pct, fiveHourResetsAt))
            }

            if (sevenDayUtilization != null) {
                val pct = sevenDayUtilization.toIntOrNull() ?: 0
                append(ProgressBarRenderer.buildUsageSection("7-day", pct, sevenDayResetsAt))
            }
        } else {
            // Fallback to CLI-parsed data (5-hour/weekly percentages)
            val sessionUsed = metadata["sessionUsed"]
            val weekUsed = metadata["weekUsed"]
            val sessionResetsAt = metadata["sessionResetsAt"]
            val weekResetsAt = metadata["weekResetsAt"]

            // Show each section only if we have data for it
            if (sessionUsed != null) {
                val sessionPct = sessionUsed.toIntOrNull() ?: 0
                // Use "5-hour" label to match OAuth data and status bar format
                append(ProgressBarRenderer.buildUsageSection("5-hour", sessionPct, sessionResetsAt))
            }

            if (weekUsed != null) {
                val weekPct = weekUsed.toIntOrNull() ?: 0
                append(ProgressBarRenderer.buildUsageSection("Weekly", weekPct, weekResetsAt))
            }

            // If no data at all, show a helpful message
            if (sessionUsed == null && weekUsed == null) {
                append("<tr><td colspan='2'><i>Usage data unavailable</i></td></tr>")
            }
        }

        // Show status if available
        val status = metadata["status"]
        if (status != null) {
            append("<tr><td colspan='2' style='padding-top: 4px;'>")
            append("<font size='-2' color='gray'>Status: $status</font></td></tr>")
        }
    }

    /**
     * Render credit balance rows for dollar-based providers (OpenRouter, Cline, OpenAI Platform).
     * Shows remaining balance and used amount when available.
     */
    private fun StringBuilder.appendCreditsRows(
        credits: org.zhavoronkov.tokenpulse.model.Credits?,
        @Suppress("UNUSED_PARAMETER") connectionType: ConnectionType
    ) {
        if (credits == null) {
            append("<tr><td colspan='2'><i>No balance data</i></td></tr>")
            return
        }

        val remaining = credits.remaining
        val used = credits.used

        when {
            // We have remaining balance
            remaining != null -> {
                val formatted = formatAmount(remaining)

                // Show remaining and used if both available
                if (used != null && used > BigDecimal.ZERO) {
                    append("<tr><td>Remaining:</td><td align='right'><b>$formatted</b></td></tr>")
                    append("<tr><td>Used:</td><td align='right'>${formatAmount(used)}</td></tr>")
                } else {
                    // Just show balance
                    append("<tr><td>Balance:</td><td align='right'><b>$formatted</b></td></tr>")
                }
            }
            // Only used is available (like OpenAI Platform)
            used != null -> {
                append("<tr><td>Used:</td><td align='right'>${formatAmount(used)}</td></tr>")
            }
            else -> {
                append("<tr><td colspan='2'><i>No balance data</i></td></tr>")
            }
        }
    }

    private fun buildSimpleTooltip(message: String, hint: String?): String {
        val hintRow = if (hint != null) "<tr><td colspan='2'><i>$hint</i></td></tr>" else ""
        return """
            <html>
            <table border='0' cellpadding='1' cellspacing='0'>
              <tr><td colspan='2'><b>TokenPulse β</b></td></tr>
              <tr height='2'><td></td></tr>
              <tr><td colspan='2'>$message</td></tr>
              $hintRow
            </table>
            </html>
        """.trimIndent()
    }

    /**
     * Get a user-friendly error message for AuthError failures.
     * For Codex, shows the actual error message instead of generic "Session expired".
     */
    private fun getAuthErrorMessage(
        authError: ProviderResult.Failure.AuthError,
        connectionType: ConnectionType
    ): String {
        // For Codex, show the actual message since it has specific auth states
        if (connectionType == ConnectionType.CODEX_CLI) {
            return authError.message
        }
        // For other providers, use the message but with some cleanup
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
        return "\$${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val component = event.component ?: return@Consumer
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return@Consumer

        val popup = createPopupMenu(project)
        popup.show(RelativePoint(component, Point(0, 0)))
    }

    private fun createPopupMenu(project: Project): ListPopup {
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup()

        group.add(object : com.intellij.openapi.project.DumbAwareAction("Show Dashboard") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                TokenPulseDashboardDialog(project).show()
            }
        })

        group.add(object : com.intellij.openapi.project.DumbAwareAction("Refresh All") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                BalanceRefreshService.getInstance().refreshAll(force = true)
            }
        })

        group.addSeparator()

        group.add(object : com.intellij.openapi.project.DumbAwareAction("Open Settings") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, TokenPulseConfigurable::class.java)
            }
        })

        return JBPopupFactory.getInstance().createActionGroupPopup(
            "TokenPulse β",
            group,
            com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false
        )
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun install(statusBar: StatusBar) {
        myStatusBar = statusBar
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(
                BalanceUpdatedTopic.TOPIC,
                object : BalanceUpdatedListener {
                    override fun balanceUpdated(accountId: String, result: ProviderResult) {
                        statusBar.updateWidget(ID())
                    }
                }
            )
    }

    override fun dispose() {
        myStatusBar = null
    }
}
