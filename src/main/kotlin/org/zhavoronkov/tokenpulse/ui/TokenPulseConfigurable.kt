package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.table.JBTable
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.*
import kotlinx.coroutines.*
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class TokenPulseConfigurable : Configurable, Disposable {
    private val settingsService = TokenPulseSettingsService.getInstance()
    private val settings = settingsService.state
    private val tableModel = AccountTableModel()
    private val table = JBTable(tableModel)
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var myModified = false

    companion object {
        private const val MAX_MINUTES = 1440
        private const val PREFERRED_WIDTH = 500
        private const val PREFERRED_HEIGHT = 250
    }

    override fun createComponent(): JComponent {
        tableModel.items = settings.accounts.toMutableList()

        // Subscribe to refresh results to update table
        scope.launch {
            BalanceRefreshService.getInstance().results.collect {
                tableModel.fireTableDataChanged()
            }
        }

        val decorator = createTableDecorator()
        val tablePanel = JPanel(BorderLayout())
        tablePanel.add(decorator.createPanel(), BorderLayout.CENTER)

        return panel {
            preferencesGroup()
            group("Accounts") {
                row {
                    cell(tablePanel).applyToComponent {
                        preferredSize = com.intellij.util.ui.JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)
                    }
                }
            }
        }
    }

    private fun Panel.preferencesGroup() {
        group("Preferences") {
            row {
                checkBox("Enable auto-refresh")
                    .bindSelected(settings::autoRefreshEnabled)
                    .onChanged { myModified = true }
            }
            row("Refresh interval (minutes):") {
                intTextField(1..MAX_MINUTES)
                    .bindIntText(settings::refreshIntervalMinutes)
                    .onChanged { myModified = true }
            }
            row {
                checkBox("Show credits")
                    .bindSelected(settings::showCredits)
                    .onChanged { myModified = true }
                checkBox("Show tokens")
                    .bindSelected(settings::showTokens)
                    .onChanged { myModified = true }
            }
        }
    }

    private fun createTableDecorator() = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            val dialog = AccountEditDialog(null, null)
            if (dialog.showAndGet()) {
                val newAccount = Account(
                    name = dialog.getAccountName(),
                    providerId = dialog.getProvider(),
                    authType = dialog.getAuthType()
                )
                tableModel.addRow(newAccount)
                CredentialsStore.getInstance().saveApiKey(newAccount.id, dialog.getApiKey())
                myModified = true
            }
        }
        .setEditAction {
            val account = tableModel.getItem(table.selectedRow)
            val apiKey = CredentialsStore.getInstance().getApiKey(account.id)
            val dialog = AccountEditDialog(account, apiKey)
            if (dialog.showAndGet()) {
                val updatedAccount = account.copy(
                    name = dialog.getAccountName(),
                    providerId = dialog.getProvider(),
                    authType = dialog.getAuthType()
                )
                tableModel.setItem(table.selectedRow, updatedAccount)
                CredentialsStore.getInstance().saveApiKey(updatedAccount.id, dialog.getApiKey())
                myModified = true
            }
        }
        .setRemoveAction {
            val account = tableModel.getItem(table.selectedRow)
            tableModel.removeRow(table.selectedRow)
            CredentialsStore.getInstance().removeApiKey(account.id)
            myModified = true
        }
        .disableUpDownActions()

    override fun isModified(): Boolean = myModified

    override fun apply() {
        settings.accounts = tableModel.items.toList()
        settingsService.loadState(settings)
        BalanceRefreshService.getInstance().restartAutoRefresh()
        myModified = false
    }

    override fun dispose() {
        scope.cancel()
    }

    override fun getDisplayName(): String = "TokenPulse"
}
