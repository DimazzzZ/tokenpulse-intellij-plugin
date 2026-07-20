package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_COLUMNS
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Dialog for adding or editing an AI provider account.
 *
 * UX principles:
 *  - No name field — identity is derived from connection type + key preview automatically.
 *  - Auth-type selector for OpenAI only (API Key vs OAuth Token).
 *    Other providers have exactly one supported key type:
 *      • Cline       → API Key  (CLINE_API_KEY)
 *      • OpenRouter  → Provisioning Key  (OPENROUTER_PROVISIONING_KEY)
 *      • Nebius      → Billing Session  (NEBIUS_BILLING_SESSION)
 *      • Codex CLI   → CLI-based (CODEX_CLI_LOCAL)
 *      • Claude Code → CLI-based (CLAUDE_CODE_LOCAL)
 *  - For Nebius: "Connect Billing Session →" button opens [NebiusConnectDialog]
 *    which guides users through a cURL capture flow.
 *  - For OpenAI: "Connect API Key →" button opens [OpenAiConnectDialog]
 *    which guides users through API key capture.
 *  - For Codex CLI: "Detect Codex CLI →" button opens [CodexConnectDialog]
 *    which verifies the CLI is installed.
 *  - For Claude Code: "Detect Claude CLI →" button opens [ClaudeConnectDialog]
 *    which verifies the CLI is installed.
 *  - For other providers: "Get API Key →" button opens the exact provider page.
 */
class AccountEditDialog(
    private val account: Account?,
    private val existingSecret: String?
) : DialogWrapper(true) {

    companion object {
        private val XIAOMI_TYPES = setOf(ConnectionType.XIAOMI_API, ConnectionType.XIAOMI_TOKEN_PLAN)
        private val SESSION_BASED_TYPES = setOf(
            ConnectionType.NEBIUS_BILLING,
            ConnectionType.OPENAI_PLATFORM,
            ConnectionType.CODEX_CLI,
            ConnectionType.CLAUDE_CODE,
            ConnectionType.OPENROUTER_PLUGIN,
            ConnectionType.XIAOMI_API,
            ConnectionType.XIAOMI_TOKEN_PLAN
        )
    }

    // ── Connection type selector ───────────────────────────────────────────
    // Only show available connection types (excludes "Coming soon" features)
    private val connectionTypeCombo = ComboBox(
        ConnectionType.availableEntries().sortedBy { it.fullDisplayName }.toTypedArray()
    ).apply {
        selectedItem = account?.connectionType?.takeIf { it.isAvailable } ?: ConnectionType.CLINE_API
        renderer = object : ColoredListCellRenderer<ConnectionType>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out ConnectionType>,
                value: ConnectionType?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                append(value.fullDisplayName)
            }
        }
        addActionListener { updateUiForConnectionType() }
    }

    // ── Key field (hidden for Nebius, OpenAI, Codex CLI, Claude Code, and Xiaomi) ────
    private val keyField = JBPasswordField().apply {
        val showKey = account?.connectionType !in SESSION_BASED_TYPES
        text = if (showKey) existingSecret ?: "" else ""
        columns = TEXT_AREA_COLUMNS
    }

    // ── "Get API Key" button (for Cline/OpenRouter) ────────────────────────
    private val getKeyButton = JButton("Get API Key →").apply {
        addActionListener { openKeyGenerationPage() }
    }

    // ── Nebius connect button ──────────────────────────────────────────────
    private val nebiusConnectButton = JButton("Connect Billing Session →").apply {
        addActionListener { openNebiusConnectDialog() }
    }

    // ── OpenAI connect button ──────────────────────────────────────────────
    private val openAiConnectButton = JButton("Connect API Key →").apply {
        addActionListener { openOpenAiConnectDialog() }
    }

    // ── Codex CLI connect button ───────────────────────────────────────────
    private val codexConnectButton = JButton("Detect Codex CLI →").apply {
        addActionListener { openCodexConnectDialog() }
    }

    // ── Claude Code connect button ────────────────────────────────────────
    private val claudeCodeConnectButton = JButton("Detect Claude CLI →").apply {
        addActionListener { openClaudeConnectDialog() }
    }

    // ── Xiaomi connect button ─────────────────────────────────────────────
    private val xiaomiConnectButton = JButton("Connect Xiaomi Account →").apply {
        addActionListener { openXiaomiConnectDialog() }
    }

    private val enabledCheckBox = javax.swing.JCheckBox("Account enabled", account?.isEnabled ?: true)

    private val providerHintLabel = JBLabel("<html><small></small></html>")

    // ── Status labels ──────────────────────────────────────────────────────
    private val nebiusStatusLabel = JBLabel(
        if (account?.connectionType == ConnectionType.NEBIUS_BILLING && !existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ Session connected</font></html>"
        } else {
            "<html><i>Not connected</i></html>"
        }
    )

    private val openAiStatusLabel = JBLabel(
        if (account?.connectionType == ConnectionType.OPENAI_PLATFORM && !existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ Connected</font></html>"
        } else {
            "<html><i>Not connected</i></html>"
        }
    )

    private val codexStatusLabel = JBLabel(
        if (account?.connectionType == ConnectionType.CODEX_CLI && !existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ Connected</font></html>"
        } else {
            "<html><i>Not connected</i></html>"
        }
    )

    private val claudeCodeStatusLabel = JBLabel(
        if (account?.connectionType == ConnectionType.CLAUDE_CODE && !existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ Connected</font></html>"
        } else {
            "<html><i>Not connected</i></html>"
        }
    )

    private val xiaomiStatusLabel = JBLabel(
        if (account?.connectionType in XIAOMI_TYPES && !existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ Session connected</font></html>"
        } else {
            "<html><i>Not connected</i></html>"
        }
    )

    /** Holds the captured Nebius session JSON (set after successful NebiusConnectDialog). */
    private var capturedNebiusSession: String? =
        if (account?.connectionType == ConnectionType.NEBIUS_BILLING) existingSecret else null

    /** Holds the captured OpenAI API key. */
    private var capturedOpenAiSecret: String? =
        if (account?.connectionType == ConnectionType.OPENAI_PLATFORM) existingSecret else null

    /** Holds the captured Codex CLI secret (CLI mode marker). */
    private var capturedCodexSecret: String? =
        if (account?.connectionType == ConnectionType.CODEX_CLI) existingSecret else null

    /** Holds the captured Claude Code secret (CLI mode marker or OAuth token). */
    private var capturedClaudeSecret: String? =
        if (account?.connectionType == ConnectionType.CLAUDE_CODE) existingSecret else null

    /** The selected auth type for Claude Code (CLI or OAuth). */
    private var selectedClaudeAuthType: AuthType =
        if (account?.connectionType == ConnectionType.CLAUDE_CODE) {
            account.authType
        } else {
            AuthType.CLAUDE_CODE_LOCAL
        }

    /** Holds the captured Xiaomi session JSON. */
    private var capturedXiaomiSession: String? =
        if (account?.connectionType == ConnectionType.XIAOMI_API ||
            account?.connectionType == ConnectionType.XIAOMI_TOKEN_PLAN
        ) {
            existingSecret
        } else {
            null
        }

    init {
        title = if (account == null) "Add Provider" else "Edit Provider"
        init()
        updateUiForConnectionType()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun updateUiForConnectionType() {
        val connectionType = getConnectionType()
        val isNebius = connectionType == ConnectionType.NEBIUS_BILLING
        val isOpenAi = connectionType == ConnectionType.OPENAI_PLATFORM
        val isCodex = connectionType == ConnectionType.CODEX_CLI
        val isClaudeCode = connectionType == ConnectionType.CLAUDE_CODE
        val isOpenRouterPlugin = connectionType == ConnectionType.OPENROUTER_PLUGIN
        val isXiaomi = connectionType in XIAOMI_TYPES
        val isOther = !isNebius && !isOpenAi && !isCodex && !isClaudeCode && !isOpenRouterPlugin && !isXiaomi

        keyField.isVisible = isOther
        getKeyButton.isVisible = isOther

        nebiusConnectButton.isVisible = isNebius
        nebiusStatusLabel.isVisible = isNebius

        openAiConnectButton.isVisible = isOpenAi
        openAiStatusLabel.isVisible = isOpenAi

        codexConnectButton.isVisible = isCodex
        codexStatusLabel.isVisible = isCodex

        claudeCodeConnectButton.isVisible = isClaudeCode
        claudeCodeStatusLabel.isVisible = isClaudeCode

        xiaomiConnectButton.isVisible = isXiaomi
        xiaomiStatusLabel.isVisible = isXiaomi

        providerHintLabel.text = "<html><font color='gray'>${keyHintFor(connectionType)}</font></html>"
    }

    /**
     * Returns the [AuthType] for the currently selected connection type.
     */
    fun getAuthType(): AuthType = when (getConnectionType()) {
        ConnectionType.CLAUDE_CODE -> selectedClaudeAuthType
        else -> getConnectionType().defaultAuthType
    }

    /**
     * Returns the secret to store:
     * - For Claude Code: the CLI mode marker string.
     * - For Codex CLI: the CLI mode marker string.
     * - For Nebius: the captured session JSON blob.
     * - For OpenAI: the captured API key.
     * - For other providers: the raw API key string.
     */
    fun getSecret(): String = when (getConnectionType()) {
        ConnectionType.CLAUDE_CODE -> capturedClaudeSecret ?: ""
        ConnectionType.CODEX_CLI -> capturedCodexSecret ?: ""
        ConnectionType.NEBIUS_BILLING -> capturedNebiusSession ?: ""
        ConnectionType.OPENAI_PLATFORM -> capturedOpenAiSecret ?: ""
        ConnectionType.XIAOMI_API, ConnectionType.XIAOMI_TOKEN_PLAN -> capturedXiaomiSession ?: ""
        else -> String(keyField.password).trim()
    }

    /**
     * Returns whether the account should be enabled.
     */
    fun getIsEnabled(): Boolean = enabledCheckBox.isSelected

    private fun openKeyGenerationPage() {
        val url = getProviderUrl()
        BrowserUtil.browse(url)
    }

    private fun getProviderUrl(): String = when (getConnectionType()) {
        ConnectionType.CLAUDE_CODE -> "https://claude.ai/settings/usage"
        ConnectionType.CLINE_API -> "https://app.cline.bot/dashboard/account?tab=api-keys"
        ConnectionType.OPENROUTER_PROVISIONING -> "https://openrouter.ai/settings/provisioning-keys"
        ConnectionType.OPENROUTER_PLUGIN -> "https://openrouter.ai"
        ConnectionType.NEBIUS_BILLING -> "https://tokenfactory.nebius.com/"
        ConnectionType.OPENAI_PLATFORM -> "https://platform.openai.com/settings/organization/admin-keys"
        ConnectionType.CODEX_CLI -> "https://github.com/openai/codex"
        ConnectionType.XIAOMI_API,
        ConnectionType.XIAOMI_TOKEN_PLAN -> "https://platform.xiaomimimo.com/console/api-keys"
    }

    private fun openNebiusConnectDialog() {
        val dialog = NebiusConnectDialog()
        if (dialog.showAndGet()) {
            val json = dialog.capturedSessionJson
            if (!json.isNullOrBlank()) {
                capturedNebiusSession = json
                nebiusStatusLabel.text =
                    "<html><font color='green'><b>✓ Session connected</b></font></html>"
            }
        }
    }

    private fun openOpenAiConnectDialog() {
        val dialog = OpenAiConnectDialog()
        if (dialog.showAndGet()) {
            val apiKey = dialog.capturedApiKey
            if (!apiKey.isNullOrBlank()) {
                capturedOpenAiSecret = apiKey
                openAiStatusLabel.text =
                    "<html><font color='green'><b>✓ API key connected</b></font></html>"
            }
        }
    }

    /**
     * Open Codex CLI detection dialog.
     */
    private fun openCodexConnectDialog() {
        val dialog = CodexConnectDialog()
        if (dialog.showAndGet()) {
            if (dialog.cliDetected) {
                // Codex CLI handles auth itself - we just use a marker value
                capturedCodexSecret = "cli-mode"
                val version = dialog.cliVersion ?: "detected"
                codexStatusLabel.text =
                    "<html><font color='green'><b>✓ Codex CLI $version</b></font></html>"
                updateUiForConnectionType()
            }
        }
    }

    private fun openClaudeConnectDialog() {
        val dialog = ClaudeConnectDialog()
        if (dialog.showAndGet()) {
            val result = dialog.result ?: return

            if (result.cliDetected) {
                // Claude CLI detected - credentials will be read automatically from Keychain/file
                capturedClaudeSecret = "cli-mode"
                selectedClaudeAuthType = AuthType.CLAUDE_CODE_LOCAL
                val version = result.cliVersion ?: "detected"
                claudeCodeStatusLabel.text =
                    "<html><font color='green'><b>✓ Claude CLI $version</b></font></html>"
            }
        }
    }

    private fun openXiaomiConnectDialog() {
        val dialog = XiaomiConnectDialog()
        if (dialog.showAndGet()) {
            val json = dialog.capturedSessionJson
            if (!json.isNullOrBlank()) {
                capturedXiaomiSession = json
                xiaomiStatusLabel.text =
                    "<html><font color='green'><b>✓ Session connected</b></font></html>"
            }
        }
    }

    private fun keyHintFor(connectionType: ConnectionType): String = when (connectionType) {
        ConnectionType.CLAUDE_CODE ->
            "Claude Code uses the Claude CLI for authentication. Click \"Detect Claude CLI →\" to verify installation."
        ConnectionType.CLINE_API ->
            "Cline personal API key. <b>Note:</b> API key management is only available for Personal accounts, " +
                "not Organization accounts."
        ConnectionType.OPENROUTER_PROVISIONING ->
            "OpenRouter <b>Provisioning Key</b> required. Click \"Get API Key →\" to open the OpenRouter settings."
        ConnectionType.OPENROUTER_PLUGIN ->
            "Uses credentials from the installed <b>OpenRouter plugin</b>. No additional configuration needed."
        ConnectionType.NEBIUS_BILLING ->
            "Nebius billing uses a browser session. Click \"Connect Billing Session →\" to capture."
        ConnectionType.CODEX_CLI ->
            "Codex CLI uses local authentication. Click \"Detect Codex CLI →\" to verify installation. " +
                "Install via: npm install -g @openai/codex"
        ConnectionType.OPENAI_PLATFORM ->
            "OpenAI API key. Click \"Connect API Key →\" to capture your key."
        ConnectionType.XIAOMI_API ->
            "Xiaomi MiMo pay-as-you-go API. Click \"Connect Xiaomi Account →\" to capture your platform session. " +
                "Get your API key at platform.xiaomimimo.com."
        ConnectionType.XIAOMI_TOKEN_PLAN ->
            "Xiaomi MiMo Token Plan. Click \"Connect Xiaomi Account →\" to capture your platform session. " +
                "Subscribe at platform.xiaomimimo.com/token-plan."
    }

    // ── Panel construction ─────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent = panel {
        row("Connection:") {
            cell(connectionTypeCombo).align(AlignX.FILL)
        }

        separator()

        // --- Generic API Key Row ---
        row("API Key:") {
            cell(keyField).align(AlignX.FILL)
        }.visibleIf(
            connectionTypeCombo.selectedValueMatches {
                it != ConnectionType.NEBIUS_BILLING &&
                    it != ConnectionType.OPENAI_PLATFORM &&
                    it != ConnectionType.CODEX_CLI &&
                    it != ConnectionType.CLAUDE_CODE &&
                    it != ConnectionType.OPENROUTER_PLUGIN &&
                    it !in XIAOMI_TYPES
            }
        )

        row {
            cell(getKeyButton)
        }.visibleIf(
            connectionTypeCombo.selectedValueMatches { it !in SESSION_BASED_TYPES }
        )

        // --- Nebius Row ---
        row {
            cell(nebiusConnectButton)
            cell(nebiusStatusLabel)
        }.visibleIf(connectionTypeCombo.selectedValueMatches { it == ConnectionType.NEBIUS_BILLING })

        // --- OpenAI Row ---
        row {
            cell(openAiConnectButton)
            cell(openAiStatusLabel)
        }.visibleIf(connectionTypeCombo.selectedValueMatches { it == ConnectionType.OPENAI_PLATFORM })

        // --- Codex CLI Row ---
        row {
            cell(codexConnectButton)
            cell(codexStatusLabel)
        }.visibleIf(connectionTypeCombo.selectedValueMatches { it == ConnectionType.CODEX_CLI })

        // --- Claude Code Row ---
        row {
            cell(claudeCodeConnectButton)
            cell(claudeCodeStatusLabel)
        }.visibleIf(connectionTypeCombo.selectedValueMatches { it == ConnectionType.CLAUDE_CODE })

        // --- Xiaomi Row ---
        row {
            cell(xiaomiConnectButton)
            cell(xiaomiStatusLabel)
        }.visibleIf(
            connectionTypeCombo.selectedValueMatches { it in XIAOMI_TYPES }
        )

        separator()

        row {
            cell(enabledCheckBox)
        }

        row {
            cell(providerHintLabel).align(AlignX.FILL)
        }

        row {
            if (shouldShowKeyPreview()) {
                cell(JBLabel("<html><small>Current key: <b>${account?.keyPreview}</b></small></html>"))
            }
        }
    }

    private fun <T> ComboBox<T>.selectedValueMatches(predicate: (T) -> Boolean): ComponentPredicate {
        val comboBox = this
        return object : ComponentPredicate() {
            @Suppress("UNCHECKED_CAST")
            override fun invoke(): Boolean = predicate(comboBox.selectedItem as T)
            override fun addListener(listener: (Boolean) -> Unit) {
                comboBox.addActionListener { listener(invoke()) }
            }
        }
    }

    private fun shouldShowKeyPreview(): Boolean {
        return account?.keyPreview?.isNotEmpty() == true &&
            account.connectionType != ConnectionType.NEBIUS_BILLING &&
            account.connectionType != ConnectionType.CODEX_CLI &&
            account.connectionType != ConnectionType.CLAUDE_CODE &&
            account.connectionType !in XIAOMI_TYPES
    }

    // ── Public accessors ───────────────────────────────────────────────────

    fun getConnectionType(): ConnectionType = connectionTypeCombo.selectedItem as ConnectionType

    // ── Validation ─────────────────────────────────────────────────────────

    override fun doValidate(): ValidationInfo? {
        return when (getConnectionType()) {
            ConnectionType.NEBIUS_BILLING -> validateNebius()
            ConnectionType.OPENAI_PLATFORM -> validateOpenAi()
            ConnectionType.CODEX_CLI -> validateCodex()
            ConnectionType.CLAUDE_CODE -> validateClaudeCode()
            ConnectionType.XIAOMI_API, ConnectionType.XIAOMI_TOKEN_PLAN -> validateXiaomi()
            else -> validateOther()
        }
    }

    private fun validateNebius(): ValidationInfo? {
        if (capturedNebiusSession.isNullOrBlank()) {
            return ValidationInfo(
                "Please connect your Nebius billing session first.",
                nebiusConnectButton
            )
        }
        return null
    }

    private fun validateOpenAi(): ValidationInfo? {
        if (capturedOpenAiSecret.isNullOrBlank()) {
            return ValidationInfo(
                "Please connect your OpenAI API key first.",
                openAiConnectButton
            )
        }
        return null
    }

    private fun validateCodex(): ValidationInfo? {
        if (capturedCodexSecret.isNullOrBlank()) {
            return ValidationInfo(
                "Please detect Codex CLI first. Install via: npm install -g @openai/codex",
                codexConnectButton
            )
        }
        return null
    }

    private fun validateClaudeCode(): ValidationInfo? {
        if (capturedClaudeSecret.isNullOrBlank()) {
            return ValidationInfo(
                "Please detect Claude CLI first. Install via: npm install -g @anthropic-ai/claude-code",
                claudeCodeConnectButton
            )
        }
        return null
    }

    private fun validateXiaomi(): ValidationInfo? {
        if (capturedXiaomiSession.isNullOrBlank()) {
            return ValidationInfo(
                "Please connect your Xiaomi account first.",
                xiaomiConnectButton
            )
        }
        return null
    }

    private fun validateOther(): ValidationInfo? {
        // OpenRouter Plugin doesn't need API key - uses credentials from the plugin
        if (getConnectionType() == ConnectionType.OPENROUTER_PLUGIN) {
            return null
        }
        if (getSecret().isEmpty()) {
            return ValidationInfo("API Key is required", keyField)
        }
        return null
    }

    override fun doOKAction() {
        val error = doValidate()
        if (error != null) {
            setErrorText(error.message, error.component)
            return
        }
        super.doOKAction()
    }
}
