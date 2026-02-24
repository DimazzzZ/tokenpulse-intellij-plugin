package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.service.BalanceUpdatedListener
import org.zhavoronkov.tokenpulse.service.BalanceUpdatedTopic
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class TokenPulseDashboardDialog(project: Project) : DialogWrapper(project) {
    private val tableModel = DashboardTableModel()
    private val table = JBTable(tableModel)

    init {
        title = "TokenPulse Dashboard"
        
        ApplicationManager.getApplication().messageBus.connect(disposable)
            .subscribe(BalanceUpdatedTopic.TOPIC, object : BalanceUpdatedListener {
                override fun balanceUpdated(accountId: String, result: ProviderResult) {
                    ApplicationManager.getApplication().invokeLater {
                        tableModel.fireTableDataChanged()
                    }
                }
            })
            
        init()
    }

    override fun createCenterPanel(): JComponent {
        tableModel.items = TokenPulseSettingsService.getInstance().state.accounts.toMutableList()

        val decorator = ToolbarDecorator.createDecorator(table)
            .disableAddAction()
            .disableRemoveAction()
            .disableUpDownActions()

        val panel = JPanel(BorderLayout())
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        
        val refreshButton = javax.swing.JButton("Refresh All").apply {
            addActionListener {
                BalanceRefreshService.getInstance().refreshAll()
            }
        }
        panel.add(refreshButton, BorderLayout.SOUTH)

        panel.preferredSize = com.intellij.util.ui.JBUI.size(600, 350)
        return panel
    }

    private class DashboardTableModel : ListTableModel<Account>(
        NAME_COLUMN,
        PROVIDER_COLUMN,
        BALANCE_COLUMN,
        STATUS_COLUMN
    ) {
        companion object {
            private val NAME_COLUMN = object : ColumnInfo<Account, String>("Account") {
                override fun valueOf(item: Account): String = item.name
            }
            private val PROVIDER_COLUMN = object : ColumnInfo<Account, String>("Provider") {
                override fun valueOf(item: Account): String = item.providerId.displayName
            }
            private val BALANCE_COLUMN = object : ColumnInfo<Account, String>("Balance") {
                override fun valueOf(item: Account): String {
                    val result = BalanceRefreshService.getInstance().results.value[item.id]
                    return when (result) {
                        is ProviderResult.Success -> BalanceFormatter.format(result.snapshot.balance)
                        else -> "--"
                    }
                }
            }
            private val STATUS_COLUMN = object : ColumnInfo<Account, String>("Status") {
                override fun valueOf(item: Account): String {
                    val result = BalanceRefreshService.getInstance().results.value[item.id]
                    return when (result) {
                        null -> "Pending"
                        is ProviderResult.Success -> "OK"
                        is ProviderResult.Failure -> result.message
                    }
                }
            }
        }
    }
}
