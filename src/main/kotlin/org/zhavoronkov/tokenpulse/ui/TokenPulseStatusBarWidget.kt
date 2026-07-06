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
    override fun getDisplayName(): String = org.zhavoronkov.tokenpulse.utils.Constants.DISPLAY_NAME
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = TokenPulseStatusBarWidget()
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

/**
 * Status bar widget that shows a compact status text and a rich HTML tooltip on hover.
 */
class TokenPulseStatusBarWidget : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var myStatusBar: StatusBar? = null

    override fun ID(): String = "TokenPulse"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getTooltipText(): String {
        val accounts = TokenPulseSettingsService.getInstance().state.accounts
        val activeAccounts = accounts.filter { it.isEnabled }

        if (activeAccounts.isEmpty()) return "TokenPulse: All accounts disabled"

        val results = BalanceRefreshService.getInstance().results.value
        val enabledAccountIds = activeAccounts.map { it.id }.toSet()

        // If no results have loaded yet, show a "refreshing" message instead of
        // incorrectly claiming no accounts are configured.
        if (results.isEmpty() || enabledAccountIds.none { it in results }) {
            return "TokenPulse: ${activeAccounts.size} account(s) configured — refreshing..."
        }

        return TokenPulseStatusTooltipPanel.buildTooltipHtml()
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val component = event.component ?: return@Consumer
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return@Consumer
        val popup = createPopupMenu(project)
        popup.show(RelativePoint(component, Point(0, 0)))
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String = formatStatusBarText()

    /**
     * Formats the status bar text based on current settings and results.
     */
    private fun formatStatusBarText(): String {
        val settings = TokenPulseSettingsService.getInstance().state
        val accounts = settings.accounts
        val enabledAccounts = accounts.filter { it.isEnabled }

        if (enabledAccounts.isEmpty()) return "TP: --"

        val results = BalanceRefreshService.getInstance().results.value
        val enabledAccountIds = enabledAccounts.map { it.id }.toSet()

        val hasAnyResults = enabledAccountIds.any { it in results }
        if (!hasAnyResults) return "TP: ..."

        val activeResults = results.filterKeys { it in enabledAccountIds }

        val successfulResults = activeResults
            .filterValues { it is org.zhavoronkov.tokenpulse.model.ProviderResult.Success }
            .mapValues { it.value as org.zhavoronkov.tokenpulse.model.ProviderResult.Success }

        if (successfulResults.isEmpty()) return "TP: --"

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

    private fun formatAutoMode(
        enabledAccounts: List<Account>,
        successfulResults: Map<String, org.zhavoronkov.tokenpulse.model.ProviderResult.Success>,
        format: org.zhavoronkov.tokenpulse.settings.StatusBarFormat
    ): String {
        val settings = TokenPulseSettingsService.getInstance().state

        for (account in enabledAccounts) {
            val result = successfulResults[account.id] ?: continue
            if (BalanceFormatter.isUsagePercentageType(account.connectionType)) {
                val formatted = BalanceFormatter.formatUsagePercentageForStatusBar(
                    result,
                    format,
                    null,
                    settings.statusBarDollarFormat
                )
                return "TP: $formatted"
            }
            val credits = result.snapshot.balance.credits
            if (credits != null) {
                val provider = account.connectionType.provider
                return "TP: ${BalanceFormatter.formatCreditsForStatusBar(
                    credits,
                    settings.statusBarDollarFormat,
                    provider,
                    format
                )}"
            }
        }
        return "TP: --"
    }

    private fun formatTotalDollars(
        successfulResults: Map<String, org.zhavoronkov.tokenpulse.model.ProviderResult.Success>,
        format: org.zhavoronkov.tokenpulse.settings.StatusBarFormat
    ): String {
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
            totalRemaining > BigDecimal.ZERO ->
                "TP: ${BalanceFormatter.formatTotalDollarsForStatusBar(totalRemaining, format, providerCount)}"
            totalUsed > BigDecimal.ZERO ->
                "TP: ${BalanceFormatter.formatUsedDollarsForStatusBar(totalUsed, format)}"
            else -> "TP: --"
        }
    }

    private fun formatSingleProvider(
        settings: org.zhavoronkov.tokenpulse.settings.TokenPulseSettings,
        enabledAccounts: List<Account>,
        successfulResults: Map<String, org.zhavoronkov.tokenpulse.model.ProviderResult.Success>,
        format: org.zhavoronkov.tokenpulse.settings.StatusBarFormat
    ): String {
        val primaryAccount = if (settings.statusBarPrimaryAccountId != null) {
            enabledAccounts.find { it.id == settings.statusBarPrimaryAccountId }
        } else {
            null
        } ?: enabledAccounts.firstOrNull() ?: return "TP: --"

        val activeAccount = if (successfulResults.containsKey(primaryAccount.id)) {
            primaryAccount
        } else {
            enabledAccounts.firstOrNull { successfulResults.containsKey(it.id) }
        } ?: return "TP: --"

        val result = successfulResults[activeAccount.id] ?: return "TP: --"
        val provider = activeAccount.connectionType.provider

        val formatted = if (BalanceFormatter.isUsagePercentageType(activeAccount.connectionType)) {
            BalanceFormatter.formatUsagePercentageForStatusBar(result, format, provider, settings.statusBarDollarFormat)
        } else {
            val credits = result.snapshot.balance.credits
            if (credits != null) {
                BalanceFormatter.formatCreditsForStatusBar(credits, settings.statusBarDollarFormat, provider, format)
            } else {
                "--"
            }
        }
        return if (formatted == "--") "TP: --" else "TP: $formatted"
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
            org.zhavoronkov.tokenpulse.utils.Constants.DISPLAY_NAME,
            group,
            com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false
        )
    }

    override fun install(statusBar: StatusBar) {
        myStatusBar = statusBar
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(
                BalanceUpdatedTopic.TOPIC,
                object : BalanceUpdatedListener {
                    override fun balanceUpdated(accountId: String, result: org.zhavoronkov.tokenpulse.model.ProviderResult) {
                        statusBar.updateWidget(ID())
                    }
                }
            )
    }

    override fun dispose() {
        myStatusBar = null
    }
}
