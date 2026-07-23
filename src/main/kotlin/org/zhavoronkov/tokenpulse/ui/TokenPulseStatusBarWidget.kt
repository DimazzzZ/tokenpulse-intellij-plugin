package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.IdeTooltip
import com.intellij.ide.IdeTooltipManager
import com.intellij.ide.TooltipEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.service.BalanceUpdatedListener
import org.zhavoronkov.tokenpulse.service.BalanceUpdatedTopic
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.StatusBarDisplayMode
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.math.BigDecimal
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class TokenPulseStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "TokenPulse"
    override fun getDisplayName(): String = org.zhavoronkov.tokenpulse.utils.Constants.DISPLAY_NAME
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = TokenPulseStatusBarWidget()
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

/**
 * Status bar widget that shows a compact status text and a rich tooltip on hover.
 *
 * The tooltip is a real Swing panel ([TokenPulseTooltipPanel]) attached via
 * [IdeTooltipManager.setCustomTooltip]. IntelliJ's tooltip manager drives
 * hover show/hide (mouse-enter shows, mouse-exit hides) and clamps the balloon
 * to the screen, so bars and percentages never wrap. Implementing
 * [CustomStatusBarWidget] lets the widget own its on-screen [JComponent] — the
 * only reliable seam to attach a custom tooltip, since [StatusBar] in 2024.2
 * has no `getWidgetComponent(id)`.
 */
class TokenPulseStatusBarWidget :
    StatusBarWidget,
    StatusBarWidget.TextPresentation,
    CustomStatusBarWidget {
    private var myStatusBar: StatusBar? = null
    private var myComponent: WidgetLabel? = null

    override fun ID(): String = "TokenPulse"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    // A plain-text fallback for accessibility; the rich hover tooltip is the
    // custom Swing panel registered in install(). Returns null so the platform
    // does not build its own HTML tooltip over our custom one.
    override fun getTooltipText(): String? = null

    // Note: for CustomStatusBarWidget implementations the platform does NOT
    // auto-invoke TextPresentation.getClickConsumer() — input is routed to the
    // widget's own component instead. The left-click popup is wired via a
    // MouseListener attached to WidgetLabel in registerCustomTooltip().

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String = formatStatusBarText()

    override fun getComponent(): JComponent {
        val existing = myComponent
        if (existing != null) return existing
        val label = WidgetLabel(this)
        myComponent = label
        return label
    }

    override fun install(statusBar: StatusBar) {
        myStatusBar = statusBar
        registerCustomTooltip()
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(
                BalanceUpdatedTopic.TOPIC,
                object : BalanceUpdatedListener {
                    override fun balanceUpdated(
                        accountId: String,
                        result: org.zhavoronkov.tokenpulse.model.ProviderResult
                    ) {
                        statusBar.updateWidget(ID())
                        myComponent?.refresh()
                    }
                }
            )
    }

    /**
     * Registers the rich hover tooltip on the widget's own component. The
     * tooltip component is rebuilt with fresh data every time it is about to
     * show (via [IdeTooltip.beforeShow]); when there is no data yet, it falls
     * back to a short text message so a tooltip still appears.
     */
    private fun registerCustomTooltip() {
        val comp = getComponent()
        val tooltip = object : IdeTooltip(comp, Point(0, 0), fallbackTip()) {
            override fun beforeShow(): Boolean {
                setTipComponent(TokenPulseTooltipPanel.buildTooltip() ?: fallbackTip())
                return true
            }

            // Show almost immediately on hover instead of the IDE-global
            // ~1.2s default (registry key ide.tooltip.initialDelay). Scoped to
            // this tooltip only; the global registry is untouched.
            override fun getShowDelay(): Int = SHOW_DELAY_MS

            override fun getInitialReshowDelay(): Int = SHOW_DELAY_MS

            // Keep the tooltip open while the cursor is still over the widget
            // or inside the balloon. IdeTooltipManager consults this on mouse
            // events; the default (true) hides on movement, which made the
            // tooltip vanish while hovering. We only allow auto-hide for real
            // exits/clicks/keys — not for mouse-moves that remain on the widget.
            override fun canAutohideOn(event: TooltipEvent): Boolean {
                if (event.isIsEventInsideBalloon) return false
                val input = event.inputEvent
                if (input is MouseEvent && input.id == MouseEvent.MOUSE_MOVED) {
                    val src = input.component ?: return true
                    val pointOnWidget = SwingUtilities.convertPoint(src, input.point, comp)
                    if (comp.isShowing && comp.contains(pointOnWidget)) return false
                }
                return true
            }

            // Disable the platform's timed auto-close (registry
            // ide.tooltip.dismissDelay, ~3-5s), which fires independently of
            // canAutohideOn. With this off, the tooltip stays open while
            // hovered and only hides when the cursor leaves (via canAutohideOn).
            override fun canBeDismissedOnTimeout(): Boolean = false
        }
            .setPreferredPosition(Balloon.Position.above)
            .setHint(false)
            .setLayer(Balloon.Layer.top)
        // Left-click opens the actions popup (Dashboard / Refresh All /
        // Settings). Since this widget is a CustomStatusBarWidget the platform
        // ignores getClickConsumer(); we must wire the click ourselves.
        comp.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    ?: return
                // Anchor the popup ABOVE the status bar so it doesn't overlap the widget row.
                // Use the popup's own preferred height so the offset stays correct across
                // themes and screen scales (no magic constant). Matches the fix in the sibling
                // openrouter-intellij-plugin (OpenRouterStatusBarWidget.showPopupMenu).
                val popup = createPopupMenu(project)
                val popupHeight = popup.content.preferredSize.height
                val originOnScreen = e.component.locationOnScreen
                val anchor = Point(originOnScreen.x, originOnScreen.y - popupHeight)
                popup.show(RelativePoint.fromScreen(anchor))
            }
        })
        IdeTooltipManager.getInstance().setCustomTooltip(comp, tooltip)
    }

    /** Short text shown when there is no per-account data to render yet. */
    private fun fallbackTip(): JComponent {
        val accounts = TokenPulseSettingsService.getInstance().state.accounts
        val activeAccounts = accounts.filter { it.isEnabled }
        val text = when {
            activeAccounts.isEmpty() -> "TokenPulse: All accounts disabled"
            else -> "TokenPulse: ${activeAccounts.size} account(s) configured \u2014 refreshing..."
        }
        return JBLabel(text).apply {
            border = JBUI.Borders.empty(6, 8)
            background = UIUtil.getToolTipBackground()
            isOpaque = true
        }
    }

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

    override fun dispose() {
        myComponent?.let { IdeTooltipManager.getInstance().setCustomTooltip(it, null) }
        myComponent = null
        myStatusBar = null
    }

    private companion object {
        /** Near-instant hover show/reshow delay (ms) for the custom tooltip. */
        const val SHOW_DELAY_MS = 50
    }
}

/**
 * The on-screen status-bar label. Uses a plain [JBLabel] (public API) with
 * status-bar-appropriate styling, then refreshes from
 * [TokenPulseStatusBarWidget.getText] when balance data changes.
 */
private class WidgetLabel(private val widget: TokenPulseStatusBarWidget) : JBLabel() {
    init {
        horizontalAlignment = SwingConstants.CENTER
        refresh()
    }

    fun refresh() {
        text = widget.getText()
    }
}
