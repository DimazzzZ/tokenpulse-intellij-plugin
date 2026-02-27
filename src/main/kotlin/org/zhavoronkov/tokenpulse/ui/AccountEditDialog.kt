package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Dialog for adding or editing an AI provider account.
 *
 * UX principles:
 *  - No name field — identity is derived from provider + key preview automatically.
 *  - No auth-type selector — each provider has exactly one supported key type:
 *      • Cline       → API Key  (CLINE_API_KEY)
 *      • OpenRouter  → Provisioning Key  (OPENROUTER_PROVISIONING_KEY)
 *      • Nebius      → Billing Session  (NEBIUS_BILLING_SESSION)
 *      • OpenAI      → OAuth Token  (OPENAI_OAUTH)
 *    Regular OpenRouter API keys do not expose the credits endpoint and are not supported.
 *    Nebius does not expose a billing API via API key — a browser session is required.
 *  - For Nebius: "Connect Billing Session →" button opens [NebiusConnectDialog]
 *    which guides users through a console script extraction flow.
 *  - For OpenAI: "Connect OAuth Token →" button opens [OpenAiConnectDialog]
 *    which guides users through OAuth authorization flow.
 *  - For other providers: "Get API Key →" button opens the exact provider page.
 *  - Key preview (first 6 + last 4 chars) is always shown so users can recognise accounts
 *    when multiple keys are configured for the same provider.
 */
class AccountEditDialog(
    private val account: Account?,
    private val existingSecret: String?
) : DialogWrapper(true) {

    // ── Provider selector ──────────────────────────────────────────────────
    private val providerCombo = JComboBox(ProviderId.entries.toTypedArray()).apply {
        selectedItem = account?.providerId ?: ProviderId.CLINE
        renderer = javax.swing.DefaultListCellRenderer().also { r ->
            setRenderer { list, value, index, isSelected, cellHasFocus ->
                r.getListCellRendererComponent(list, (value as ProviderId).displayName, index, isSelected, cellHasFocus)
            }
        }
        addActionListener { updateUiForProvider() }
    }

    // ── Key field (hidden for Nebius and OpenAI) ───────────────────────────
    private val keyField = JBPasswordField().apply {
        text = if (account?.providerId != ProviderId.NEBIUS && account?.providerId != ProviderId.OPENAI) existingSecret ?: "" else ""
        columns = 36
    }

    // ── "Get API Key" button (non-Nebius, non-OpenAI providers) ────────────
    private val getKeyButton = JButton("Get API Key →").apply {
        addActionListener { openKeyGenerationPage() }
    }

    // ── Nebius connect button ──────────────────────────────────────────────
    private val nebiusConnectButton = JButton("Connect Billing Session →").apply {
        addActionListener { openNebiusConnectDialog() }
    }

    // ── OpenAI connect button ──────────────────────────────────────────────
    private val openAiConnectButton = JButton("Connect OAuth Token →").apply {
        addActionListener { openOpenAiConnectDialog() }
    }

    // ── Nebius session status label ────────────────────────────────────────
    private val nebiusStatusLabel = JBLabel(
        if (account?.providerId == ProviderId.NEBIUS && !existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ Session connected</font></html>"
        } else {
            "<html><i>Not connected</i></html>"
        }
    )

    // ── OpenAI OAuth status label ──────────────────────────────────────────
    private val openAiStatusLabel = JBLabel(
        if (account?.providerId == ProviderId.OPENAI && !existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ OAuth connected</font></html>"
        } else {
            "<html><i>Not connected</i></html>"
        }
    )

    /** Holds the captured Nebius session JSON (set after successful NebiusConnectDialog). */
    private var capturedNebiusSession: String? =
        if (account?.providerId == ProviderId.NEBIUS) existingSecret else null

    /** Holds the captured OpenAI OAuth token JSON (set after successful OpenAiConnectDialog). */
    private var capturedOpenAiToken: String? =
        if (account?.providerId == ProviderId.OPENAI) existingSecret else null

    init {
        title = if (account == null) "Add Provider" else "Edit Provider"
        init()
        updateUiForProvider()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun updateUiForProvider() {
        val isNebius = getProvider() == ProviderId.NEBIUS
        val isOpenAi = getProvider() == ProviderId.OPENAI
        keyField.isVisible = !isNebius && !isOpenAi
        getKeyButton.isVisible = !isNebius && !isOpenAi
        nebiusConnectButton.isVisible = isNebius
        nebiusStatusLabel.isVisible = isNebius
        openAiConnectButton.isVisible = isOpenAi
        openAiStatusLabel.isVisible = isOpenAi
        // Update button text based on provider
        if (isOpenAi) {
            getKeyButton.text = "Get API Key →"
            getKeyButton.toolTipText = "Opens the OpenAI API keys page in your browser"
        } else if (isNebius) {
            getKeyButton.text = "Get API Key →"
            getKeyButton.toolTipText = "Opens the Nebius billing page in your browser"
        } else {
            getKeyButton.text = "Get API Key →"
            getKeyButton.toolTipText = "Opens the provider's key management page in your browser"
        }
    }

    /**
     * Returns the single supported [AuthType] for the currently selected provider.
     * Each provider maps to exactly one auth type — no user choice required.
     */
    fun getAuthType(): AuthType = when (getProvider()) {
        ProviderId.OPENROUTER -> AuthType.OPENROUTER_PROVISIONING_KEY
        ProviderId.CLINE -> AuthType.CLINE_API_KEY
        ProviderId.NEBIUS -> AuthType.NEBIUS_BILLING_SESSION
        ProviderId.OPENAI -> AuthType.OPENAI_OAUTH
    }

    /**
     * Returns the secret to store:
     * - For Nebius: the captured session JSON blob.
     * - For OpenAI: the captured OAuth token JSON blob.
     * - For other providers: the raw API key string.
     */
    fun getSecret(): String = when (getProvider()) {
        ProviderId.NEBIUS -> capturedNebiusSession ?: ""
        ProviderId.OPENAI -> capturedOpenAiToken ?: ""
        else -> String(keyField.password).trim()
    }

    /** Kept for compatibility with callers that expect getApiKey(). */
    fun getApiKey(): String = getSecret()

    private fun openKeyGenerationPage() {
        val url = when (getProvider()) {
            ProviderId.CLINE -> "https://app.cline.bot/dashboard/account?tab=api-keys"
            ProviderId.OPENROUTER -> "https://openrouter.ai/settings/provisioning-keys"
            ProviderId.NEBIUS -> NebiusConnectDialog.NEBIUS_URL
            ProviderId.OPENAI -> "https://platform.openai.com/account/api-keys"
        }
        BrowserUtil.browse(url)
    }

    private fun openNebiusConnectDialog() {
        val dialog = NebiusConnectDialog()
        if (dialog.showAndGet()) {
            val json = dialog.capturedSessionJson
            if (!json.isNullOrBlank()) {
                capturedNebiusSession = json
                nebiusStatusLabel.text = "<html><font color='green'><b>✓ Session connected</b></font></html>"
            }
        }
    }

    private fun openOpenAiConnectDialog() {
        val dialog = OpenAiConnectDialog()
        if (dialog.showAndGet()) {
            val apiKey = dialog.capturedApiKey
            if (!apiKey.isNullOrBlank()) {
                capturedOpenAiToken = apiKey
                openAiStatusLabel.text = "<html><font color='green'><b>✓ API key connected</b></font></html>"
            }
        }
    }

    private fun keyHintFor(provider: ProviderId): String = when (provider) {
        ProviderId.CLINE ->
            "Cline personal API key. Click \"Get API Key →\" to open the Cline dashboard."
        ProviderId.OPENROUTER ->
            "OpenRouter <b>Provisioning Key</b> required. Regular API keys do not expose credits info."
        ProviderId.NEBIUS ->
            "Nebius billing uses a browser session. Click \"Connect Billing Session →\" to run the extraction script."
        ProviderId.OPENAI ->
            "OpenAI personal API key for usage/cost data. Click \"Get API Key →\" to open the OpenAI dashboard."
    }

    // ── Panel construction ─────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent = panel {
        row("Provider:") {
            cell(providerCombo)
        }
        // Non-Nebius: API key row
        row {
            cell(getKeyButton)
                .comment("Opens the provider's key management page in your browser")
        }
        row("API Key:") {
            cell(keyField).align(AlignX.FILL).resizableColumn()
        }
        // Nebius: connect session row
        row {
            cell(nebiusConnectButton)
            cell(nebiusStatusLabel).align(AlignX.FILL).resizableColumn()
        }
        // OpenAI: connect OAuth row
        row {
            cell(openAiConnectButton)
            cell(openAiStatusLabel).align(AlignX.FILL).resizableColumn()
        }
        if (account != null && account.keyPreview.isNotEmpty() && account.providerId != ProviderId.NEBIUS && account.providerId != ProviderId.OPENAI) {
            row {
                cell(JBLabel("<html><small>Current key: <b>${account.keyPreview}</b></small></html>"))
            }
        }
        row {
            val provider = providerCombo.selectedItem as? ProviderId ?: ProviderId.CLINE
            comment(keyHintFor(provider))
        }
    }

    // ── Public accessors ───────────────────────────────────────────────────

    fun getProvider(): ProviderId = providerCombo.selectedItem as ProviderId

    // ── Validation ─────────────────────────────────────────────────────────

    override fun doValidate(): ValidationInfo? {
        return when (getProvider()) {
            ProviderId.NEBIUS -> {
                if (capturedNebiusSession.isNullOrBlank()) {
                    ValidationInfo(
                        "Please connect your Nebius billing session first.",
                        nebiusConnectButton
                    )
                } else null
            }
            ProviderId.OPENAI -> {
                if (capturedOpenAiToken.isNullOrBlank()) {
                    ValidationInfo(
                        "Please connect your OpenAI OAuth token first.",
                        openAiConnectButton
                    )
                } else null
            }
            else -> {
                if (getSecret().isEmpty()) {
                    ValidationInfo("API Key is required", keyField)
                } else null
            }
        }
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
