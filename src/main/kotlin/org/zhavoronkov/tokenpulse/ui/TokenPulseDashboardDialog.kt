package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dashboard dialog showing all configured provider accounts with their current balances.
 *
 * Columns: Provider | API Key | Status | Last Updated | Credits
 */
class TokenPulseDashboardDialog(project: Project) : DialogWrapper(project) {

    private val tableModel = DashboardTableModel()
    private val table = JBTable(tableModel)

    companion object {
        private const val DIALOG_WIDTH = 700
        private const val DIALOG_HEIGHT = 400

        // Column indices (must match DashboardTableModel order)
        private const val COL_PROVIDER = 0
        private const val COL_KEY_PREVIEW = 1
        private const val COL_STATUS = 2
        private const val COL_LAST_UPDATED = 3
        private const val COL_CREDITS = 4

        // Column preferred widths (px, before JBUI scaling)
        private const val COL_PROVIDER_WIDTH = 110
        private const val COL_KEY_PREVIEW_WIDTH = 170
        private const val COL_STATUS_WIDTH = 90
        private const val COL_LAST_UPDATED_WIDTH = 110
        private const val COL_CREDITS_WIDTH = 110
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

        configureTable()

        val scrollPane = JBScrollPane(table)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun configureTable() {
        table.fillsViewportHeight = true
        table.autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS

        val cm = table.columnModel
        cm.getColumn(COL_PROVIDER).preferredWidth = JBUI.scale(COL_PROVIDER_WIDTH)
        cm.getColumn(COL_KEY_PREVIEW).preferredWidth = JBUI.scale(COL_KEY_PREVIEW_WIDTH)
        cm.getColumn(COL_STATUS).preferredWidth = JBUI.scale(COL_STATUS_WIDTH)
        cm.getColumn(COL_LAST_UPDATED).preferredWidth = JBUI.scale(COL_LAST_UPDATED_WIDTH)
        cm.getColumn(COL_CREDITS).preferredWidth = JBUI.scale(COL_CREDITS_WIDTH)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun doOKAction() {
        BalanceRefreshService.getInstance().refreshAll(force = true)
        // Keep dialog open after refresh so user can see results
    }
}
