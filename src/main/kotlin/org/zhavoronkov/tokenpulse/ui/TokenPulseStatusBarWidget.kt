package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import java.awt.Component
import java.awt.event.MouseEvent

class TokenPulseStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "TokenPulse"
    override fun getDisplayName(): String = "TokenPulse"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = TokenPulseStatusBarWidget()
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class TokenPulseStatusBarWidget : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID(): String = "TokenPulse"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val snapshots = BalanceRefreshService.getInstance().snapshots.value
        if (snapshots.isEmpty()) return "TokenPulse: --"
        
        // Show total credits as a summary if available
        val totalCredits = snapshots.values
            .map { it.balance }
            .filterIsInstance<org.zhavoronkov.tokenpulse.model.Balance.CreditsUsd>()
            .fold(java.math.BigDecimal.ZERO) { acc, balance -> acc.add(balance.amount) }
        
        return "TP: ${BalanceFormatter.format(org.zhavoronkov.tokenpulse.model.Balance.CreditsUsd(totalCredits))}"
    }

    override fun getTooltipText(): String = "Click to open TokenPulse Dashboard"
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull() ?: return@Consumer
        TokenPulseDashboardDialog(project).show()
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT
    override fun install(statusBar: StatusBar) {}
    override fun dispose() {}
}
