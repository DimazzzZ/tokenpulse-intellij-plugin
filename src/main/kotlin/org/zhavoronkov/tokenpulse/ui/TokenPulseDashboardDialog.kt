package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog that shows a table with all configured token balances.
 */
class TokenPulseDashboardDialog(project: Project) : DialogWrapper(project) {
    private val tableModel = AccountTableModel()
    private val table = JBTable(tableModel)

    companion object {
        private const val DIALOG_WIDTH = 600
        private const val DIALOG_HEIGHT = 400
    }

    init {
        title = "TokenPulse Dashboard"
        setOKButtonText("Refresh All")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)

        tableModel.items = TokenPulseSettingsService.getInstance().state.accounts.toMutableList()

        val scrollPane = JBScrollPane(table)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, cancelAction)
    }

    override fun doOKAction() {
        BalanceRefreshService.getInstance().refreshAll(force = true)
        // Keep dialog open after refresh
    }
}
