package org.zhavoronkov.tokenpulse.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.ui.TokenPulseConfigurable
import org.zhavoronkov.tokenpulse.ui.TokenPulseDashboardDialog
import org.zhavoronkov.tokenpulse.utils.Constants

class ShowDashboardAction : DumbAwareAction("Show ${Constants.DISPLAY_NAME} Dashboard") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        TokenPulseDashboardDialog(project).show()
    }
}

class RefreshAllAction : DumbAwareAction("Refresh All Token Balances") {
    override fun actionPerformed(e: AnActionEvent) {
        BalanceRefreshService.getInstance().refreshAll(force = true)
    }
}

class OpenSettingsAction : DumbAwareAction("${Constants.DISPLAY_NAME} Settings...") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, TokenPulseConfigurable::class.java)
    }
}
