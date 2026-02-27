package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.options.Configurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.CredentialsStore
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.settings.generateKeyPreview
import javax.swing.JComponent

class TokenPulseConfigurable : Configurable {
    private val settingsService = TokenPulseSettingsService.getInstance()
    private val settings = settingsService.state
    private val tableModel = AccountTableModel()
    private val table = JBTable(tableModel)

    private var myModified = false

    companion object {
        private const val MAX_MINUTES = 1440

        // Column indices (must match AccountTableModel column order)
        private const val COL_PROVIDER = 0
        private const val COL_KEY_PREVIEW = 1
        private const val COL_STATUS = 2
        private const val COL_LAST_UPDATED = 3
        private const val COL_CREDITS = 4
        private const val COL_ENABLED = 5

        // Column preferred widths (px, before JBUI scaling)
        private const val COL_PROVIDER_WIDTH = 100
        private const val COL_KEY_PREVIEW_WIDTH = 160
        private const val COL_STATUS_WIDTH = 90
        private const val COL_LAST_UPDATED_WIDTH = 100
        private const val COL_CREDITS_WIDTH = 100
        private const val COL_ENABLED_WIDTH = 60

        private const val TABLE_HEIGHT = 200
    }

    override fun createComponent(): JComponent {
        tableModel.items = settings.accounts.toMutableList()
        configureTable()

        val decorator = createTableDecorator()

        return panel {
            preferencesGroup()
            group("Accounts") {
                row {
                    cell(decorator.createPanel())
                        .resizableColumn()
                        .align(com.intellij.ui.dsl.builder.Align.FILL)
                }
            }
        }
    }

    private fun configureTable() {
        table.fillsViewportHeight = true
        table.preferredScrollableViewportSize = JBUI.size(700, TABLE_HEIGHT)
        table.autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS

        val cm = table.columnModel
        cm.getColumn(COL_PROVIDER).preferredWidth = JBUI.scale(COL_PROVIDER_WIDTH)
        cm.getColumn(COL_KEY_PREVIEW).preferredWidth = JBUI.scale(COL_KEY_PREVIEW_WIDTH)
        cm.getColumn(COL_STATUS).preferredWidth = JBUI.scale(COL_STATUS_WIDTH)
        cm.getColumn(COL_LAST_UPDATED).preferredWidth = JBUI.scale(COL_LAST_UPDATED_WIDTH)
        cm.getColumn(COL_CREDITS).preferredWidth = JBUI.scale(COL_CREDITS_WIDTH)
        cm.getColumn(COL_ENABLED).preferredWidth = JBUI.scale(COL_ENABLED_WIDTH)
        cm.getColumn(COL_ENABLED).maxWidth = JBUI.scale(COL_ENABLED_WIDTH)
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
                val secret = dialog.getSecret()
                val provider = dialog.getProvider()
                val newAccount = Account(
                    providerId = provider,
                    authType = dialog.getAuthType(),
                    keyPreview = secretPreview(provider, secret)
                )
                tableModel.addRow(newAccount)
                CredentialsStore.getInstance().saveApiKey(newAccount.id, secret)
                myModified = true
            }
        }
        .setEditAction {
            val account = tableModel.getItem(table.selectedRow)
            val existingSecret = CredentialsStore.getInstance().getApiKey(account.id)
            val dialog = AccountEditDialog(account, existingSecret)
            if (dialog.showAndGet()) {
                val secret = dialog.getSecret()
                val provider = dialog.getProvider()
                val updatedAccount = account.copy(
                    providerId = provider,
                    authType = dialog.getAuthType(),
                    keyPreview = secretPreview(provider, secret)
                )
                tableModel.setItem(table.selectedRow, updatedAccount)
                CredentialsStore.getInstance().saveApiKey(updatedAccount.id, secret)
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

    override fun getDisplayName(): String = "TokenPulse"

    /**
     * Returns a display-safe preview string for the stored secret.
     * For Nebius (billing session JSON) and OpenAI (OAuth token JSON), shows a friendly
     * label instead of a garbled key preview.
     * For all other providers, delegates to [generateKeyPreview].
     */
    private fun secretPreview(provider: ProviderId, secret: String): String =
        when (provider) {
            ProviderId.NEBIUS -> "Session"
            ProviderId.OPENAI -> "OAuth"
            else -> generateKeyPreview(secret)
        }
}
