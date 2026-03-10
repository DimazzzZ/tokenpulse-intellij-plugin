package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.ChatGptOAuthManager
import org.zhavoronkov.tokenpulse.service.BalanceRefreshService
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.CredentialsStore
import org.zhavoronkov.tokenpulse.settings.StatusBarDisplayMode
import org.zhavoronkov.tokenpulse.settings.StatusBarDollarFormat
import org.zhavoronkov.tokenpulse.settings.StatusBarFormat
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.settings.generateKeyPreview
import javax.swing.JComponent

class TokenPulseConfigurable : Configurable {
    private val settingsService = TokenPulseSettingsService.getInstance()
    private val settings = settingsService.state
    private val tableModel = AccountTableModel()
    private val table = object : JBTable(tableModel) {
        override fun getToolTipText(e: java.awt.event.MouseEvent): String? {
            val row = rowAtPoint(e.point)
            val col = columnAtPoint(e.point)
            if (row < 0 || col < 0) return null

            val account = tableModel.getItem(convertRowIndexToModel(row))
            val result = BalanceRefreshService.getInstance().results.value[account.id]

            if (result is org.zhavoronkov.tokenpulse.model.ProviderResult.Failure) {
                return result.message
            }
            return super.getToolTipText(e)
        }
    }

    private var myModified = false

    // Status bar settings UI components
    private lateinit var displayModeCombo: ComboBox<StatusBarDisplayMode>
    private lateinit var primaryAccountCombo: ComboBox<AccountOption>
    private lateinit var formatCombo: ComboBox<StatusBarFormat>
    private lateinit var dollarFormatCombo: ComboBox<StatusBarDollarFormat>

    /** Wrapper for account dropdown with "First enabled" option */
    private sealed class AccountOption {
        data object FirstEnabled : AccountOption() {
            override fun toString(): String = "First enabled account"
        }
        data class Specific(val account: Account) : AccountOption() {
            override fun toString(): String = account.name.ifBlank { account.connectionType.fullDisplayName }
        }
    }

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
        // Deep copy accounts to prevent immediate mutation of saved settings when toggling checkboxes
        tableModel.items = settings.accounts.map { it.copy() }.toMutableList()
        configureTable()

        // Listen for table edits (like toggling 'Enabled' checkbox) to enable 'Apply' button
        tableModel.addTableModelListener {
            myModified = true
        }

        val decorator = createTableDecorator()

        return panel {
            group("Provider Accounts") {
                row {
                    cell(decorator.createPanel())
                        .align(Align.FILL)
                }
                row {
                    comment("Add AI provider accounts to track your balances in the status bar.")
                }
            }

            group("Status Bar Display") {
                row("Display mode:") {
                    displayModeCombo = comboBox(
                        StatusBarDisplayMode.entries,
                        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.displayName }
                    )
                        .applyToComponent {
                            selectedItem = settings.statusBarDisplayMode
                            addActionListener {
                                myModified = true
                                updatePrimaryAccountComboState()
                            }
                        }
                        .component
                    contextHelp("Auto adapts to your first provider type. Total shows sum of all balances.")
                }
                row("Primary account:") {
                    primaryAccountCombo = comboBox(buildAccountOptions())
                        .applyToComponent {
                            selectedItem = findSelectedAccountOption()
                            isEnabled = settings.statusBarDisplayMode == StatusBarDisplayMode.SINGLE_PROVIDER
                            addActionListener {
                                myModified = true
                                updateBalanceFormatComboState()
                            }
                        }
                        .component
                    contextHelp("Only used when mode is 'Single provider'")
                }
                row("Format:") {
                    formatCombo = comboBox(
                        StatusBarFormat.entries,
                        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.displayName }
                    )
                        .applyToComponent {
                            selectedItem = settings.statusBarFormat
                            addActionListener { myModified = true }
                        }
                        .component
                }
                row("Balance format:") {
                    dollarFormatCombo = comboBox(
                        StatusBarDollarFormat.entries,
                        renderer = createBalanceFormatRenderer()
                    )
                        .applyToComponent {
                            selectedItem = settings.statusBarDollarFormat
                            addActionListener {
                                // Prevent selection of unsupported formats
                                val selected = selectedItem as? StatusBarDollarFormat
                                if (selected != null && !isFormatSupported(selected)) {
                                    // Revert to first supported format
                                    selectedItem = getFirstSupportedFormat()
                                } else {
                                    myModified = true
                                }
                            }
                        }
                        .component
                    contextHelp("Format for balance display. Options depend on selected provider's data availability.")
                }
                row {
                    comment(
                        "Compact: \"86% 5h • 72% wk\" / \"\$500\"<br>" +
                            "Descriptive: \"86% of 5h remaining\" / \"\$500 remaining\""
                    )
                }
            }

            group("General Settings") {
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
                    checkBox("Show credits (e.g. $6.00)")
                        .bindSelected(settings::showCredits)
                        .onChanged { myModified = true }
                    checkBox("Show tokens (e.g. 1,000)")
                        .bindSelected(settings::showTokens)
                        .onChanged { myModified = true }
                }
            }
        }
    }

    private fun buildAccountOptions(): List<AccountOption> {
        val options = mutableListOf<AccountOption>(AccountOption.FirstEnabled)
        tableModel.items.filter { it.isEnabled }.forEach { account ->
            options.add(AccountOption.Specific(account))
        }
        return options
    }

    private fun findSelectedAccountOption(): AccountOption {
        val primaryId = settings.statusBarPrimaryAccountId ?: return AccountOption.FirstEnabled
        val account = tableModel.items.find { it.id == primaryId }
        return if (account != null) AccountOption.Specific(account) else AccountOption.FirstEnabled
    }

    private fun updatePrimaryAccountComboState() {
        primaryAccountCombo.isEnabled = displayModeCombo.selectedItem == StatusBarDisplayMode.SINGLE_PROVIDER
        updateBalanceFormatComboState()
    }

    /**
     * Updates the balance format combo state based on selected account.
     * Disables the combo if selected provider uses percentage display.
     * Refreshes the renderer to gray out unsupported options.
     */
    private fun updateBalanceFormatComboState() {
        val selectedAccount = getSelectedAccount()
        val usesPercentage = selectedAccount?.connectionType?.usesPercentageDisplay ?: false

        // Disable the combo entirely for percentage-based providers
        dollarFormatCombo.isEnabled = !usesPercentage

        // Force repaint to update grayed-out options
        dollarFormatCombo.repaint()

        // If current selection is not supported, switch to first supported
        val currentFormat = dollarFormatCombo.selectedItem as? StatusBarDollarFormat
        if (currentFormat != null && !isFormatSupported(currentFormat)) {
            dollarFormatCombo.selectedItem = getFirstSupportedFormat()
        }
    }

    /**
     * Returns the currently selected account (for Single Provider mode or first enabled).
     */
    private fun getSelectedAccount(): Account? {
        val mode = displayModeCombo.selectedItem as? StatusBarDisplayMode ?: StatusBarDisplayMode.AUTO
        return when (mode) {
            StatusBarDisplayMode.SINGLE_PROVIDER -> {
                when (val selected = primaryAccountCombo.selectedItem) {
                    is AccountOption.Specific -> selected.account
                    else -> tableModel.items.firstOrNull { it.isEnabled }
                }
            }
            else -> tableModel.items.firstOrNull { it.isEnabled }
        }
    }

    /**
     * Checks if a balance format is supported by the currently selected account.
     */
    private fun isFormatSupported(format: StatusBarDollarFormat): Boolean {
        val account = getSelectedAccount() ?: return true // Allow all if no account
        val supportedFormats = account.connectionType.supportedBalanceFormats
        // If empty (percentage-based), all dollar formats are technically "supported" but won't be used
        return supportedFormats.isEmpty() || format in supportedFormats
    }

    /**
     * Returns the first supported balance format for the current account.
     */
    private fun getFirstSupportedFormat(): StatusBarDollarFormat {
        val account = getSelectedAccount()
        val supportedFormats = account?.connectionType?.supportedBalanceFormats
            ?: return StatusBarDollarFormat.REMAINING_ONLY

        return if (supportedFormats.isEmpty()) {
            StatusBarDollarFormat.REMAINING_ONLY
        } else {
            supportedFormats.first()
        }
    }

    /**
     * Creates a custom renderer for the balance format dropdown that grays out unsupported options.
     */
    private fun createBalanceFormatRenderer(): ColoredListCellRenderer<StatusBarDollarFormat> {
        return object : ColoredListCellRenderer<StatusBarDollarFormat>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out StatusBarDollarFormat>,
                value: StatusBarDollarFormat?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return

                val isSupported = isFormatSupported(value)
                val account = getSelectedAccount()
                val usesPercentage = account?.connectionType?.usesPercentageDisplay ?: false

                if (usesPercentage) {
                    // For percentage-based providers, show that dollar formats don't apply
                    append("${value.displayName} — ${value.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(" (uses % display)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                } else if (!isSupported) {
                    // Gray out unsupported formats with explanation
                    append("${value.displayName} — ${value.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(" (not available)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                } else {
                    append("${value.displayName} — ${value.description}")
                }
            }
        }
    }

    private fun configureTable() {
        table.fillsViewportHeight = true
        table.preferredScrollableViewportSize = JBUI.size(750, TABLE_HEIGHT)
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

    private fun createTableDecorator() = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            val dialog = AccountEditDialog(null, null)
            if (dialog.showAndGet()) {
                val secret = dialog.getSecret()
                val connectionType = dialog.getConnectionType()

                // Special handling for ChatGPT: only one account allowed
                if (connectionType == ConnectionType.CHATGPT_SUBSCRIPTION) {
                    val existingIdx = tableModel.items.indexOfFirst {
                        it.connectionType == ConnectionType.CHATGPT_SUBSCRIPTION
                    }
                    if (existingIdx != -1) {
                        val existing = tableModel.getItem(existingIdx)
                        val updated = existing.copy(
                            chatGptUseCodex = dialog.getChatGptUseCodex(),
                            isEnabled = dialog.getIsEnabled()
                        )
                        tableModel.setItem(existingIdx, updated)
                        myModified = true
                        return@setAddAction
                    }
                }

                // Special handling for Claude Code: only one account allowed
                if (connectionType == ConnectionType.CLAUDE_CODE) {
                    val existingIdx = tableModel.items.indexOfFirst {
                        it.connectionType == ConnectionType.CLAUDE_CODE
                    }
                    if (existingIdx != -1) {
                        val existing = tableModel.getItem(existingIdx)
                        val updated = existing.copy(
                            isEnabled = dialog.getIsEnabled()
                        )
                        tableModel.setItem(existingIdx, updated)
                        Messages.showInfoMessage(
                            "Claude Code is already configured. The existing account has been updated.",
                            "Account Updated"
                        )
                        myModified = true
                        return@setAddAction
                    }
                }

                val newAccount = Account(
                    connectionType = connectionType,
                    authType = dialog.getAuthType(),
                    keyPreview = secretPreview(connectionType, secret),
                    isEnabled = dialog.getIsEnabled(),
                    chatGptUseCodex = if (connectionType == ConnectionType.CHATGPT_SUBSCRIPTION) {
                        dialog.getChatGptUseCodex()
                    } else {
                        null
                    }
                )
                tableModel.addRow(newAccount)
                CredentialsStore.getInstance().saveApiKey(newAccount.id, secret)
                myModified = true
            }
        }
        .setEditAction {
            val viewIdx = table.selectedRow
            if (viewIdx == -1) return@setEditAction
            val modelIdx = table.convertRowIndexToModel(viewIdx)
            val account = tableModel.getItem(modelIdx)
            val existingSecret = CredentialsStore.getInstance().getApiKey(account.id)

            val dialog = AccountEditDialog(account, existingSecret)
            if (dialog.showAndGet()) {
                val secret = dialog.getSecret()
                val connectionType = dialog.getConnectionType()
                val updatedAccount = account.copy(
                    connectionType = connectionType,
                    authType = dialog.getAuthType(),
                    isEnabled = dialog.getIsEnabled(),
                    keyPreview = secretPreview(connectionType, secret),
                    chatGptUseCodex = if (connectionType == ConnectionType.CHATGPT_SUBSCRIPTION) {
                        dialog.getChatGptUseCodex()
                    } else {
                        account.chatGptUseCodex
                    }
                )
                tableModel.setItem(modelIdx, updatedAccount)
                CredentialsStore.getInstance().saveApiKey(updatedAccount.id, secret)
                myModified = true
            }
        }
        .setRemoveAction {
            val viewIdx = table.selectedRow
            if (viewIdx == -1) return@setRemoveAction
            val modelIdx = table.convertRowIndexToModel(viewIdx)
            val account = tableModel.getItem(modelIdx)
            tableModel.removeRow(modelIdx)
            CredentialsStore.getInstance().removeApiKey(account.id)

            // If removing ChatGPT, also clear the global OAuth session
            if (account.connectionType == ConnectionType.CHATGPT_SUBSCRIPTION) {
                ChatGptOAuthManager.getInstance().clearCredentials()
            }

            myModified = true
        }
        .disableUpDownActions()

    override fun isModified(): Boolean = myModified

    override fun apply() {
        settings.accounts = tableModel.items.toList()

        // Apply status bar settings
        settings.statusBarDisplayMode = displayModeCombo.selectedItem as? StatusBarDisplayMode
            ?: StatusBarDisplayMode.AUTO
        settings.statusBarFormat = formatCombo.selectedItem as? StatusBarFormat
            ?: StatusBarFormat.COMPACT
        settings.statusBarDollarFormat = dollarFormatCombo.selectedItem as? StatusBarDollarFormat
            ?: StatusBarDollarFormat.USED_OF_REMAINING
        settings.statusBarPrimaryAccountId = when (val selected = primaryAccountCombo.selectedItem) {
            is AccountOption.Specific -> selected.account.id
            else -> null
        }

        settingsService.loadState(settings)
        BalanceRefreshService.getInstance().restartAutoRefresh()
        myModified = false
    }

    override fun getDisplayName(): String = "TokenPulse β"

    /**
     * Returns a display-safe preview string for the stored secret.
     * For Nebius (billing session JSON), shows "Session".
     * For OpenAI:
     *   - If secret looks like OAuth JSON, shows "OAuth".
     *   - Otherwise, shows a masked API key preview.
     * For all other providers, delegates to [generateKeyPreview].
     */
    private fun secretPreview(connectionType: ConnectionType, secret: String): String =
        when (connectionType) {
            ConnectionType.CLAUDE_CODE -> "Local File"
            ConnectionType.NEBIUS_BILLING -> "Session"
            ConnectionType.OPENAI_PLATFORM -> {
                // Detect if secret is OAuth JSON (has accessToken and refreshToken fields)
                val isOAuthJson = secret.contains("\"accessToken\"") && secret.contains("\"refreshToken\"")
                if (isOAuthJson) "OAuth" else generateKeyPreview(secret)
            }
            else -> generateKeyPreview(secret)
        }
}
