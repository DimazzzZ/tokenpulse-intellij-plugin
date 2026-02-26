package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * Dialog for editing an AI provider account.
 */
class AccountEditDialog(
    private val account: Account?,
    private val apiKey: String?
) : DialogWrapper(true) {
    private val nameField = JTextField(account?.name ?: "")
    private val providerIdField = JTextField(account?.providerId?.name ?: ProviderId.OPENROUTER.name)
    private val authTypeField = JTextField(account?.authType?.name ?: AuthType.OPENROUTER_API_KEY.name)
    private val apiKeyField = JTextField(apiKey ?: "")

    init {
        title = if (account == null) "Add Account" else "Edit Account"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Name:") {
                cell(nameField)
            }
            row("Provider:") {
                cell(providerIdField)
                comment("Valid values: OPENROUTER, CLINE")
            }
            row("Auth Type:") {
                cell(authTypeField)
                comment("Valid values: OPENROUTER_API_KEY, OPENROUTER_PROVISIONING_KEY, CLINE_TOKEN")
            }
            row("API Key:") {
                cell(apiKeyField)
            }
            row {
                cell(JBLabel("Note: Provider and Auth Type are currently case-sensitive.")).applyToComponent {
                    foreground = UIUtil.getContextHelpForeground()
                }
            }
        }
    }

    fun getAccountName(): String = nameField.text.trim()
    fun getProvider(): ProviderId = ProviderId.valueOf(providerIdField.text.trim().uppercase())
    fun getAuthType(): AuthType = AuthType.valueOf(authTypeField.text.trim().uppercase())
    fun getApiKey(): String = apiKeyField.text.trim()

    override fun doValidate(): ValidationInfo? {
        val nameValidation = validateName()
        if (nameValidation != null) return nameValidation

        val providerValidation = validateProvider()
        if (providerValidation != null) return providerValidation

        val authValidation = validateAuthType()
        if (authValidation != null) return authValidation

        return validateApiKey()
    }

    private fun validateName(): ValidationInfo? {
        return if (getAccountName().isEmpty()) ValidationInfo("Name is required", nameField) else null
    }

    private fun validateProvider(): ValidationInfo? {
        return try {
            getProvider()
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ValidationInfo("Invalid Provider ID", providerIdField)
        }
    }

    private fun validateAuthType(): ValidationInfo? {
        return try {
            getAuthType()
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ValidationInfo("Invalid Auth Type", authTypeField)
        }
    }

    private fun validateApiKey(): ValidationInfo? {
        return if (getApiKey().isEmpty()) ValidationInfo("API Key is required", apiKeyField) else null
    }
}
