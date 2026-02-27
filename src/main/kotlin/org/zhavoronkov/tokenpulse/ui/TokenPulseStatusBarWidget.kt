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
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.service.BalanceUpdatedListener
import org.zhavoronkov.tokenpulse.service.BalanceUpdatedTopic
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.math.BigDecimal

class TokenPulseStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "TokenPulse"
    override fun getDisplayName(): String = "TokenPulse"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = TokenPulseStatusBarWidget()
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class TokenPulseStatusBarWidget : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var myStatusBar: StatusBar? = null

    override fun ID(): String = "TokenPulse"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val results = BalanceRefreshService.getInstance().results.value
        if (results.isEmpty()) return "TP: --"

        // Try to get remaining credits first (balance-based providers)
        val totalRemaining = results.values
            .filterIsInstance<ProviderResult.Success>()
            .mapNotNull { it.snapshot.balance.credits?.remaining }
            .fold(BigDecimal.ZERO) { acc, amount -> acc.add(amount) }

        // If no remaining credits, sum up usage (usage-only providers like OpenAI)
        val totalUsed = results.values
            .filterIsInstance<ProviderResult.Success>()
            .mapNotNull { it.snapshot.balance.credits?.used }
            .fold(BigDecimal.ZERO) { acc, amount -> acc.add(amount) }

        return if (totalRemaining > BigDecimal.ZERO) {
            "TP: ${BalanceFormatter.format(Balance(credits = Credits(remaining = totalRemaining)))}"
        } else if (totalUsed > BigDecimal.ZERO) {
            "TP: ${BalanceFormatter.format(Balance(credits = Credits(used = totalUsed)))}"
        } else {
            "TP: --"
        }
    }

    override fun getTooltipText(): String {
        val results = BalanceRefreshService.getInstance().results.value
        if (results.isEmpty()) return "No accounts configured"

        val errors = results.values.filterIsInstance<ProviderResult.Failure>().count()
        val total = results.size

        return "TokenPulse: $total accounts ($errors errors). Click for actions."
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
            "TokenPulse",
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
