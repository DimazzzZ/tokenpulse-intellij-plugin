package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.ChatGptOAuthManager
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_COLUMNS
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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
 *      • ChatGPT     → OAuth (CHATGPT_BILLING_SESSION)
 *      • Claude Code → CLI-based (CLAUDE_CODE_LOCAL)
 *  - For Nebius: "Connect Billing Session →" button opens [NebiusConnectDialog]
 *    which guides users through a cURL capture flow.
 *  - For OpenAI: "Connect API Key →" button opens [OpenAiConnectDialog]
 *    which guides users through API key capture.
 *  - For ChatGPT: "Sign in with ChatGPT →" button opens [ChatGptConnectDialog]
 *    which handles OAuth PKCE flow automatically.
 *  - For Claude Code: "Detect Claude CLI →" button opens [ClaudeConnectDialog]
 *    which verifies the CLI is installed.
 *  - For other providers: "Get API Key →" button opens the exact provider page.
 */
class AccountEditDialog(
    private val account: Account?,
    private val existingSecret: String?
) : DialogWrapper(true) {

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

    // ── Key field (hidden for Nebius, OpenAI, ChatGPT, and Claude Code) ────
    private val keyField = JBPasswordField().apply {
        val showKey = account?.connectionType != ConnectionType.NEBIUS_BILLING &&
            account?.connectionType != ConnectionType.OPENAI_PLATFORM &&
            account?.connectionType != ConnectionType.CHATGPT_SUBSCRIPTION &&
            account?.connectionType != ConnectionType.CLAUDE_CODE
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

    // ── ChatGPT connect button ─────────────────────────────────────────────
    private val chatGptConnectButton = JButton("Sign in with ChatGPT →").apply {
        addActionListener { openChatGptConnectDialog() }
    }

    // ── Claude Code connect button ────────────────────────────────────────
    private val claudeCodeConnectButton = JButton("Detect Claude CLI →").apply {
        addActionListener { openClaudeConnectDialog() }
    }

    // ── ChatGPT Codex opt-in checkbox ──────────────────────────────────────
    private val chatGptUseCodexCheckBox = javax.swing.JCheckBox().apply {
        text = "Use external Codex CLI for detailed rate limits (Optional)"
        isSelected = account?.chatGptUseCodex ?: false
        addActionListener { updateUiForConnectionType() }
    }

    private val chatGptCodexStatusLabel = JBLabel(
        "<html><i style='color:gray;'>Manual installation required: <b>npm i -g @openai/codex</b><br/>" +
            "Leave unchecked if you only want basic subscription info.</i></html>"
    )

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

    private val chatGptStatusLabel: JBLabel

    private val claudeCodeStatusLabel = JBLabel(
        if (account?.connectionType == ConnectionType.CLAUDE_CODE && !existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ Connected</font></html>"
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

    /** Holds the captured Claude Code secret (CLI mode marker). */
    private var capturedClaudeSecret: String? =
        if (account?.connectionType == ConnectionType.CLAUDE_CODE) existingSecret else null

    /** Indicates if ChatGPT OAuth is connected (uses OAuth manager, not stored secret). */
    private var isChatGptConnected: Boolean = false

    init {
        title = if (account == null) "Add Provider" else "Edit Provider"

        // Check if ChatGPT has a valid session (not just stored credentials)
        val oauthManager = ChatGptOAuthManager.getInstance()
        isChatGptConnected = oauthManager.hasValidSession()

        // If credentials exist but session is invalid, clear them
        if (!isChatGptConnected && oauthManager.isAuthenticated()) {
            oauthManager.clearCredentials()
        }

        val chatGptEmail = if (isChatGptConnected) oauthManager.getEmail() else null

        chatGptStatusLabel = JBLabel(
            if (isChatGptConnected) {
                if (chatGptEmail != null) {
                    "<html><font color='green'>✓ Connected as $chatGptEmail</font></html>"
                } else {
                    "<html><font color='green'>✓ Connected</font></html>"
                }
            } else {
                "<html><i>Not connected</i></html>"
            }
        )

        init()
        updateUiForConnectionType()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun updateUiForConnectionType() {
        val connectionType = getConnectionType()
        val isNebius = connectionType == ConnectionType.NEBIUS_BILLING
        val isOpenAi = connectionType == ConnectionType.OPENAI_PLATFORM
        val isChatGpt = connectionType == ConnectionType.CHATGPT_SUBSCRIPTION
        val isClaudeCode = connectionType == ConnectionType.CLAUDE_CODE
        val isOpenRouterPlugin = connectionType == ConnectionType.OPENROUTER_PLUGIN
        val isOther = !isNebius && !isOpenAi && !isChatGpt && !isClaudeCode && !isOpenRouterPlugin

        keyField.isVisible = isOther
        getKeyButton.isVisible = isOther

        nebiusConnectButton.isVisible = isNebius
        nebiusStatusLabel.isVisible = isNebius

        openAiConnectButton.isVisible = isOpenAi
        openAiStatusLabel.isVisible = isOpenAi

        chatGptConnectButton.isVisible = isChatGpt
        chatGptStatusLabel.isVisible = isChatGpt

        claudeCodeConnectButton.isVisible = isClaudeCode
        claudeCodeStatusLabel.isVisible = isClaudeCode

        // Show Codex checkbox only for ChatGPT when connected
        val showCodex = isChatGpt && isChatGptConnected
        chatGptUseCodexCheckBox.isVisible = showCodex
        chatGptCodexStatusLabel.isVisible = showCodex

        providerHintLabel.text = "<html><font color='gray'>${keyHintFor(connectionType)}</font></html>"
    }

    /**
     * Returns the [AuthType] for the currently selected connection type.
     */
    fun getAuthType(): AuthType = getConnectionType().defaultAuthType

    /**
     * Returns the secret to store:
     * - For Claude Code: the CLI mode marker string.
     * - For Nebius: the captured session JSON blob.
     * - For OpenAI: the captured API key.
     * - For ChatGPT: marker value (tokens managed by OAuth manager).
     * - For other providers: the raw API key string.
     */
    fun getSecret(): String = when (getConnectionType()) {
        ConnectionType.CLAUDE_CODE -> capturedClaudeSecret ?: ""
        ConnectionType.NEBIUS_BILLING -> capturedNebiusSession ?: ""
        ConnectionType.OPENAI_PLATFORM -> capturedOpenAiSecret ?: ""
        ConnectionType.CHATGPT_SUBSCRIPTION -> "oauth-managed" // Marker value, actual tokens in OAuth manager
        else -> String(keyField.password).trim()
    }

    /** Kept for compatibility with callers that expect getApiKey(). */
    fun getApiKey(): String = getSecret()

    /**
     * Returns whether the user opted into Codex integration for ChatGPT.
     * Only valid when connection type is CHATGPT_SUBSCRIPTION.
     */
    fun getChatGptUseCodex(): Boolean = chatGptUseCodexCheckBox.isSelected

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
        ConnectionType.OPENROUTER_PLUGIN -> "https://openrouter.ai" // Uses plugin credentials, no user config needed
        ConnectionType.NEBIUS_BILLING -> "https://tokenfactory.nebius.com/"
        ConnectionType.OPENAI_PLATFORM -> "https://platform.openai.com/settings/organization/admin-keys"
        ConnectionType.CHATGPT_SUBSCRIPTION -> "https://chatgpt.com"
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
     * Start ChatGPT OAuth flow directly (without intermediate dialog).
     *
     * If another ChatGPT account already exists, shows a warning and offers
     * to copy the OAuth link for manual incognito login.
     */
    private fun openChatGptConnectDialog() {
        val oauthManager = ChatGptOAuthManager.getInstance()

        // Check if there's already a connected ChatGPT session
        val existingEmail = if (oauthManager.hasValidSession()) oauthManager.getEmail() else null

        // For NEW accounts only: check if ChatGPT account already exists in settings
        val existingChatGptAccounts = if (account == null) {
            TokenPulseSettingsService.getInstance().state.accounts
                .filter { it.connectionType == ConnectionType.CHATGPT_SUBSCRIPTION }
        } else {
            emptyList()
        }

        if (existingChatGptAccounts.isNotEmpty() || (account == null && existingEmail != null)) {
            // Show warning with option to copy link for incognito
            showMultipleAccountsWarning(existingEmail)
            return
        }

        // Start OAuth flow directly
        startChatGptOAuth()
    }

    /**
     * Show warning when user tries to add another ChatGPT account.
     */
    private fun showMultipleAccountsWarning(existingEmail: String?) {
        val accountText = if (existingEmail != null) {
            "You are already connected as <b>$existingEmail</b>."
        } else {
            "A ChatGPT account is already connected."
        }

        val choice = Messages.showDialog(
            null,
            """
                <html>
                <p>$accountText</p>
                <br/>
                <p><b>To add another ChatGPT account:</b></p>
                <ol>
                <li>Copy the OAuth link to clipboard</li>
                <li>Open the link in <b>Incognito/Private</b> mode or another browser</li>
                <li>Sign in with your <b>other</b> ChatGPT account</li>
                <li>Complete the OAuth flow</li>
                </ol>
                <br/>
                <p><i>Note: Due to browser session sharing, the new login will replace the existing session.</i></p>
                </html>
            """.trimIndent(),
            "ChatGPT Account Already Connected",
            arrayOf("Copy OAuth Link", "Sign In Anyway", "Cancel"),
            0,
            Messages.getWarningIcon()
        )

        when (choice) {
            0 -> copyOAuthLinkToClipboard()
            1 -> startChatGptOAuth()
            else -> { /* Cancel - do nothing */ }
        }
    }

    /**
     * Copy the OAuth URL to clipboard for manual incognito login.
     */
    private fun copyOAuthLinkToClipboard() {
        val oauthManager = ChatGptOAuthManager.getInstance()
        val authUrl = oauthManager.generateAuthorizationUrl()

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(authUrl), null)

        Messages.showInfoMessage(
            "OAuth link copied to clipboard!\n\n" +
                "Paste it in an Incognito/Private browser window to sign in with a different account.",
            "Link Copied"
        )

        chatGptStatusLabel.text = "<html><i>Waiting for OAuth callback...</i></html>"
    }

    /**
     * Start the ChatGPT OAuth flow and update UI when complete.
     */
    private fun startChatGptOAuth() {
        val oauthManager = ChatGptOAuthManager.getInstance()

        chatGptStatusLabel.text = "<html><i>Opening browser for sign in...</i></html>"
        chatGptConnectButton.isEnabled = false

        val future = oauthManager.startAuthorizationFlow()

        future.whenComplete { credentials, error ->
            javax.swing.SwingUtilities.invokeLater {
                chatGptConnectButton.isEnabled = true

                if (error != null) {
                    chatGptStatusLabel.text = "<html><font color='red'>Sign in failed</font></html>"
                    Messages.showErrorDialog(
                        "Failed to sign in: ${error.message}",
                        "ChatGPT Sign In Failed"
                    )
                } else if (credentials != null) {
                    isChatGptConnected = true
                    val email = credentials.email
                    chatGptStatusLabel.text = if (email != null) {
                        "<html><font color='green'><b>✓ Connected as $email</b></font></html>"
                    } else {
                        "<html><font color='green'><b>✓ Connected</b></font></html>"
                    }
                    updateUiForConnectionType()
                }
            }
        }
    }

    private fun openClaudeConnectDialog() {
        val dialog = ClaudeConnectDialog()
        if (dialog.showAndGet()) {
            if (dialog.cliDetected) {
                // Claude CLI doesn't need a secret - it handles auth itself
                // We use a marker value to indicate CLI mode is enabled
                capturedClaudeSecret = "cli-mode"
                val version = dialog.cliVersion ?: "detected"
                claudeCodeStatusLabel.text =
                    "<html><font color='green'><b>✓ Claude CLI $version</b></font></html>"
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
        ConnectionType.CHATGPT_SUBSCRIPTION ->
            "Sign in with your ChatGPT account. Uses secure OAuth - your password is never shared."
        ConnectionType.OPENAI_PLATFORM ->
            "OpenAI API key. Click \"Connect API Key →\" to capture your key."
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
                    it != ConnectionType.CHATGPT_SUBSCRIPTION &&
                    it != ConnectionType.CLAUDE_CODE &&
                    it != ConnectionType.OPENROUTER_PLUGIN
            }
        )

        row {
            cell(getKeyButton)
        }.visibleIf(
            connectionTypeCombo.selectedValueMatches {
                it != ConnectionType.NEBIUS_BILLING &&
                    it != ConnectionType.OPENAI_PLATFORM &&
                    it != ConnectionType.CHATGPT_SUBSCRIPTION &&
                    it != ConnectionType.CLAUDE_CODE &&
                    it != ConnectionType.OPENROUTER_PLUGIN
            }
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

        // --- ChatGPT Row ---
        row {
            cell(chatGptConnectButton)
            cell(chatGptStatusLabel)
        }.visibleIf(connectionTypeCombo.selectedValueMatches { it == ConnectionType.CHATGPT_SUBSCRIPTION })

        // --- Claude Code Row ---
        row {
            cell(claudeCodeConnectButton)
            cell(claudeCodeStatusLabel)
        }.visibleIf(connectionTypeCombo.selectedValueMatches { it == ConnectionType.CLAUDE_CODE })

        row {
            cell(chatGptUseCodexCheckBox)
        }.visibleIf(
            connectionTypeCombo.selectedValueMatches {
                it == ConnectionType.CHATGPT_SUBSCRIPTION && isChatGptConnected
            }
        )

        row {
            cell(chatGptCodexStatusLabel)
        }.visibleIf(
            connectionTypeCombo.selectedValueMatches {
                it == ConnectionType.CHATGPT_SUBSCRIPTION && isChatGptConnected
            }
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
            account.connectionType != ConnectionType.CHATGPT_SUBSCRIPTION &&
            account.connectionType != ConnectionType.CLAUDE_CODE
    }

    // ── Public accessors ───────────────────────────────────────────────────

    fun getConnectionType(): ConnectionType = connectionTypeCombo.selectedItem as ConnectionType

    // ── Validation ─────────────────────────────────────────────────────────

    override fun doValidate(): ValidationInfo? {
        return when (getConnectionType()) {
            ConnectionType.NEBIUS_BILLING -> validateNebius()
            ConnectionType.OPENAI_PLATFORM -> validateOpenAi()
            ConnectionType.CHATGPT_SUBSCRIPTION -> validateChatGpt()
            ConnectionType.CLAUDE_CODE -> validateClaudeCode()
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

    private fun validateChatGpt(): ValidationInfo? {
        if (!isChatGptConnected) {
            return ValidationInfo(
                "Please sign in with your ChatGPT account first.",
                chatGptConnectButton
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
