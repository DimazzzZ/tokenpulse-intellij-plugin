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
 *    Regular OpenRouter API keys do not expose the credits endpoint and are not supported.
 *  - "Get API Key →" button opens the exact provider page for key generation.
 *  - Key preview (first 6 + last 4 chars) is always shown so users can recognise accounts
 *    when multiple keys are configured for the same provider.
 */
class AccountEditDialog(
    private val account: Account?,
    private val apiKey: String?
) : DialogWrapper(true) {

    // ── Provider selector ──────────────────────────────────────────────────
    private val providerCombo = JComboBox(ProviderId.entries.toTypedArray()).apply {
        selectedItem = account?.providerId ?: ProviderId.CLINE
        renderer = javax.swing.DefaultListCellRenderer().also { r ->
            setRenderer { list, value, index, isSelected, cellHasFocus ->
                r.getListCellRendererComponent(list, (value as ProviderId).displayName, index, isSelected, cellHasFocus)
            }
        }
    }

    // ── Key field ──────────────────────────────────────────────────────────
    private val keyField = JBPasswordField().apply {
        text = apiKey ?: ""
        columns = 36
    }

    // ── "Get API Key" button ───────────────────────────────────────────────
    private val getKeyButton = JButton("Get API Key →").apply {
        addActionListener { openKeyGenerationPage() }
    }

    init {
        title = if (account == null) "Add Provider" else "Edit Provider"
        init()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Returns the single supported [AuthType] for the currently selected provider.
     * Each provider maps to exactly one auth type — no user choice required.
     */
    fun getAuthType(): AuthType = when (getProvider()) {
        ProviderId.OPENROUTER -> AuthType.OPENROUTER_PROVISIONING_KEY
        ProviderId.CLINE -> AuthType.CLINE_API_KEY
    }

    private fun openKeyGenerationPage() {
        val url = when (getProvider()) {
            ProviderId.CLINE -> "https://app.cline.bot/dashboard/account?tab=api-keys"
            ProviderId.OPENROUTER -> "https://openrouter.ai/settings/provisioning-keys"
        }
        BrowserUtil.browse(url)
    }

    private fun keyHintFor(provider: ProviderId): String = when (provider) {
        ProviderId.CLINE ->
            "Cline personal API key. Click \"Get API Key →\" to open the Cline dashboard."
        ProviderId.OPENROUTER ->
            "OpenRouter <b>Provisioning Key</b> required. Regular API keys do not expose credits info."
    }

    // ── Panel construction ─────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent = panel {
        row("Provider:") {
            cell(providerCombo)
        }
        row {
            cell(getKeyButton)
                .comment("Opens the provider's key management page in your browser")
        }
        row("API Key:") {
            cell(keyField).align(AlignX.FILL).resizableColumn()
        }
        if (account != null && account.keyPreview.isNotEmpty()) {
            row {
                cell(JBLabel("<html><small>Current key: <b>${account.keyPreview}</b></small></html>"))
            }
        }
        row {
            val provider = providerCombo.selectedItem as? ProviderId ?: ProviderId.CLINE
            comment("<html>${keyHintFor(provider)}</html>")
        }
    }

    // ── Public accessors ───────────────────────────────────────────────────

    fun getProvider(): ProviderId = providerCombo.selectedItem as ProviderId
    fun getApiKey(): String = String(keyField.password).trim()

    // ── Validation ─────────────────────────────────────────────────────────

    override fun doValidate(): ValidationInfo? {
        if (getApiKey().isEmpty()) {
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
