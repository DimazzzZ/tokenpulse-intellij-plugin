package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import javax.swing.JComponent

class TokenPulseDashboardDialog(project: Project) : DialogWrapper(project) {
    init {
        title = "TokenPulse Dashboard"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val settings = TokenPulseSettingsService.getInstance().state
        val snapshots = BalanceRefreshService.getInstance().snapshots.value

        return panel {
            row {
                button("Refresh All") {
                    BalanceRefreshService.getInstance().refreshAll()
                    close(OK_EXIT_CODE) // Refresh and close for simplicity in MVP
                }
            }
            group("Account Balances") {
                settings.accounts.forEach { account ->
                    val snapshot = snapshots[account.id]
                    row(account.name) {
                        label(snapshot?.let { BalanceFormatter.format(it.balance) } ?: "Pending...")
                        label(account.providerId.displayName).comment("Provider")
                    }
                }
            }
        }
    }
}
